@file:OptIn(com.debanshu777.caraml.sdk.api.ExperimentalCaraMLApi::class)

package com.debanshu777.caraml.sdk.api

import kotlinx.coroutines.flow.Flow
import kotlin.jvm.JvmInline

/**
 * Marks evolving SDK contracts for hosts, engine plugins, providers and stores.
 * Opting in acknowledges possible source and binary changes before a stable release;
 * it does not imply platform certification or persistence compatibility.
 */
@RequiresOptIn("This SDK surface is experimental and may change before the first stable release.")
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.CONSTRUCTOR)
@Retention(AnnotationRetention.BINARY)
annotation class ExperimentalCaraMLApi

@JvmInline value class EngineId(val value: String) { init { require(value.isNotBlank()) } }
@JvmInline value class EngineVersion(val value: String) { init { require(value.isNotBlank()) } }
@JvmInline value class CapabilityId(val value: String) { init { require(value.isNotBlank()) } }
@JvmInline value class ProviderId(val value: String) { init { require(value.isNotBlank()) } }
@JvmInline value class ModelId(val value: String) { init { require(value.isNotBlank()) } }
@JvmInline value class ArtifactId(val value: String) { init { require(value.isNotBlank()) } }
@JvmInline value class OperationId(val value: String) { init { require(value.isNotBlank()) } }
@JvmInline value class TaskId(val value: String) { init { require(value.isNotBlank()) } }

/** Public value passed between discovery, storage and inference. Metadata is provider-neutral. */
data class ModelDescriptor(
    val id: ModelId,
    val displayName: String,
    val format: String? = null,
    val metadata: Map<String, String> = emptyMap(),
) {
    init { require(displayName.isNotBlank()) }
}

data class ArtifactRef(
    val id: ArtifactId,
    val providerId: ProviderId? = null,
    /** Opaque provider revision. It may be a branch, version, hash, or another provider token. */
    val revision: String? = null,
    val expectedSha256: String? = null,
)

data class ArtifactDescriptor(
    val ref: ArtifactRef,
    val fileName: String,
    val sizeBytes: Long? = null,
    val mediaType: String? = null,
) { init { require(fileName.isNotBlank()); require(sizeBytes == null || sizeBytes >= 0) } }

sealed interface ArtifactLocation {
    data class LocalFile(val path: String) : ArtifactLocation
    data class ContentUri(val uri: String) : ArtifactLocation
    data class Opaque(val scheme: String, val value: String) : ArtifactLocation
}

/** A lease prevents managed artifact removal while an engine is using its location. */
interface ArtifactLease : SuspendCloseable {
    val artifact: ArtifactRef
    val location: ArtifactLocation
}

@ExperimentalCaraMLApi
interface SuspendCloseable { suspend fun close() }

@ExperimentalCaraMLApi
interface ModelCatalog {
    suspend fun list(): List<InstalledModel>
    suspend fun upsert(model: InstalledModel)
    suspend fun remove(modelId: ModelId): Boolean
}

data class InstalledModel(
    val descriptor: ModelDescriptor,
    val artifacts: List<ArtifactRef>,
    val installedAtEpochMillis: Long,
)

/**
 * Staged artifact publication with owner/generation fencing and leased access.
 *
 * Implementations must document their durability, restart recovery and coordination scope.
 * This contract does not provide an atomic transaction with an independently supplied
 * [DownloadTaskStore]; hosts requiring coordinated recovery must supply that guarantee.
 */
@ExperimentalCaraMLApi
interface ArtifactStore : ArtifactAccess {
    /** Opens or resumes a stage for this task and advances its write fence. */
    suspend fun stage(descriptor: ArtifactDescriptor, taskId: TaskId, owner: String, generation: Long): StagedArtifact
    suspend fun commit(stage: StagedArtifact, owner: String, generation: Long): ArtifactLease
    suspend fun abort(stage: StagedArtifact)
    override suspend fun acquire(ref: ArtifactRef): ArtifactLease?
    suspend fun remove(ref: ArtifactRef): Boolean
}

/** Minimal artifact boundary required by inference. */
@ExperimentalCaraMLApi
fun interface ArtifactAccess { suspend fun acquire(ref: ArtifactRef): ArtifactLease? }

interface StagedArtifact : SuspendCloseable {
    val descriptor: ArtifactDescriptor
    val taskId: TaskId
    val owner: String
    val generation: Long
    val bytesWritten: Long
    suspend fun write(bytes: ByteArray, owner: String, generation: Long)
}

interface FencedRecord {
    val owner: String
    val generation: Long
}

@ExperimentalCaraMLApi
interface DownloadTaskStore {
    suspend fun create(task: DownloadTask): DownloadTask
    suspend fun read(id: TaskId): DownloadTask?
    /** Atomically takes ownership and advances the fencing generation. */
    suspend fun claim(id: TaskId, owner: String): DownloadTask?
    /** Updates only when owner and generation still match; stale workers receive false. */
    suspend fun update(task: DownloadTask, owner: String, generation: Long): Boolean
    suspend fun listActive(): List<DownloadTask>
}

data class DownloadTask(
    val id: TaskId,
    val artifact: ArtifactDescriptor,
    val state: DownloadTaskState = DownloadTaskState.QUEUED,
    val bytesReceived: Long = 0,
    val totalBytes: Long? = artifact.sizeBytes,
    val owner: String? = null,
    val generation: Long = 0,
    val errorCode: String? = null,
) { init { require(bytesReceived >= 0); require(totalBytes == null || totalBytes >= 0); require(generation >= 0) } }

enum class DownloadTaskState { QUEUED, RUNNING, PAUSED, SUCCEEDED, FAILED, CANCELLED }

data class DownloadProgress(val taskId: TaskId, val bytesReceived: Long, val totalBytes: Long?, val state: DownloadTaskState)

@ExperimentalCaraMLApi
interface ModelProvider {
    val id: ProviderId
    fun search(query: String, pageToken: String? = null): Flow<ModelPage>
    suspend fun resolve(model: ModelCandidate): List<ArtifactDescriptor>
}

data class ModelCandidate(val providerId: ProviderId, val remoteId: String, val name: String, val description: String? = null, val metadata: Map<String, String> = emptyMap())
data class ModelPage(val items: List<ModelCandidate>, val nextPageToken: String? = null)

@ExperimentalCaraMLApi
interface ArtifactSource {
    val providerId: ProviderId
    /** The source owns and closes the response after [consume], including on cancellation.
     * The transfer and its single-use body must not escape this scope.
     */
    suspend fun withTransfer(
        artifact: ArtifactDescriptor,
        offsetBytes: Long = 0,
        consume: suspend (ArtifactTransfer) -> Unit,
    )
}

interface ArtifactTransfer {
    val totalBytes: Long?
    val validator: String?
    val body: Flow<ByteArray>
}

/** Engine-owned request types implement this interface; core never inspects plugin option fields. */
@ExperimentalCaraMLApi
interface EngineRequest {
    val operationId: OperationId
    val capability: CapabilityId
    val model: ModelDescriptor
    val artifact: ArtifactRef
    val requestedResources: ResourceEstimate
    /** Fingerprints effective options and bounded request shape, excluding per-turn input text. */
    val requestFingerprint: String
    val preferredEngineId: EngineId? get() = null
}

data class EngineCapacity(val maxLoadedSessions: Int, val maxConcurrentOperations: Int = 1) {
    init { require(maxLoadedSessions > 0); require(maxConcurrentOperations > 0) }
}

data class ResourceEstimate(
    val residentBytes: Long = 0,
    val transientBytes: Long = 0,
    val outputBytes: Long = 0,
    val diskBytes: Long = 0,
) {
    init { require(residentBytes >= 0 && transientBytes >= 0 && outputBytes >= 0 && diskBytes >= 0) }
    init {
        require(residentBytes <= Long.MAX_VALUE - transientBytes)
        require(residentBytes + transientBytes <= Long.MAX_VALUE - outputBytes)
    }
    val memoryBytes: Long get() = residentBytes + transientBytes + outputBytes
}

data class ResourceSnapshot(
    val availableMemoryBytes: Long? = null,
    val availableStorageBytes: Long? = null,
    val lowMemory: Boolean? = null,
    val thermalStatus: ThermalStatus = ThermalStatus.UNKNOWN,
    val capturedAtEpochMillis: Long,
) {
    init {
        require(availableMemoryBytes == null || availableMemoryBytes >= 0)
        require(availableStorageBytes == null || availableStorageBytes >= 0)
        require(capturedAtEpochMillis >= 0)
    }
}

enum class ThermalStatus { UNKNOWN, NOMINAL, FAIR, SERIOUS, CRITICAL }

@ExperimentalCaraMLApi
fun interface DeviceResourceSource { suspend fun snapshot(): ResourceSnapshot }

data class EnginePlanSummary(
    val engineId: EngineId,
    val engineVersion: EngineVersion,
    val artifactId: ArtifactId,
    val capability: CapabilityId,
    val modelId: ModelId,
    val requestFingerprint: String,
    val resources: ResourceEstimate,
    val effectiveOptionsDescription: String = "",
)

sealed interface PreflightResult {
    data class Ready(val plan: EnginePlan) : PreflightResult
    data class Unsupported(val reason: String) : PreflightResult
    data class RequiresConfirmation(val reason: String, val alternative: EnginePlan? = null) : PreflightResult
}

/** Plugin-authored typed plan. Its implementation and option payload remain private to that plugin. */
@ExperimentalCaraMLApi
interface EnginePlan { val summary: EnginePlanSummary }

@ExperimentalCaraMLApi
interface EngineProvider {
    val id: EngineId
    val version: EngineVersion
    val capabilities: Set<CapabilityId>
    val capacity: EngineCapacity
    suspend fun preflight(request: EngineRequest, artifact: ArtifactLease): PreflightResult
    /**
     * A returned session transfers native ownership to core. If loading throws before returning,
     * the provider must settle and reclaim any partial native work before throwing. If teardown
     * cannot be confirmed, throw [UnreleasedEngineLoad] with the native owner so core can retain
     * it together with the artifact lease and resource reservation.
     */
    suspend fun load(plan: EnginePlan): EngineSession
}

/** SPI failure: the owner may still reference model files or native allocations.
 * Core quarantines this owner and rethrows [cause] (including coroutine cancellation).
 * A direct SPI caller must retain ownership until close succeeds or the process exits.
 */
@ExperimentalCaraMLApi
class UnreleasedEngineLoad(val owner: SuspendCloseable, cause: Throwable) :
    Exception("Native load teardown could not be confirmed", cause)

@ExperimentalCaraMLApi
interface EngineSession : SuspendCloseable {
    val engineId: EngineId
    val model: ModelDescriptor
    suspend fun generate(operationId: OperationId, request: EngineRequest): Flow<GenerationEvent>
    suspend fun cancel(operationId: OperationId)
    /** Successful return confirms native work is quiescent and no longer uses the artifact. */
    suspend fun unload()
    /** Successful return confirms native work is quiescent and no longer uses the artifact. */
    override suspend fun close()
}

sealed interface GenerationEvent {
    data class TextDelta(val text: String, val kind: TextKind = TextKind.CONTENT) : GenerationEvent
    /** Replaces the accumulated text for this channel after a native parser resynchronization. */
    data class TextResync(val text: String, val kind: TextKind = TextKind.CONTENT) : GenerationEvent
    data class Progress(val fraction: Double?, val message: String? = null) : GenerationEvent
    /** A committed media result; publication precedes the terminal Completed event. */
    data class MediaPublished(val result: GeneratedMediaResult) : GenerationEvent
    data class Completed(val reason: CompletionReason, val metrics: GenerationMetrics = GenerationMetrics()) : GenerationEvent
    data class Failed(val error: EngineError) : GenerationEvent
}

enum class TextKind { CONTENT, REASONING }
enum class CompletionReason { END_OF_TURN, STOP_SEQUENCE, LENGTH_LIMIT, CANCELLED }
data class GenerationMetrics(val inputTokens: Long? = null, val outputTokens: Long? = null, val elapsedMillis: Long? = null)

data class EngineError(val code: String, val message: String, val recoverable: Boolean = true) {
    init { require(code.isNotBlank()) }
}

sealed class InferenceException(message: String) : Exception(message) {
    class NoEngine(val capability: CapabilityId) : InferenceException("No engine supports ${capability.value}")
    class Busy(val engineId: EngineId, reason: String) : InferenceException("Engine ${engineId.value} is busy: $reason")
    class Unsupported(detail: String) : InferenceException(detail)
    class InvalidPlan(detail: String) : InferenceException(detail)
    class ResourceUnavailable(detail: String) : InferenceException(detail)
}

interface GeneratedOutputSink {
    suspend fun begin(operationId: OperationId, expectedBytes: Long?): StagedOutput
}
interface StagedOutput : SuspendCloseable {
    suspend fun write(bytes: ByteArray)
    suspend fun commit(metadata: GeneratedMediaMetadata): GeneratedMediaResult
    suspend fun abort()
}
data class GeneratedMediaMetadata(val mimeType: String, val width: Int? = null, val height: Int? = null, val frameRate: Double? = null, val frameCount: Int? = null)
data class GeneratedMediaResult(val id: String, val metadata: GeneratedMediaMetadata, val sizeBytes: Long, val location: ArtifactLocation)
