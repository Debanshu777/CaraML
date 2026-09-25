package com.debanshu777.caraml.core.download

import com.debanshu777.huggingfacemanager.download.DownloadResponseProvenance
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val IOS_BACKGROUND_DESCRIPTOR_VERSION = "1"
private const val IOS_BACKGROUND_ID_LENGTH = 64
private const val IOS_BACKGROUND_MAX_ARTIFACT_BYTES = 1L shl 50
private const val IOS_BACKGROUND_MAX_EXPECTED_BYTES_LENGTH = 16
private const val IOS_BACKGROUND_MIN_DESCRIPTION_LENGTH = 135
private const val IOS_BACKGROUND_MAX_DESCRIPTION_LENGTH = 150
private const val IOS_COMPLETION_ENVELOPE_VERSION = 1
private const val IOS_COMPLETION_ENVELOPE_MAX_BYTES = 2_048

internal enum class IosBackgroundTaskDisposition(val code: String) {
    ACTIVE("a"),
    REJECTED("r"),
    STOPPED("s"),
    ;

    companion object {
        fun fromCode(code: String): IosBackgroundTaskDisposition? = entries.firstOrNull { it.code == code }
    }
}

internal data class IosBackgroundTaskKey(
    val batchId: String,
    val artifactId: String,
) {
    init {
        require(batchId.isCanonicalDownloadId()) { "Invalid background download" }
        require(artifactId.isCanonicalDownloadId()) { "Invalid background download" }
    }
}

/** Only active descriptions own a platform download slot or block replacement scheduling. */
internal fun iosActiveTaskKey(taskDescription: String?): IosBackgroundTaskKey? =
    IosBackgroundTaskDescriptor.decode(taskDescription)
        ?.takeIf { it.disposition == IosBackgroundTaskDisposition.ACTIVE }
        ?.key

/**
 * Exact download identity and terminal disposition persisted by
 * `NSURLSessionTask.taskDescription`.
 *
 * A background session restores the description before delivering delegate callbacks, so both
 * the response limit and a terminal rejection survive process death. Only the current, canonical
 * version is accepted for normal processing; earlier formats are recoverable solely to fail their
 * associated artifact closed.
 */
internal data class IosBackgroundTaskDescriptor(
    val batchId: String,
    val artifactId: String,
    val expectedBytes: Long,
    val disposition: IosBackgroundTaskDisposition = IosBackgroundTaskDisposition.ACTIVE,
) {
    init {
        require(batchId.isCanonicalDownloadId()) { "Invalid background download" }
        require(artifactId.isCanonicalDownloadId()) { "Invalid background download" }
        require(expectedBytes in 1L..IOS_BACKGROUND_MAX_ARTIFACT_BYTES) { "Invalid background download" }
    }

    val key: IosBackgroundTaskKey
        get() = IosBackgroundTaskKey(batchId, artifactId)

    fun withDisposition(value: IosBackgroundTaskDisposition): IosBackgroundTaskDescriptor =
        if (disposition == value) this else copy(disposition = value)

    fun encode(): String =
        "$IOS_BACKGROUND_DESCRIPTOR_VERSION:${disposition.code}:$batchId:$artifactId:$expectedBytes"

    companion object {
        fun decode(value: String?): IosBackgroundTaskDescriptor? {
            if (value == null || value.length !in
                IOS_BACKGROUND_MIN_DESCRIPTION_LENGTH..IOS_BACKGROUND_MAX_DESCRIPTION_LENGTH
            ) {
                return null
            }
            val fields = value.split(':')
            if (fields.size != 5 || fields[0] != IOS_BACKGROUND_DESCRIPTOR_VERSION) return null
            val disposition = IosBackgroundTaskDisposition.fromCode(fields[1]) ?: return null
            val batchId = fields[2]
            val artifactId = fields[3]
            val expectedText = fields[4]
            if (!batchId.isCanonicalDownloadId() || !artifactId.isCanonicalDownloadId()) return null
            if (!expectedText.isCanonicalExpectedBytes()) return null
            val expectedBytes = expectedText.toLongOrNull()
                ?.takeIf { it in 1L..IOS_BACKGROUND_MAX_ARTIFACT_BYTES }
                ?: return null
            val descriptor = IosBackgroundTaskDescriptor(
                batchId = batchId,
                artifactId = artifactId,
                expectedBytes = expectedBytes,
                disposition = disposition,
            )
            return descriptor.takeIf { it.encode() == value }
        }

        /**
         * Recovers only validated opaque identifiers from an invalid/prior description so the
         * matching durable record can be terminally failed. The result must never resume work.
         */
        fun recoverKeyForTerminalFailure(value: String?): IosBackgroundTaskKey? {
            if (value == null || value.length !in 129..IOS_BACKGROUND_MAX_DESCRIPTION_LENGTH) return null
            val fields = value.split(':')
            val (batchId, artifactId) = when (fields.size) {
                2, 3 -> fields[0] to fields[1]
                5 -> {
                    if (fields[0] != IOS_BACKGROUND_DESCRIPTOR_VERSION) return null
                    fields[2] to fields[3]
                }
                else -> return null
            }
            if (!batchId.isCanonicalDownloadId() || !artifactId.isCanonicalDownloadId()) return null
            return IosBackgroundTaskKey(batchId, artifactId)
        }
    }
}

internal data class IosCompletionFlightKey(
    val batchId: String,
    val artifactId: String,
    val platformTaskId: String,
) {
    init {
        require(batchId.isCanonicalDownloadId() && artifactId.isCanonicalDownloadId()) {
            "Invalid completed download"
        }
        require(platformTaskId.isPlatformTaskId()) { "Invalid completed download" }
    }
}

@ConsistentCopyVisibility
internal data class IosValidatedCompletionEnvelope private constructor(
    val taskDescription: String,
    val descriptor: IosBackgroundTaskDescriptor,
    val platformTaskId: String,
    val completedBytes: Long,
    val captureRelativePath: String,
    val response: DownloadResponseProvenance,
) {
    val key: IosCompletionFlightKey
        get() = IosCompletionFlightKey(descriptor.batchId, descriptor.artifactId, platformTaskId)

    companion object {
        fun create(
            taskDescription: String,
            platformTaskId: String,
            completedBytes: Long,
            response: DownloadResponseProvenance,
        ): IosValidatedCompletionEnvelope {
            val descriptor = requireNotNull(IosBackgroundTaskDescriptor.decode(taskDescription)) {
                "Invalid completed download"
            }
            require(descriptor.disposition == IosBackgroundTaskDisposition.ACTIVE) {
                "Invalid completed download"
            }
            require(platformTaskId.isPlatformTaskId() && completedBytes == descriptor.expectedBytes) {
                "Invalid completed download"
            }
            require(DownloadResponseProvenance.validate(response.origin, response.statusCode) == response) {
                "Invalid completed download"
            }
            return IosValidatedCompletionEnvelope(
                taskDescription = taskDescription,
                descriptor = descriptor,
                platformTaskId = platformTaskId,
                completedBytes = completedBytes,
                captureRelativePath = "${descriptor.artifactId}.download",
                response = response,
            )
        }
    }
}

@Serializable
private data class IosValidatedCompletionEnvelopeDto(
    val version: Int,
    val taskDescription: String,
    val batchId: String,
    val artifactId: String,
    val platformTaskId: String,
    val expectedBytes: Long,
    val completedBytes: Long,
    val captureRelativePath: String,
    val responseOrigin: String,
    val statusCode: Int,
)

internal object IosValidatedCompletionEnvelopeCodec {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
        isLenient = false
    }

    fun encode(value: IosValidatedCompletionEnvelope): String {
        val encoded = json.encodeToString(
            IosValidatedCompletionEnvelopeDto(
                version = IOS_COMPLETION_ENVELOPE_VERSION,
                taskDescription = value.taskDescription,
                batchId = value.descriptor.batchId,
                artifactId = value.descriptor.artifactId,
                platformTaskId = value.platformTaskId,
                expectedBytes = value.descriptor.expectedBytes,
                completedBytes = value.completedBytes,
                captureRelativePath = value.captureRelativePath,
                responseOrigin = value.response.origin,
                statusCode = value.response.statusCode,
            ),
        )
        require(encoded.encodeToByteArray().size <= IOS_COMPLETION_ENVELOPE_MAX_BYTES) {
            "Invalid completed download"
        }
        return encoded
    }

    fun decode(value: String): IosValidatedCompletionEnvelope? {
        if (value.length > IOS_COMPLETION_ENVELOPE_MAX_BYTES ||
            value.encodeToByteArray().size > IOS_COMPLETION_ENVELOPE_MAX_BYTES
        ) {
            return null
        }
        val dto = runCatching { json.decodeFromString<IosValidatedCompletionEnvelopeDto>(value) }.getOrNull()
            ?: return null
        if (dto.version != IOS_COMPLETION_ENVELOPE_VERSION) return null
        val response = DownloadResponseProvenance.validate(dto.responseOrigin, dto.statusCode) ?: return null
        val completion = runCatching {
            IosValidatedCompletionEnvelope.create(
                taskDescription = dto.taskDescription,
                platformTaskId = dto.platformTaskId,
                completedBytes = dto.completedBytes,
                response = response,
            )
        }.getOrNull() ?: return null
        return completion.takeIf {
            dto.batchId == it.descriptor.batchId &&
                dto.artifactId == it.descriptor.artifactId &&
                dto.expectedBytes == it.descriptor.expectedBytes &&
                dto.captureRelativePath == it.captureRelativePath
        }
    }
}

/** Coalesces callback and startup recovery for one exact URLSession task generation. */
internal class IosCompletionSingleFlight {
    private val mutex = Mutex()
    private val flights = mutableMapOf<IosCompletionFlightKey, CompletableDeferred<Unit>>()

    suspend fun runOrJoin(key: IosCompletionFlightKey, block: suspend () -> Unit) {
        val selection = mutex.withLock {
            flights[key]?.let { FlightSelection.Follower(it) }
                ?: CompletableDeferred<Unit>().let { deferred ->
                    flights[key] = deferred
                    FlightSelection.Leader(deferred)
                }
        }
        when (selection) {
            is FlightSelection.Follower -> selection.deferred.await()
            is FlightSelection.Leader -> {
                try {
                    block()
                    selection.deferred.complete(Unit)
                } catch (cause: Throwable) {
                    selection.deferred.completeExceptionally(cause)
                    throw cause
                } finally {
                    mutex.withLock {
                        if (flights[key] === selection.deferred) flights.remove(key)
                    }
                }
            }
        }
    }

    suspend fun isActive(key: IosCompletionFlightKey): Boolean = mutex.withLock { key in flights }

    private sealed interface FlightSelection {
        data class Leader(val deferred: CompletableDeferred<Unit>) : FlightSelection
        data class Follower(val deferred: CompletableDeferred<Unit>) : FlightSelection
    }
}

/**
 * Rebinds a restored platform task to the current durable request, not merely its persisted size.
 * A Room row whose metadata changed without its primary key changing must never import bytes for
 * the former request.
 */
internal fun iosPersistedTaskBindingFailure(
    batch: DownloadBatchSnapshot,
    descriptor: IosBackgroundTaskDescriptor,
): DownloadFailureCode? {
    val artifact = batch.artifacts.singleOrNull { it.artifactId == descriptor.artifactId }
        ?: return DownloadFailureCode.INTEGRITY
    if (!artifact.request.metadata.usesImmutableStorageLayout) return DownloadFailureCode.SECURE_PATH
    val canonicalArtifactId = downloadBatchArtifactId(batch.batchId, artifact.request)
    return if (
        descriptor.batchId != batch.batchId ||
        descriptor.artifactId != canonicalArtifactId ||
        artifact.artifactId != canonicalArtifactId ||
        descriptor.expectedBytes != artifact.expectedBytes ||
        artifact.expectedBytes != artifact.request.metadata.artifact.expectedBytes
    ) {
        DownloadFailureCode.INTEGRITY
    } else {
        null
    }
}

internal sealed interface IosDownloadBoundDecision {
    data class Progress(
        val descriptor: IosBackgroundTaskDescriptor,
        val bytesWritten: Long,
    ) : IosDownloadBoundDecision

    data class Reject(val descriptor: IosBackgroundTaskDescriptor) : IosDownloadBoundDecision

    data class Invalid(val recoverableKey: IosBackgroundTaskKey?) : IosDownloadBoundDecision
    data object Duplicate : IosDownloadBoundDecision
    data object Inactive : IosDownloadBoundDecision
}

internal enum class IosDownloadBoundCompletion {
    ACTIVE,
    REJECTED,
    STOPPED,
    INVALID,
    MISSING,
}

/**
 * Opaque point-in-time token for URLSession task restoration.
 *
 * The response-bound owner compares this token by identity. A completion replaces the current
 * token, invalidating every task snapshot captured before that completion without retaining an
 * unbounded set of completed platform task identifiers.
 */
internal class IosRestoreRegistrationGeneration internal constructor()

/** Adapter used by the Foundation delegate to make the durable reason observable before cancel. */
internal fun persistIosRejectionBeforeCancellation(
    descriptor: IosBackgroundTaskDescriptor,
    persistTaskDescription: (String) -> Unit,
    cancel: () -> Unit,
) {
    persistTaskDescription(
        descriptor.withDisposition(IosBackgroundTaskDisposition.REJECTED).encode(),
    )
    cancel()
}

/**
 * Pure state machine behind the iOS URLSession delegate response limit.
 *
 * Its owner serializes access. Keeping Foundation out of this class makes all boundary and
 * restoration behavior executable on every host test target.
 */
internal class IosDownloadResponseBound {
    private val tasks = mutableMapOf<String, TaskState>()
    private var restoreRegistrationGeneration = IosRestoreRegistrationGeneration()

    fun captureRestoreRegistrationGeneration(): IosRestoreRegistrationGeneration =
        restoreRegistrationGeneration

    fun registerRestored(
        taskId: String,
        persistedTaskDescription: String?,
        generation: IosRestoreRegistrationGeneration,
    ): Boolean {
        if (generation !== restoreRegistrationGeneration) return false
        return registerCurrent(taskId, persistedTaskDescription)
    }

    fun registerNewTask(taskId: String, persistedTaskDescription: String?): Boolean =
        registerCurrent(taskId, persistedTaskDescription)

    private fun registerCurrent(taskId: String, persistedTaskDescription: String?): Boolean {
        if (!taskId.isPlatformTaskId()) return false
        val descriptor = IosBackgroundTaskDescriptor.decode(persistedTaskDescription) ?: return false
        if (descriptor.disposition != IosBackgroundTaskDisposition.ACTIVE) return false
        val current = tasks[taskId]
        if (current == null) {
            tasks[taskId] = TaskState.Active(descriptor, highestBytesWritten = -1L)
            return true
        }
        if (!current.descriptor.hasSameBound(descriptor)) {
            tasks[taskId] = TaskState.Rejected(current.descriptor.asRejected())
            return false
        }
        return current is TaskState.Active
    }

    fun inspect(
        taskId: String,
        persistedTaskDescription: String?,
        totalBytesWritten: Long,
        declaredExpectedBytes: Long,
    ): IosDownloadBoundDecision {
        if (!taskId.isPlatformTaskId()) {
            return IosDownloadBoundDecision.Invalid(
                IosBackgroundTaskDescriptor.recoverKeyForTerminalFailure(persistedTaskDescription),
            )
        }
        val descriptor = IosBackgroundTaskDescriptor.decode(persistedTaskDescription)
            ?: return IosDownloadBoundDecision.Invalid(
                IosBackgroundTaskDescriptor.recoverKeyForTerminalFailure(persistedTaskDescription),
            )
        if (descriptor.disposition == IosBackgroundTaskDisposition.STOPPED) {
            return IosDownloadBoundDecision.Inactive
        }
        val state = tasks[taskId]
        if (descriptor.disposition == IosBackgroundTaskDisposition.REJECTED) {
            if (state is TaskState.Rejected && state.descriptor.hasSameBound(descriptor)) {
                return IosDownloadBoundDecision.Inactive
            }
            tasks[taskId] = TaskState.Rejected(descriptor)
            return IosDownloadBoundDecision.Reject(descriptor)
        }
        val active = when {
            state == null -> TaskState.Active(descriptor, highestBytesWritten = -1L)
                .also { tasks[taskId] = it }
            !state.descriptor.hasSameBound(descriptor) -> {
                val rejected = descriptor.asRejected()
                tasks[taskId] = TaskState.Rejected(rejected)
                return IosDownloadBoundDecision.Reject(rejected)
            }
            state is TaskState.Rejected || state is TaskState.Stopped -> {
                return IosDownloadBoundDecision.Inactive
            }
            else -> state as TaskState.Active
        }
        if (totalBytesWritten < 0L || declaredExpectedBytes < -1L ||
            declaredExpectedBytes > descriptor.expectedBytes ||
            totalBytesWritten > descriptor.expectedBytes
        ) {
            val rejected = descriptor.asRejected()
            tasks[taskId] = TaskState.Rejected(rejected)
            return IosDownloadBoundDecision.Reject(rejected)
        }
        if (totalBytesWritten <= active.highestBytesWritten) return IosDownloadBoundDecision.Duplicate
        active.highestBytesWritten = totalBytesWritten
        return IosDownloadBoundDecision.Progress(descriptor, totalBytesWritten)
    }

    /**
     * Returns the description that must be persisted before platform cancellation. A missing state
     * is deliberately not inserted: completion may already have removed this task generation.
     */
    fun stop(taskId: String, persistedTaskDescription: String? = null): IosBackgroundTaskDescriptor? {
        if (!taskId.isPlatformTaskId()) return null
        val current = tasks[taskId]
        val persisted = IosBackgroundTaskDescriptor.decode(persistedTaskDescription)
        val descriptor = current?.descriptor ?: persisted ?: return null
        if (current is TaskState.Rejected ||
            persisted?.disposition == IosBackgroundTaskDisposition.REJECTED
        ) {
            val rejected = descriptor.asRejected()
            if (current != null) tasks[taskId] = TaskState.Rejected(rejected)
            return rejected
        }
        val stopped = descriptor.withDisposition(IosBackgroundTaskDisposition.STOPPED)
        if (current != null) tasks[taskId] = TaskState.Stopped(stopped)
        return stopped
    }

    fun complete(
        taskId: String,
        persistedTaskDescription: String? = null,
    ): IosDownloadBoundCompletion {
        if (taskId.isPlatformTaskId()) {
            restoreRegistrationGeneration = IosRestoreRegistrationGeneration()
        }
        val state = tasks.remove(taskId)
        val persistedDescriptor = IosBackgroundTaskDescriptor.decode(persistedTaskDescription)
        val persistedDisposition = persistedDescriptor?.disposition
        return when {
            state is TaskState.Rejected -> IosDownloadBoundCompletion.REJECTED
            state is TaskState.Stopped -> IosDownloadBoundCompletion.STOPPED
            persistedTaskDescription != null && persistedDescriptor == null ->
                IosDownloadBoundCompletion.INVALID
            persistedDisposition == IosBackgroundTaskDisposition.REJECTED ->
                IosDownloadBoundCompletion.REJECTED
            persistedDisposition == IosBackgroundTaskDisposition.STOPPED ->
                IosDownloadBoundCompletion.STOPPED
            state is TaskState.Active ||
                persistedDisposition == IosBackgroundTaskDisposition.ACTIVE ->
                IosDownloadBoundCompletion.ACTIVE
            else -> IosDownloadBoundCompletion.MISSING
        }
    }

    private sealed interface TaskState {
        val descriptor: IosBackgroundTaskDescriptor

        data class Active(
            override val descriptor: IosBackgroundTaskDescriptor,
            var highestBytesWritten: Long,
        ) : TaskState

        data class Rejected(override val descriptor: IosBackgroundTaskDescriptor) : TaskState
        data class Stopped(override val descriptor: IosBackgroundTaskDescriptor) : TaskState
    }
}

private fun IosBackgroundTaskDescriptor.asRejected(): IosBackgroundTaskDescriptor =
    withDisposition(IosBackgroundTaskDisposition.REJECTED)

private fun IosBackgroundTaskDescriptor.hasSameBound(other: IosBackgroundTaskDescriptor): Boolean =
    batchId == other.batchId && artifactId == other.artifactId && expectedBytes == other.expectedBytes

private fun String.isCanonicalDownloadId(): Boolean =
    length == IOS_BACKGROUND_ID_LENGTH && all { it in '0'..'9' || it in 'a'..'f' }

private fun String.isCanonicalExpectedBytes(): Boolean =
    isNotEmpty() && length <= IOS_BACKGROUND_MAX_EXPECTED_BYTES_LENGTH && first() != '0' &&
        all { it in '0'..'9' }

private fun String.isPlatformTaskId(): Boolean =
    length in 1..20 && all { it in '0'..'9' }
