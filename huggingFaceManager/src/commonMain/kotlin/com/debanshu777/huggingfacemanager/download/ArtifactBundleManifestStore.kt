package com.debanshu777.huggingfacemanager.download

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.Buffer
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.Sink
import okio.buffer

@Serializable
private data class BundlePublicationJournal(
    val version: Int,
    val bundleDigest: String,
)

class ArtifactBundleManifestStore(
    private val ownerRoot: Path,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
    durability: ArtifactDurability? = null,
    private val phaseObserver: (ManifestJournalPhase) -> Unit = {},
    private val artifactValidator: (ArtifactManifestEntry) -> Boolean,
) {
    companion object {
        const val MANIFEST_FILE_NAME = ".caraml-bundle-v1.json"
        const val JOURNAL_FILE_NAME = ".caraml-bundle-v1.journal"
        private const val MAX_JOURNAL_BYTES = 256
    }

    private val manifestPath = ownerRoot / MANIFEST_FILE_NAME
    private val partPath = "$manifestPath.part".toPath()
    private val previousPath = "$manifestPath.previous".toPath()
    private val journalPath = ownerRoot / JOURNAL_FILE_NAME
    private val journalPartPath = "$journalPath.part".toPath()
    private val durability = durability ?: artifactDurability(ownerRoot, fileSystem)
    private val secureRoot = if (fileSystem === FileSystem.SYSTEM) SecureArtifactRoot(ownerRoot) else null
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false; isLenient = false }

    fun publish(entries: Collection<ArtifactManifestEntry>) {
        recover()
        if (entries.map { it.bundleId }.toSet().size != 1) throw ArtifactVerificationException()
        val manifest = ArtifactManifest.create(entries) ?: throw ArtifactVerificationException()
        if (!manifest.entries.all(artifactValidator)) throw ArtifactVerificationException()
        writePart(manifest)
        writeJournal(BundlePublicationJournal(ArtifactManifest.VERSION, manifest.bundleDigest))
        phaseObserver(ManifestJournalPhase.PREPARED)
        preserveOld()
        phaseObserver(ManifestJournalPhase.OLD_PRESERVED)
        move(partPath, manifestPath)
        phaseObserver(ManifestJournalPhase.NEW_PUBLISHED)
        if (readValidated()?.bundleDigest != manifest.bundleDigest) {
            restoreOld()
            throw ArtifactVerificationException()
        }
        finish()
    }

    fun recover() {
        if (!exists(journalPath)) return
        val journal = readBounded(journalPath, MAX_JOURNAL_BYTES)?.let { bytes ->
            runCatching { json.decodeFromString<BundlePublicationJournal>(bytes.decodeToString()) }.getOrNull()
        }?.takeIf {
            it.version == ArtifactManifest.VERSION && it.bundleDigest.length == 64 && it.bundleDigest.all(::isAsciiHexDigit)
        } ?: throw ArtifactVerificationException()

        val published = readValidated()
        if (published?.bundleDigest == journal.bundleDigest) {
            finish()
            return
        }
        val staged = readManifest(partPath)?.takeIf {
            it.bundleDigest == journal.bundleDigest && it.entries.all(artifactValidator)
        }
        if (staged != null) {
            preserveOld()
            move(partPath, manifestPath)
            if (readValidated()?.bundleDigest != journal.bundleDigest) throw ArtifactVerificationException()
            finish()
            return
        }
        if (published != null && !exists(previousPath)) {
            delete(partPath)
            delete(journalPartPath)
            delete(journalPath)
            return
        }
        restoreOld()
    }

    fun readValidated(): ArtifactManifest? = readManifest(manifestPath)?.takeIf { manifest ->
        manifest.entries.map { it.bundleId }.toSet().size == 1 && manifest.entries.all(artifactValidator)
    }

    internal fun readManifestOnly(): ArtifactManifest? =
        sequenceOf(manifestPath, partPath, previousPath)
            .mapNotNull(::readManifest)
            .firstOrNull { manifest -> manifest.entries.map { it.bundleId }.toSet().size == 1 }

    fun close() = secureRoot?.close()

    private fun preserveOld() {
        when {
            exists(manifestPath) && !exists(previousPath) -> move(manifestPath, previousPath)
            !exists(manifestPath) && exists(previousPath) -> Unit
            !exists(manifestPath) && !exists(previousPath) -> Unit
            else -> throw ArtifactVerificationException()
        }
    }

    private fun restoreOld() {
        if (exists(previousPath)) {
            delete(manifestPath)
            move(previousPath, manifestPath)
        } else {
            delete(manifestPath)
        }
        delete(partPath)
        delete(journalPartPath)
        delete(journalPath)
    }

    private fun finish() {
        delete(previousPath)
        delete(partPath)
        delete(journalPartPath)
        delete(journalPath)
    }

    private fun writePart(manifest: ArtifactManifest) {
        val encoded = json.encodeToString(manifest).encodeToByteArray()
        if (encoded.size > ArtifactManifestStore.MAX_MANIFEST_BYTES) throw ArtifactVerificationException()
        delete(partPath)
        write(partPath, encoded)
    }

    private fun writeJournal(journal: BundlePublicationJournal) {
        val encoded = json.encodeToString(journal).encodeToByteArray()
        if (encoded.size > MAX_JOURNAL_BYTES) throw ArtifactVerificationException()
        delete(journalPartPath)
        write(journalPartPath, encoded)
        move(journalPartPath, journalPath)
    }

    private fun write(path: Path, bytes: ByteArray) {
        val rawSink = secureRoot?.sink(relative(path), mustCreate = true) ?: fileSystem.sink(path, mustCreate = true)
        val sink = rawSink.buffer()
        try {
            sink.write(bytes)
        } finally {
            sink.close()
        }
        syncFile(path)
    }

    private fun readManifest(path: Path): ArtifactManifest? =
        readBounded(path, ArtifactManifestStore.MAX_MANIFEST_BYTES)?.let { bytes ->
            runCatching { json.decodeFromString<ArtifactManifest>(bytes.decodeToString()) }.getOrNull()
        }

    private fun readBounded(path: Path, maxBytes: Int): ByteArray? {
        secureRoot?.let { return it.readBounded(relative(path), maxBytes) }
        if (!fileSystem.exists(path) || fileSystem.metadataOrNull(path)?.symlinkTarget != null) return null
        return runCatching {
            val source = fileSystem.source(path)
            try {
                val buffer = Buffer()
                val limit = maxBytes.toLong() + 1L
                while (buffer.size < limit) {
                    val count = source.read(buffer, minOf(8_192L, limit - buffer.size))
                    if (count == -1L) break
                }
                buffer.readByteArray().takeIf { it.size in 1..maxBytes }
            } finally {
                source.close()
            }
        }.getOrNull()
    }

    private fun exists(path: Path): Boolean =
        secureRoot?.existsRegularFile(relative(path)) ?: fileSystem.exists(path)

    private fun move(source: Path, target: Path) {
        secureRoot?.let {
            it.atomicMove(relative(source), relative(target))
            it.syncDirectory(".")
            return
        }
        fileSystem.atomicMove(source, target)
        durability.syncDirectory(".")
    }

    private fun delete(path: Path) {
        if (!exists(path)) return
        secureRoot?.let {
            it.delete(relative(path))
            it.syncDirectory(".")
            return
        }
        fileSystem.delete(path, mustExist = true)
        durability.syncDirectory(".")
    }

    private fun syncFile(path: Path) {
        secureRoot?.let {
            it.syncFile(relative(path))
            return
        }
        val handle = fileSystem.openReadWrite(path, mustCreate = false, mustExist = true)
        try {
            handle.flush()
        } finally {
            handle.close()
        }
        durability.syncFile(relative(path))
    }

    private fun relative(path: Path): String {
        val root = ownerRoot.normalized().toString().trimEnd('/')
        val normalized = path.normalized().toString()
        if (!normalized.startsWith("$root/")) throw ArtifactFileAccessException()
        return normalized.removePrefix("$root/")
    }
}
