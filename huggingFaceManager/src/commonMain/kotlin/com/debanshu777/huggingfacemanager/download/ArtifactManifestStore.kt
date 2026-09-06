package com.debanshu777.huggingfacemanager.download

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.Buffer
import okio.FileSystem
import okio.HashingSource
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer

enum class ManifestJournalPhase {
    PREPARED,
    OLD_PRESERVED,
    NEW_PUBLISHED,
}

class ArtifactVerificationException : Exception("Downloaded artifact could not be verified")

@Serializable
@ConsistentCopyVisibility
data class ArtifactManifestEntry private constructor(
    val logicalRole: String,
    val identity: DownloadArtifactIdentity,
    val byteCount: Long,
    val contentSha256: String,
) {
    init {
        require(isValid(logicalRole, identity, byteCount, contentSha256)) {
            "Invalid artifact manifest entry"
        }
    }

    companion object {
        fun create(
            logicalRole: String,
            identity: DownloadArtifactIdentity,
            byteCount: Long,
            contentSha256: String,
        ): ArtifactManifestEntry? = if (isValid(logicalRole, identity, byteCount, contentSha256)) {
            ArtifactManifestEntry(logicalRole, identity, byteCount, contentSha256.lowercase())
        } else {
            null
        }

        private fun isValid(
            logicalRole: String,
            identity: DownloadArtifactIdentity,
            byteCount: Long,
            contentSha256: String,
        ): Boolean =
            logicalRole.isNotEmpty() && logicalRole.length <= MAX_LOGICAL_ROLE_LENGTH &&
                logicalRole == logicalRole.trim() &&
                logicalRole.all { it.isLetterOrDigit() || it in "._-" } &&
                byteCount == identity.expectedBytes &&
                contentSha256.length == 64 && contentSha256.all(::isAsciiHexDigit)
    }
}

@Serializable
@ConsistentCopyVisibility
data class ArtifactManifest private constructor(
    val version: Int,
    val bundleDigest: String,
    val entries: List<ArtifactManifestEntry>,
) {
    init {
        require(version == VERSION && validateEntries(entries) && bundleDigest == canonicalBundleDigest(entries)) {
            "Invalid artifact manifest"
        }
    }

    companion object {
        const val VERSION: Int = 1

        fun create(entries: Collection<ArtifactManifestEntry>): ArtifactManifest? {
            val snapshot = entries.asSequence().take(MAX_MANIFEST_ENTRIES + 1).toList()
            if (snapshot.size != entries.size || !validateEntries(snapshot)) return null
            val sorted = snapshot.sortedWith(manifestEntryComparator)
            return ArtifactManifest(VERSION, canonicalBundleDigest(sorted), sorted)
        }

        private fun validateEntries(entries: List<ArtifactManifestEntry>): Boolean {
            if (entries.isEmpty() || entries.size > MAX_MANIFEST_ENTRIES) return false
            val roles = HashSet<String>(entries.size)
            val paths = HashSet<String>(entries.size)
            return entries.all { entry ->
                ArtifactManifestEntry.create(
                    entry.logicalRole,
                    entry.identity,
                    entry.byteCount,
                    entry.contentSha256,
                ) != null && roles.add(entry.logicalRole) &&
                    paths.add("${entry.identity.repositoryId}\u0000${entry.identity.relativePath}")
            }
        }
    }
}

private const val MAX_MANIFEST_ENTRIES = 64
private const val MAX_LOGICAL_ROLE_LENGTH = 64

private val manifestEntryComparator = compareBy<ArtifactManifestEntry>(
    { it.logicalRole },
    { it.identity.repositoryId },
    { it.identity.relativePath },
)

private fun canonicalBundleDigest(entries: List<ArtifactManifestEntry>): String {
    val buffer = Buffer()
    entries.sortedWith(manifestEntryComparator).forEach { entry ->
        buffer.writeLengthPrefixed(entry.logicalRole)
        buffer.writeLengthPrefixed(entry.identity.repositoryId)
        buffer.writeLengthPrefixed(entry.identity.immutableRevision)
        buffer.writeLengthPrefixed(entry.identity.relativePath)
        buffer.writeLengthPrefixed(entry.identity.remoteObjectId.orEmpty())
        buffer.writeLong(entry.identity.expectedBytes)
        buffer.writeLong(entry.byteCount)
        buffer.writeLengthPrefixed(entry.contentSha256.lowercase())
    }
    return buffer.snapshot().sha256().hex()
}

private fun Buffer.writeLengthPrefixed(value: String) {
    val bytes = value.encodeToByteArray()
    writeInt(bytes.size)
    write(bytes)
}

@Serializable
private data class ArtifactCommitJournal(
    val version: Int,
    val phase: ManifestJournalPhase,
    val relativePath: String,
)

class ArtifactManifestStore(
    private val modelRoot: Path,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
    private val phaseObserver: (ManifestJournalPhase) -> Unit = {},
) {
    companion object {
        const val MANIFEST_FILE_NAME = ".caraml-artifact-v1.json"
        const val JOURNAL_FILE_NAME = ".caraml-artifact-v1.journal"
        const val MAX_MANIFEST_BYTES = 256 * 1024
        private const val MAX_JOURNAL_BYTES = 4 * 1024
    }

    private val manifestPath = modelRoot / MANIFEST_FILE_NAME
    private val manifestPartPath = "$manifestPath.part".toPath()
    private val manifestPreviousPath = "$manifestPath.previous".toPath()
    private val journalPath = modelRoot / JOURNAL_FILE_NAME
    private val journalPartPath = "$journalPath.part".toPath()
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
        isLenient = false
        explicitNulls = true
    }

    fun read(): ArtifactManifest? = readManifest(manifestPath)

    fun commit(relativePath: String, entry: ArtifactManifestEntry) {
        recover()
        val target = validatedTarget(relativePath) ?: throw IllegalArgumentException("Invalid model file path")
        val staged = target.sibling(PART_SUFFIX)
        if (!validateFile(staged, entry)) throw ArtifactVerificationException()
        val existing = read()?.entries.orEmpty()
        val retained = existing.filterNot {
            it.logicalRole == entry.logicalRole ||
                it.identity.repositoryId == entry.identity.repositoryId &&
                it.identity.relativePath == entry.identity.relativePath
        }
        val nextManifest = ArtifactManifest.create(retained + entry) ?: throw ArtifactVerificationException()
        writeManifestPart(nextManifest)
        writeJournal(ManifestJournalPhase.PREPARED, relativePath)

        preserveOldGeneration(target)
        writeJournal(ManifestJournalPhase.OLD_PRESERVED, relativePath)

        publishNewGeneration(target)
        writeJournal(ManifestJournalPhase.NEW_PUBLISHED, relativePath)

        if (!validateManifest(manifestPath)) {
            restorePreviousGeneration(target)
            throw ArtifactVerificationException()
        }
        finishTransaction(target)
    }

    fun recover() {
        if (!fileSystem.exists(journalPath)) return
        val journal = readJournal() ?: run {
            fileSystem.delete(journalPath, mustExist = false)
            fileSystem.delete(journalPartPath, mustExist = false)
            return
        }
        val target = validatedTarget(journal.relativePath) ?: run {
            fileSystem.delete(journalPath, mustExist = false)
            fileSystem.delete(journalPartPath, mustExist = false)
            return
        }
        when (journal.phase) {
            ManifestJournalPhase.PREPARED -> {
                if (validateManifest(manifestPartPath, target to target.sibling(PART_SUFFIX))) {
                    preserveOldGeneration(target)
                    writeJournal(ManifestJournalPhase.OLD_PRESERVED, journal.relativePath)
                    publishNewGeneration(target)
                    writeJournal(ManifestJournalPhase.NEW_PUBLISHED, journal.relativePath)
                    if (validateManifest(manifestPath)) finishTransaction(target)
                    else restorePreviousGeneration(target)
                } else {
                    discardPreparedGeneration(target)
                }
            }
            ManifestJournalPhase.OLD_PRESERVED -> {
                if (validateManifest(manifestPartPath, target to target.sibling(PART_SUFFIX))) {
                    publishNewGeneration(target)
                    writeJournal(ManifestJournalPhase.NEW_PUBLISHED, journal.relativePath)
                    if (validateManifest(manifestPath)) finishTransaction(target)
                    else restorePreviousGeneration(target)
                } else {
                    restorePreviousGeneration(target)
                }
            }
            ManifestJournalPhase.NEW_PUBLISHED -> {
                if (validateManifest(manifestPath)) finishTransaction(target)
                else restorePreviousGeneration(target)
            }
        }
    }

    private fun writeManifestPart(manifest: ArtifactManifest) {
        val encoded = json.encodeToString(manifest).encodeToByteArray()
        if (encoded.size > MAX_MANIFEST_BYTES) throw ArtifactVerificationException()
        fileSystem.createDirectories(modelRoot)
        fileSystem.delete(manifestPartPath, mustExist = false)
        fileSystem.write(manifestPartPath, mustCreate = true) { write(encoded) }
        sync(manifestPartPath)
    }

    private fun writeJournal(phase: ManifestJournalPhase, relativePath: String) {
        val encoded = json.encodeToString(ArtifactCommitJournal(ArtifactManifest.VERSION, phase, relativePath))
            .encodeToByteArray()
        if (encoded.size > MAX_JOURNAL_BYTES) throw ArtifactVerificationException()
        fileSystem.delete(journalPartPath, mustExist = false)
        fileSystem.write(journalPartPath, mustCreate = true) { write(encoded) }
        sync(journalPartPath)
        fileSystem.atomicMove(journalPartPath, journalPath)
        phaseObserver(phase)
    }

    private fun preserveOldGeneration(target: Path) {
        val previousTarget = target.sibling(PREVIOUS_SUFFIX)
        fileSystem.delete(previousTarget, mustExist = false)
        fileSystem.delete(manifestPreviousPath, mustExist = false)
        if (fileSystem.exists(target)) fileSystem.atomicMove(target, previousTarget)
        if (fileSystem.exists(manifestPath)) fileSystem.atomicMove(manifestPath, manifestPreviousPath)
    }

    private fun publishNewGeneration(target: Path) {
        fileSystem.atomicMove(target.sibling(PART_SUFFIX), target)
        fileSystem.atomicMove(manifestPartPath, manifestPath)
    }

    private fun finishTransaction(target: Path) {
        fileSystem.delete(target.sibling(PREVIOUS_SUFFIX), mustExist = false)
        fileSystem.delete(manifestPreviousPath, mustExist = false)
        fileSystem.delete(manifestPartPath, mustExist = false)
        fileSystem.delete(journalPath, mustExist = false)
        fileSystem.delete(journalPartPath, mustExist = false)
    }

    private fun restorePreviousGeneration(target: Path) {
        val previousTarget = target.sibling(PREVIOUS_SUFFIX)
        fileSystem.delete(target, mustExist = false)
        fileSystem.delete(manifestPath, mustExist = false)
        if (fileSystem.exists(previousTarget)) fileSystem.atomicMove(previousTarget, target)
        if (fileSystem.exists(manifestPreviousPath)) fileSystem.atomicMove(manifestPreviousPath, manifestPath)
        discardPreparedGeneration(target)
    }

    private fun discardPreparedGeneration(target: Path) {
        fileSystem.delete(target.sibling(PART_SUFFIX), mustExist = false)
        fileSystem.delete(manifestPartPath, mustExist = false)
        fileSystem.delete(journalPath, mustExist = false)
        fileSystem.delete(journalPartPath, mustExist = false)
    }

    private fun readManifest(path: Path): ArtifactManifest? = readBounded(path, MAX_MANIFEST_BYTES)?.let { bytes ->
        runCatching { json.decodeFromString<ArtifactManifest>(bytes.decodeToString()) }.getOrNull()
    }

    private fun readJournal(): ArtifactCommitJournal? = readBounded(journalPath, MAX_JOURNAL_BYTES)?.let { bytes ->
        runCatching { json.decodeFromString<ArtifactCommitJournal>(bytes.decodeToString()) }
            .getOrNull()
            ?.takeIf { it.version == ArtifactManifest.VERSION && validatedTarget(it.relativePath) != null }
    }

    private fun readBounded(path: Path, maxBytes: Int): ByteArray? {
        if (!fileSystem.exists(path)) return null
        if (fileSystem.metadataOrNull(path)?.symlinkTarget != null) return null
        val size = fileSystem.metadata(path).size ?: return null
        if (size !in 1L..maxBytes.toLong()) return null
        return runCatching { fileSystem.read(path) { readByteArray() } }.getOrNull()
    }

    private fun validateManifest(
        path: Path,
        stagedOverride: Pair<Path, Path>? = null,
    ): Boolean {
        val manifest = readManifest(path) ?: return false
        return manifest.entries.all { entry ->
            val finalPath = validatedTarget(entry.identity.relativePath) ?: return false
            val pathToCheck = if (stagedOverride?.first == finalPath) stagedOverride.second else finalPath
            validateFile(pathToCheck, entry)
        }
    }

    private fun validateFile(path: Path, entry: ArtifactManifestEntry): Boolean {
        if (fileSystem.metadataOrNull(path)?.symlinkTarget != null) return false
        val size = runCatching { fileSystem.metadata(path).size }.getOrNull() ?: return false
        if (size != entry.byteCount) return false
        val digest = runCatching { sha256(path) }.getOrNull() ?: return false
        return digest == entry.contentSha256 &&
            entry.identity.expectedSha256OrNull()?.let { it == digest } != false
    }

    private fun sha256(path: Path): String {
        val hashing = HashingSource.sha256(fileSystem.source(path))
        val source = hashing.buffer()
        try {
            val sink = Buffer()
            while (source.read(sink, 256L * 1024L) != -1L) sink.clear()
        } finally {
            source.close()
        }
        return hashing.hash.hex()
    }

    private fun sync(path: Path) {
        val handle = fileSystem.openReadWrite(path, mustCreate = false, mustExist = true)
        try {
            handle.flush()
        } finally {
            handle.close()
        }
    }

    private fun validatedTarget(relativePath: String): Path? {
        val validated = runCatching { validateDownloadRequest("owner/model", relativePath).relativePath }.getOrNull()
            ?: return null
        if (validated != relativePath) return null
        val target = (modelRoot / validated).normalized()
        val root = modelRoot.normalized()
        return target.takeIf { it != root && it.toString().startsWith("$root/") }
    }
}

private const val PART_SUFFIX = ".part"
private const val PREVIOUS_SUFFIX = ".previous"

private fun Path.sibling(suffix: String): Path = "$this$suffix".toPath()
