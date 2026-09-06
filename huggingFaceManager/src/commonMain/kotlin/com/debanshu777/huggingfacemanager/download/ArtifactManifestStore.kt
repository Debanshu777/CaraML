package com.debanshu777.huggingfacemanager.download

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.Buffer
import okio.FileSystem
import okio.HashingSource
import okio.Path
import okio.Path.Companion.toPath
import okio.Sink
import okio.buffer

enum class ManifestJournalPhase {
    PREPARED,
    OLD_PRESERVED,
    NEW_PUBLISHED,
    ROLLING_BACK,
}

private enum class ManifestJournalStep {
    PREPARED,
    OLD_TARGET_PRESERVED,
    OLD_MANIFEST_PRESERVED,
    OLD_PRESERVED,
    NEW_TARGET_PUBLISHED,
    NEW_MANIFEST_PUBLISHED,
    NEW_PUBLISHED,
    OLD_TARGET_REMOVED,
    OLD_MANIFEST_REMOVED,
    ROLLBACK_STARTED,
    RESTORING_TARGET,
    TARGET_RESTORED,
    RESTORING_MANIFEST,
    MANIFEST_RESTORED,
    ROLLBACK_VALIDATED,
}

class ArtifactVerificationException : Exception("Downloaded artifact could not be verified")

@Serializable
@ConsistentCopyVisibility
data class ArtifactManifestEntry private constructor(
    val logicalRole: String,
    val identity: DownloadArtifactIdentity,
    val byteCount: Long,
    val contentSha256: String,
    val bundleId: String = requireNotNull(artifactBundleId(listOf(identity))),
    val localRelativePath: String = identity.relativePath,
) {
    init {
        require(isValid(logicalRole, identity, byteCount, contentSha256, bundleId, localRelativePath)) {
            "Invalid artifact manifest entry"
        }
    }

    companion object {
        fun create(
            logicalRole: String,
            identity: DownloadArtifactIdentity,
            byteCount: Long,
            contentSha256: String,
            bundleId: String = requireNotNull(artifactBundleId(listOf(identity))),
            localRelativePath: String = identity.relativePath,
        ): ArtifactManifestEntry? = if (
            isValid(logicalRole, identity, byteCount, contentSha256, bundleId, localRelativePath)
        ) {
            ArtifactManifestEntry(
                logicalRole,
                identity,
                byteCount,
                contentSha256.lowercase(),
                bundleId.lowercase(),
                localRelativePath,
            )
        } else {
            null
        }

        private fun isValid(
            logicalRole: String,
            identity: DownloadArtifactIdentity,
            byteCount: Long,
            contentSha256: String,
            bundleId: String,
            localRelativePath: String,
        ): Boolean =
            logicalRole.isNotEmpty() && logicalRole.length <= MAX_LOGICAL_ROLE_LENGTH &&
                logicalRole == logicalRole.trim() &&
                logicalRole.all { it.isLetterOrDigit() || it in "._-" } &&
                byteCount == identity.expectedBytes &&
                contentSha256.length == 64 && contentSha256.all(::isAsciiHexDigit) &&
                bundleId.length == 64 && bundleId.all(::isAsciiHexDigit) &&
                runCatching {
                    validateDownloadRequest(identity.repositoryId, localRelativePath).relativePath
                }.getOrNull() == localRelativePath
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
                    entry.bundleId,
                    entry.localRelativePath,
                ) != null && roles.add("${entry.bundleId}\u0000${entry.logicalRole}") &&
                    paths.add("${entry.identity.repositoryId}\u0000${entry.localRelativePath}")
            }
        }
    }
}

private const val MAX_MANIFEST_ENTRIES = 64
private const val MAX_LOGICAL_ROLE_LENGTH = 64

private val manifestEntryComparator = compareBy<ArtifactManifestEntry>(
    { it.bundleId },
    { it.logicalRole },
    { it.identity.repositoryId },
    { it.identity.relativePath },
)

private fun canonicalBundleDigest(entries: List<ArtifactManifestEntry>): String {
    val buffer = Buffer()
    entries.sortedWith(manifestEntryComparator).forEach { entry ->
        buffer.writeLengthPrefixed(entry.logicalRole)
        buffer.writeLengthPrefixed(entry.bundleId)
        buffer.writeLengthPrefixed(entry.identity.repositoryId)
        buffer.writeLengthPrefixed(entry.identity.immutableRevision)
        buffer.writeLengthPrefixed(entry.identity.relativePath)
        buffer.writeLengthPrefixed(entry.localRelativePath)
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
    val transactionId: String? = null,
    val hadPreviousGeneration: Boolean? = null,
    val hadPreviousTarget: Boolean? = null,
    val hadPreviousManifest: Boolean? = null,
    val previousManifestDigest: String? = null,
    val previousTargetSha256: String? = null,
    val step: ManifestJournalStep? = null,
)

class ArtifactManifestStore(
    private val modelRoot: Path,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
    durability: ArtifactDurability? = null,
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
    private val durability = durability ?: artifactDurability(modelRoot, fileSystem)
    private val secureRoot = if (fileSystem === FileSystem.SYSTEM) SecureArtifactRoot(modelRoot) else null
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
        isLenient = false
        explicitNulls = true
    }

    fun read(): ArtifactManifest? = readManifest(manifestPath)

    fun readValidated(): ArtifactManifest? = read()?.takeIf { validateManifest(manifestPath) }

    fun containsValidated(entry: ArtifactManifestEntry): Boolean =
        readValidated()?.entries?.singleOrNull {
            it.bundleId == entry.bundleId && it.logicalRole == entry.logicalRole &&
                it.identity == entry.identity && it.localRelativePath == entry.localRelativePath &&
                it.contentSha256 == entry.contentSha256 && it.byteCount == entry.byteCount
        } != null

    internal fun prepareStaged(relativePath: String): Sink {
        val target = validatedTarget(relativePath) ?: throw IllegalArgumentException("Invalid model file path")
        val staged = target.sibling(PART_SUFFIX)
        ensureParent(staged)
        durableDelete(staged)
        return openSink(staged, mustCreate = true)
    }

    internal fun syncStaged(relativePath: String) {
        val target = validatedTarget(relativePath) ?: throw IllegalArgumentException("Invalid model file path")
        sync(target.sibling(PART_SUFFIX))
    }

    internal fun discardStaged(relativePath: String) {
        val target = validatedTarget(relativePath) ?: throw IllegalArgumentException("Invalid model file path")
        durableDelete(target.sibling(PART_SUFFIX))
    }

    internal fun hasPendingTransaction(): Boolean = exists(journalPath)

    internal fun revalidateRoot() = secureRoot?.revalidate()

    internal fun close() = secureRoot?.close()

    fun commit(relativePath: String, entry: ArtifactManifestEntry) {
        recover()
        val target = validatedTarget(relativePath) ?: throw IllegalArgumentException("Invalid model file path")
        val staged = target.sibling(PART_SUFFIX)
        if (!validateFile(staged, entry)) throw ArtifactVerificationException()
        if (exists(target.sibling(PREVIOUS_SUFFIX)) || exists(manifestPreviousPath)) {
            throw ArtifactVerificationException()
        }
        val previousManifest = read()
        val targetExists = exists(target)
        val manifestExists = exists(manifestPath)
        if (manifestExists && (previousManifest == null || !validateManifest(manifestPath))) {
            throw ArtifactVerificationException()
        }
        if (targetExists && previousManifest?.entries?.none { it.localRelativePath == relativePath } != false) {
            throw ArtifactVerificationException()
        }
        val existing = previousManifest?.entries.orEmpty()
        val retained = existing.filterNot {
            it.bundleId == entry.bundleId && it.logicalRole == entry.logicalRole ||
                it.identity.repositoryId == entry.identity.repositoryId &&
                it.localRelativePath == entry.localRelativePath
        }
        val nextManifest = ArtifactManifest.create(retained + entry) ?: throw ArtifactVerificationException()
        writeManifestPart(nextManifest)
        val journal = ArtifactCommitJournal(
            version = ArtifactManifest.VERSION,
            phase = ManifestJournalPhase.PREPARED,
            relativePath = relativePath,
            transactionId = nextManifest.bundleDigest,
            hadPreviousTarget = targetExists,
            hadPreviousManifest = manifestExists,
            previousManifestDigest = previousManifest?.bundleDigest,
            previousTargetSha256 = previousManifest?.entries
                ?.singleOrNull { it.localRelativePath == relativePath }
                ?.contentSha256,
            step = ManifestJournalStep.PREPARED,
        )
        writeJournal(journal)
        continueTransaction(journal, target)
    }

    fun recover() {
        if (!exists(journalPath)) return
        val decoded = readJournal() ?: run {
            durableDelete(journalPartPath)
            durableDelete(journalPath)
            return
        }
        val target = validatedTarget(decoded.relativePath) ?: run {
            durableDelete(journalPartPath)
            durableDelete(journalPath)
            return
        }
        val journal = normalizedJournal(decoded, target) ?: return
        if (journal.phase == ManifestJournalPhase.ROLLING_BACK) {
            restorePreviousGeneration(journal, target)
        } else if (newGenerationIsRecoverable(journal, target)) {
            continueTransaction(journal, target)
        } else {
            restorePreviousGeneration(journal, target)
        }
    }

    private fun normalizedJournal(journal: ArtifactCommitJournal, target: Path): ArtifactCommitJournal? {
        val transactionId = journal.transactionId ?: return null
        if (transactionId.length != 64 || !transactionId.all(::isAsciiHexDigit)) return null
        val hadPreviousTarget = journal.hadPreviousTarget ?: return null
        val hadPreviousManifest = journal.hadPreviousManifest ?: return null
        val step = journal.step ?: return null
        if (!legalJournalState(journal.phase, step)) return null
        if (hadPreviousTarget != (journal.previousTargetSha256 != null) ||
            hadPreviousManifest != (journal.previousManifestDigest != null)
        ) return null
        if (journal.previousTargetSha256?.let(::isSha256) == false ||
            journal.previousManifestDigest?.let(::isSha256) == false
        ) return null
        val normalized = journal.copy(
            transactionId = transactionId,
            hadPreviousTarget = hadPreviousTarget,
            hadPreviousManifest = hadPreviousManifest,
            step = step,
        )
        if (journal.phase != ManifestJournalPhase.ROLLING_BACK) {
            val manifest = readManifest(manifestPartPath) ?: readManifest(manifestPath) ?: return null
            if (manifest.bundleDigest != transactionId) return null
        }
        if (!filesystemMatchesJournalEvidence(normalized, target)) return null
        return normalized
    }

    private fun legalJournalState(phase: ManifestJournalPhase, step: ManifestJournalStep): Boolean = when (phase) {
        ManifestJournalPhase.PREPARED -> step in setOf(
            ManifestJournalStep.PREPARED,
            ManifestJournalStep.OLD_TARGET_PRESERVED,
            ManifestJournalStep.OLD_MANIFEST_PRESERVED,
        )
        ManifestJournalPhase.OLD_PRESERVED -> step in setOf(
            ManifestJournalStep.OLD_PRESERVED,
            ManifestJournalStep.NEW_TARGET_PUBLISHED,
            ManifestJournalStep.NEW_MANIFEST_PUBLISHED,
        )
        ManifestJournalPhase.NEW_PUBLISHED -> step in setOf(
            ManifestJournalStep.NEW_PUBLISHED,
            ManifestJournalStep.OLD_TARGET_REMOVED,
            ManifestJournalStep.OLD_MANIFEST_REMOVED,
        )
        ManifestJournalPhase.ROLLING_BACK -> step in setOf(
            ManifestJournalStep.ROLLBACK_STARTED,
            ManifestJournalStep.RESTORING_TARGET,
            ManifestJournalStep.TARGET_RESTORED,
            ManifestJournalStep.RESTORING_MANIFEST,
            ManifestJournalStep.MANIFEST_RESTORED,
            ManifestJournalStep.ROLLBACK_VALIDATED,
        )
    }

    private fun filesystemMatchesJournalEvidence(journal: ArtifactCommitJournal, target: Path): Boolean {
        val oldIsValid = previousGenerationIsRecoverable(journal, target)
        return when (journal.phase) {
            ManifestJournalPhase.PREPARED -> oldIsValid
            ManifestJournalPhase.OLD_PRESERVED -> oldIsValid
            ManifestJournalPhase.NEW_PUBLISHED -> publishedNewGenerationIsValid(journal, target) ||
                journal.hadPreviousManifest == true && oldIsValid ||
                journal.hadPreviousManifest == false && readManifest(manifestPath)?.bundleDigest == journal.transactionId
            ManifestJournalPhase.ROLLING_BACK -> oldIsValid
        }
    }

    private fun publishedNewGenerationIsValid(journal: ArtifactCommitJournal, target: Path): Boolean {
        val manifest = readManifest(manifestPath) ?: return false
        return manifest.bundleDigest == journal.transactionId && validateManifest(manifestPath)
    }

    private fun previousGenerationIsRecoverable(journal: ArtifactCommitJournal, target: Path): Boolean {
        if (journal.hadPreviousManifest != true) {
            if (journal.hadPreviousTarget == true || exists(manifestPreviousPath) || exists(target.sibling(PREVIOUS_SUFFIX))) {
                return false
            }
            return when (journal.phase) {
                ManifestJournalPhase.PREPARED -> !exists(manifestPath) && !exists(target)
                ManifestJournalPhase.OLD_PRESERVED -> !exists(manifestPreviousPath)
                ManifestJournalPhase.NEW_PUBLISHED -> true
                ManifestJournalPhase.ROLLING_BACK -> true
            }
        }
        val manifestPathToCheck = listOf(manifestPreviousPath, manifestPath).singleOrNull { path ->
            readManifest(path)?.bundleDigest == journal.previousManifestDigest
        } ?: return false
        val oldManifest = readManifest(manifestPathToCheck) ?: return false
        if (journal.hadPreviousTarget != true) {
            return oldManifest.entries.none { it.localRelativePath == journal.relativePath }
        }
        val oldEntry = oldManifest.entries.singleOrNull { it.localRelativePath == journal.relativePath }
            ?: return false
        if (oldEntry.contentSha256 != journal.previousTargetSha256) return false
        val oldTarget = listOf(target.sibling(PREVIOUS_SUFFIX), target).singleOrNull { path ->
            exists(path) && validateFile(path, oldEntry)
        } ?: return false
        return validateManifest(manifestPathToCheck, target to oldTarget)
    }

    private fun isSha256(value: String): Boolean = value.length == 64 && value.all(::isAsciiHexDigit)

    private fun continueTransaction(initial: ArtifactCommitJournal, target: Path) {
        var journal = initial
        if (journal.phase == ManifestJournalPhase.PREPARED) {
            preserveExactlyOnce(target, target.sibling(PREVIOUS_SUFFIX), journal.hadPreviousTarget == true)
            journal = journal.advance(ManifestJournalPhase.PREPARED, ManifestJournalStep.OLD_TARGET_PRESERVED)
            preserveExactlyOnce(manifestPath, manifestPreviousPath, journal.hadPreviousManifest == true)
            journal = journal.advance(ManifestJournalPhase.PREPARED, ManifestJournalStep.OLD_MANIFEST_PRESERVED)
            journal = journal.advance(ManifestJournalPhase.OLD_PRESERVED, ManifestJournalStep.OLD_PRESERVED)
        }
        if (journal.phase == ManifestJournalPhase.OLD_PRESERVED) {
            publishExactlyOnce(target.sibling(PART_SUFFIX), target)
            journal = journal.advance(ManifestJournalPhase.OLD_PRESERVED, ManifestJournalStep.NEW_TARGET_PUBLISHED)
            publishExactlyOnce(manifestPartPath, manifestPath)
            journal = journal.advance(ManifestJournalPhase.OLD_PRESERVED, ManifestJournalStep.NEW_MANIFEST_PUBLISHED)
            journal = journal.advance(ManifestJournalPhase.NEW_PUBLISHED, ManifestJournalStep.NEW_PUBLISHED)
        }
        if (!validateManifest(manifestPath) || readManifest(manifestPath)?.bundleDigest != journal.transactionId) {
            restorePreviousGeneration(journal, target)
            throw ArtifactVerificationException()
        }
        finishTransaction(journal, target)
    }

    private fun newGenerationIsRecoverable(journal: ArtifactCommitJournal, target: Path): Boolean {
        val candidateManifestPath = when {
            exists(manifestPartPath) -> manifestPartPath
            exists(manifestPath) -> manifestPath
            else -> return false
        }
        val candidate = readManifest(candidateManifestPath) ?: return false
        if (candidate.bundleDigest != journal.transactionId) return false
        val candidateTarget = when {
            exists(target.sibling(PART_SUFFIX)) -> target.sibling(PART_SUFFIX)
            exists(target) -> target
            else -> return false
        }
        return validateManifest(candidateManifestPath, target to candidateTarget)
    }

    private fun ArtifactCommitJournal.advance(
        nextPhase: ManifestJournalPhase,
        nextStep: ManifestJournalStep,
    ): ArtifactCommitJournal = copy(phase = nextPhase, step = nextStep).also(::writeJournal)

    private fun writeManifestPart(manifest: ArtifactManifest) {
        val encoded = json.encodeToString(manifest).encodeToByteArray()
        if (encoded.size > MAX_MANIFEST_BYTES) throw ArtifactVerificationException()
        ensureParent(manifestPartPath)
        durableDelete(manifestPartPath)
        val sink = openSink(manifestPartPath, mustCreate = true).buffer()
        try {
            sink.write(encoded)
        } finally {
            sink.close()
        }
        sync(manifestPartPath)
    }

    private fun writeJournal(journal: ArtifactCommitJournal) {
        val encoded = json.encodeToString(journal).encodeToByteArray()
        if (encoded.size > MAX_JOURNAL_BYTES) throw ArtifactVerificationException()
        durableDelete(journalPartPath)
        val sink = openSink(journalPartPath, mustCreate = true).buffer()
        try {
            sink.write(encoded)
        } finally {
            sink.close()
        }
        sync(journalPartPath)
        durableMove(journalPartPath, journalPath)
        if (journal.step == ManifestJournalStep.PREPARED ||
            journal.step == ManifestJournalStep.OLD_PRESERVED ||
            journal.step == ManifestJournalStep.NEW_PUBLISHED
        ) phaseObserver(journal.phase)
    }

    private fun preserveExactlyOnce(source: Path, previous: Path, required: Boolean) {
        val sourceExists = exists(source)
        val previousExists = exists(previous)
        if (!required) {
            if (sourceExists || previousExists) throw ArtifactVerificationException()
            return
        }
        when {
            sourceExists && !previousExists -> durableMove(source, previous)
            !sourceExists && previousExists -> Unit
            else -> throw ArtifactVerificationException()
        }
    }

    private fun publishExactlyOnce(staged: Path, published: Path) {
        val stagedExists = exists(staged)
        val publishedExists = exists(published)
        when {
            stagedExists && !publishedExists -> durableMove(staged, published)
            !stagedExists && publishedExists -> Unit
            else -> throw ArtifactVerificationException()
        }
    }

    private fun finishTransaction(initial: ArtifactCommitJournal, target: Path) {
        var journal = initial
        durableDelete(target.sibling(PREVIOUS_SUFFIX))
        journal = journal.advance(ManifestJournalPhase.NEW_PUBLISHED, ManifestJournalStep.OLD_TARGET_REMOVED)
        durableDelete(manifestPreviousPath)
        journal = journal.advance(ManifestJournalPhase.NEW_PUBLISHED, ManifestJournalStep.OLD_MANIFEST_REMOVED)
        durableDelete(manifestPartPath)
        durableDelete(journalPartPath)
        durableDelete(journalPath)
    }

    private fun restorePreviousGeneration(journal: ArtifactCommitJournal, target: Path) {
        var rollback = if (journal.phase == ManifestJournalPhase.ROLLING_BACK) {
            journal
        } else {
            journal.advance(ManifestJournalPhase.ROLLING_BACK, ManifestJournalStep.ROLLBACK_STARTED)
        }
        if (rollback.step == ManifestJournalStep.ROLLBACK_STARTED) {
            rollback = rollback.advance(ManifestJournalPhase.ROLLING_BACK, ManifestJournalStep.RESTORING_TARGET)
        }
        if (rollback.step == ManifestJournalStep.RESTORING_TARGET) {
            restoreTargetExactlyOnce(rollback, target)
            rollback = rollback.advance(ManifestJournalPhase.ROLLING_BACK, ManifestJournalStep.TARGET_RESTORED)
        }
        if (rollback.step == ManifestJournalStep.TARGET_RESTORED) {
            rollback = rollback.advance(ManifestJournalPhase.ROLLING_BACK, ManifestJournalStep.RESTORING_MANIFEST)
        }
        if (rollback.step == ManifestJournalStep.RESTORING_MANIFEST) {
            restoreManifestExactlyOnce(rollback)
            rollback = rollback.advance(ManifestJournalPhase.ROLLING_BACK, ManifestJournalStep.MANIFEST_RESTORED)
        }
        if (rollback.step == ManifestJournalStep.MANIFEST_RESTORED) {
            validateRestoredGeneration(rollback, target)
            rollback = rollback.advance(ManifestJournalPhase.ROLLING_BACK, ManifestJournalStep.ROLLBACK_VALIDATED)
        }
        if (rollback.step != ManifestJournalStep.ROLLBACK_VALIDATED) throw ArtifactVerificationException()
        discardPreparedGeneration(target)
    }

    private fun restoreTargetExactlyOnce(journal: ArtifactCommitJournal, target: Path) {
        val previousTarget = target.sibling(PREVIOUS_SUFFIX)
        if (journal.hadPreviousTarget != true) {
            durableDelete(target)
            return
        }
        if (exists(previousTarget)) {
            durableDelete(target)
            durableMove(previousTarget, target)
        } else if (!targetMatchesPreviousEvidence(journal, target)) {
            throw ArtifactVerificationException()
        }
    }

    private fun restoreManifestExactlyOnce(journal: ArtifactCommitJournal) {
        if (journal.hadPreviousManifest != true) {
            durableDelete(manifestPath)
            return
        }
        if (exists(manifestPreviousPath)) {
            durableDelete(manifestPath)
            durableMove(manifestPreviousPath, manifestPath)
        } else if (readManifest(manifestPath)?.bundleDigest != journal.previousManifestDigest) {
            throw ArtifactVerificationException()
        }
    }

    private fun targetMatchesPreviousEvidence(journal: ArtifactCommitJournal, target: Path): Boolean {
        val manifest = listOf(manifestPreviousPath, manifestPath)
            .mapNotNull(::readManifest)
            .singleOrNull { it.bundleDigest == journal.previousManifestDigest }
            ?: return false
        val entry = manifest.entries.singleOrNull { it.localRelativePath == journal.relativePath }
            ?: return false
        return entry.contentSha256 == journal.previousTargetSha256 && validateFile(target, entry)
    }

    private fun validateRestoredGeneration(journal: ArtifactCommitJournal, target: Path) {
        if (journal.hadPreviousManifest == true) {
            if (readManifest(manifestPath)?.bundleDigest != journal.previousManifestDigest ||
                !validateManifest(manifestPath)
            ) throw ArtifactVerificationException()
        } else if (exists(manifestPath) || exists(target)) {
            throw ArtifactVerificationException()
        }
    }

    private fun discardPreparedGeneration(target: Path) {
        durableDelete(target.sibling(PART_SUFFIX))
        durableDelete(manifestPartPath)
        durableDelete(journalPartPath)
        durableDelete(journalPath)
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
        secureRoot?.let { return it.readBounded(relativeToRoot(path), maxBytes) }
        if (!fileSystem.exists(path)) return null
        if (fileSystem.metadataOrNull(path)?.symlinkTarget != null) return null
        return runCatching {
            val source = fileSystem.source(path)
            try {
                val sink = Buffer()
                val limit = maxBytes.toLong() + 1L
                while (sink.size < limit) {
                    val read = source.read(sink, minOf(8_192L, limit - sink.size))
                    if (read == -1L) break
                }
                sink.readByteArray().takeIf { it.size in 1..maxBytes }
            } finally {
                source.close()
            }
        }.getOrNull()
    }

    private fun validateManifest(
        path: Path,
        stagedOverride: Pair<Path, Path>? = null,
    ): Boolean {
        val manifest = readManifest(path) ?: return false
        return manifest.entries.all { entry ->
            val finalPath = validatedTarget(entry.localRelativePath) ?: return false
            val pathToCheck = if (stagedOverride?.first == finalPath) stagedOverride.second else finalPath
            validateFile(pathToCheck, entry)
        }
    }

    private fun validateFile(path: Path, entry: ArtifactManifestEntry): Boolean {
        secureRoot?.let { secure ->
            val relative = relativeToRoot(path)
            return secure.size(relative) == entry.byteCount &&
                secure.sha256(relative, entry.byteCount)?.let { digest ->
                    digest == entry.contentSha256 &&
                        entry.identity.expectedSha256OrNull()?.let { it == digest } != false
                } == true
        }
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
        secureRoot?.let {
            it.syncFile(relativeToRoot(path))
            return
        }
        val handle = fileSystem.openReadWrite(path, mustCreate = false, mustExist = true)
        try {
            handle.flush()
        } finally {
            handle.close()
        }
        durability.syncFile(relativeToRoot(path))
    }

    private fun durableMove(source: Path, target: Path) {
        secureRoot?.let {
            it.atomicMove(relativeToRoot(source), relativeToRoot(target))
            it.syncDirectory(relativeToRoot(target.parent ?: throw ArtifactDurabilityException()))
            return
        }
        fileSystem.atomicMove(source, target)
        syncParent(target)
    }

    private fun durableDelete(path: Path) {
        secureRoot?.let {
            if (!it.existsRegularFile(relativeToRoot(path))) return
            it.delete(relativeToRoot(path))
            it.syncDirectory(relativeToRoot(path.parent ?: throw ArtifactDurabilityException()))
            return
        }
        if (!fileSystem.exists(path)) return
        fileSystem.delete(path, mustExist = true)
        syncParent(path)
    }

    private fun syncParent(path: Path) {
        val parent = path.parent ?: throw ArtifactDurabilityException()
        durability.syncDirectory(relativeToRoot(parent))
    }

    private fun ensureParent(path: Path) {
        secureRoot?.let {
            it.createParentDirectories(relativeToRoot(path))
            return
        }
        fileSystem.createDirectories(path.parent ?: throw ArtifactFileAccessException())
    }

    private fun openSink(path: Path, mustCreate: Boolean): Sink =
        secureRoot?.sink(relativeToRoot(path), mustCreate) ?: fileSystem.sink(path, mustCreate)

    private fun exists(path: Path): Boolean =
        secureRoot?.existsRegularFile(relativeToRoot(path)) ?: fileSystem.exists(path)

    private fun relativeToRoot(path: Path): String {
        val root = modelRoot.normalized().toString().trimEnd('/')
        val normalized = path.normalized().toString()
        if (normalized == root) return "."
        if (!normalized.startsWith("$root/")) throw ArtifactDurabilityException()
        return normalized.removePrefix("$root/")
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
