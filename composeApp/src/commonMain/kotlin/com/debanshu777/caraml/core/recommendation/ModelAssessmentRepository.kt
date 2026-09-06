package com.debanshu777.caraml.core.recommendation

import com.debanshu777.caraml.core.platform.BackendCapability
import com.debanshu777.caraml.core.platform.BackendKind
import com.debanshu777.caraml.core.platform.BackendStatus
import com.debanshu777.caraml.core.platform.DeviceSnapshot
import com.debanshu777.caraml.core.platform.HardwareProfile
import com.debanshu777.caraml.core.platform.MemoryTopology
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

@ConsistentCopyVisibility
data class HardwareBackendFingerprint internal constructor(
    val kind: BackendKind,
    val status: BackendStatus,
    val availabilityConfidence: Confidence?,
)

@ConsistentCopyVisibility
data class HardwareFingerprint internal constructor(
    val cpuArchitecture: String,
    val logicalCoreCount: Int,
    val performanceCoreCount: Int?,
    val instructionSets: List<String>,
    val backends: List<HardwareBackendFingerprint>,
    val memoryTopology: MemoryTopology,
)

@ConsistentCopyVisibility
data class AssessmentCacheKey internal constructor(
    val identity: ModelFileIdentity,
    val workload: WorkloadConfig,
    val hardwareFingerprint: HardwareFingerprint,
    val engineVersion: String,
    val estimatorVersion: Int,
    val calibrationRevision: Long,
    internal val descriptor: ModelDescriptor? = null,
    internal val componentIdentities: List<ModelFileIdentity> = listOf(identity),
)

class AssessmentCapacityExceededException : IllegalStateException("Too many assessment calculations are active")

@OptIn(ExperimentalAtomicApi::class)
class ModelAssessmentRepository internal constructor(
    private val suitabilityEngine: SuitabilityEngine,
    private val recommendationPolicy: RecommendationPolicy,
    private val calibrationSource: CalibrationSource = NoCalibrationSource,
    private val assessmentDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val estimatorVersion: Int = PerformanceEstimator.VERSION,
    private val assessmentComputer: suspend (ModelDescriptor, HardwareProfile, WorkloadConfig) -> AssessedPlans =
        { descriptor, hardware, workload -> suitabilityEngine.assessPlans(descriptor, hardware, workload) },
) {
    private val repositoryState = AtomicReference(RepositoryState())
    private val workScope = CoroutineScope(SupervisorJob() + assessmentDispatcher)

    init {
        require(estimatorVersion in 1..MAX_ESTIMATOR_VERSION) { "estimatorVersion is out of range" }
    }

    suspend fun assess(
        descriptor: ModelDescriptor,
        snapshot: DeviceSnapshot,
        workload: WorkloadConfig,
    ): ModelAssessment {
        val preparation = withContext(assessmentDispatcher) {
            prepareAssessment(descriptor, snapshot.hardwareProfile, workload)
        }
        val plans = when (preparation) {
            is AssessmentPreparation.Cancelled -> throw preparation.exception
            is AssessmentPreparation.Unavailable -> {
                return unavailableAssessment(snapshot, preparation.reason)
            }
            is AssessmentPreparation.Ready -> assessedPlans(preparation.key)
        }
        // Only immutable plan estimates are cached. Dynamic budgets and evidence are always rebuilt.
        return suitabilityEngine.assemble(plans, snapshot)
    }

    fun personalize(
        assessment: ModelAssessment,
        snapshot: DeviceSnapshot,
        profile: RecommendationProfile,
    ): PersonalizedRecommendation = recommendationPolicy.recommend(assessment, snapshot, profile)

    fun invalidate(fileIdentity: ModelFileIdentity) {
        val target = fileIdentityFingerprint(fileIdentity) ?: return
        var cancelled: List<Deferred<AssessmentComputation>>
        while (true) {
            val current = repositoryState.load()
            val matchingInFlight = current.inFlight.filterKeys { it.matches(target) }
            val next = current.copy(
                inFlight = current.inFlight - matchingInFlight.keys,
                completed = current.completed.filterKeys { !it.matches(target) },
                completedOrder = current.completedOrder.filterNot { it.matches(target) },
            )
            if (repositoryState.compareAndSet(current, next)) {
                cancelled = matchingInFlight.values.map { it.deferred }
                break
            }
        }
        cancelled.forEach { it.cancel(AssessmentInvalidatedCancellationException()) }
    }

    private fun prepareAssessment(
        descriptor: ModelDescriptor,
        hardware: HardwareProfile,
        workload: WorkloadConfig,
    ): AssessmentPreparation {
        val inputs = try {
            normalizedAssessmentInputs(descriptor, workload, hardware)
        } catch (cancelled: CancellationException) {
            return AssessmentPreparation.Cancelled(cancelled)
        } catch (_: Exception) {
            null
        } ?: return AssessmentPreparation.Unavailable(AssessmentReason.INVALID_METADATA)

        val state = try {
            CalibrationState(
                engineVersion = calibrationSource.engineVersion() ?: UNKNOWN_ENGINE_VERSION,
                revision = calibrationSource.revision(),
            )
        } catch (cancelled: CancellationException) {
            return AssessmentPreparation.Cancelled(cancelled)
        } catch (_: Exception) {
            return AssessmentPreparation.Unavailable(AssessmentReason.INVALID_PERFORMANCE_EVIDENCE)
        }
        val key = assessmentCacheKey(
            inputs = inputs,
            engineVersion = state.engineVersion,
            estimatorVersion = estimatorVersion,
            calibrationRevision = state.revision,
        ) ?: return AssessmentPreparation.Unavailable(AssessmentReason.INVALID_PERFORMANCE_EVIDENCE)
        return AssessmentPreparation.Ready(key)
    }

    private suspend fun assessedPlans(key: AssessmentCacheKey): AssessedPlans {
        val acquired = acquire(key)
        acquired.cached?.let { return it }
        val deferred = requireNotNull(acquired.deferred)
        try {
            return when (val result = deferred.await()) {
                is AssessmentComputation.Success -> {
                    if (!deferred.isCancelled && calibrationIsCurrent(key)) {
                        putCompletedIfCurrent(key, deferred, result.value)
                    }
                    result.value
                }
                is AssessmentComputation.Failure -> throw result.throwable
            }
        } finally {
            release(key, deferred)
        }
    }

    private fun calibrationIsCurrent(key: AssessmentCacheKey): Boolean {
        val current = try {
            CalibrationState(
                engineVersion = calibrationSource.engineVersion() ?: UNKNOWN_ENGINE_VERSION,
                revision = calibrationSource.revision(),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return false
        }
        return validEngineVersion(current.engineVersion) &&
            current.engineVersion == key.engineVersion &&
            current.revision == key.calibrationRevision
    }

    private fun unavailableAssessment(
        snapshot: DeviceSnapshot,
        reason: AssessmentReason,
    ): ModelAssessment {
        val evidence = listOf(Evidence(reason, Confidence.LOW))
        val compatibility = Compatibility.Unknown(listOf(reason), evidence)
        return suitabilityEngine.assemble(
            AssessedPlans(
                values = emptyList(),
                assessmentKey = UNAVAILABLE_ASSESSMENT_KEY,
                compatibility = compatibility,
                memoryTopology = snapshot.hardwareProfile.memoryTopology,
                reasons = listOf(reason),
                evidence = evidence,
            ),
            snapshot,
        )
    }

    private fun acquire(key: AssessmentCacheKey): AcquiredAssessment {
        while (true) {
            val current = repositoryState.load()
            current.completed[key]?.let { cached ->
                val next = current.copy(
                    completedOrder = current.completedOrder.filterNot { it == key } + key,
                )
                if (repositoryState.compareAndSet(current, next)) {
                    return AcquiredAssessment(cached = cached)
                }
                return@let
            }
            current.inFlight[key]?.let { existing ->
                val next = current.copy(
                    inFlight = current.inFlight + (key to existing.copy(consumers = existing.consumers + 1)),
                )
                if (repositoryState.compareAndSet(current, next)) {
                    return AcquiredAssessment(deferred = existing.deferred)
                }
                return@let
            }
            if (current.inFlight.size >= MAX_IN_FLIGHT_ENTRIES) {
                throw AssessmentCapacityExceededException()
            }
            val deferred = workScope.async(start = CoroutineStart.LAZY) {
                try {
                    AssessmentComputation.Success(
                        assessmentComputer(
                            requireNotNull(key.descriptor),
                            key.hardwareFingerprint.toHardwareProfile(),
                            key.workload,
                        ),
                    )
                } catch (failure: Throwable) {
                    AssessmentComputation.Failure(failure)
                }
            }
            val next = current.copy(
                inFlight = current.inFlight + (key to RefCountedAssessment(deferred, consumers = 1)),
            )
            if (repositoryState.compareAndSet(current, next)) {
                return AcquiredAssessment(deferred = deferred)
            }
            deferred.cancel()
        }
    }

    private fun release(key: AssessmentCacheKey, deferred: Deferred<AssessmentComputation>) {
        var cancel: Boolean
        while (true) {
            val current = repositoryState.load()
            val entry = current.inFlight[key]
            if (entry?.deferred !== deferred) return
            val finalConsumer = entry.consumers == 1
            val next = current.copy(
                inFlight = if (finalConsumer) {
                    current.inFlight - key
                } else {
                    current.inFlight + (key to entry.copy(consumers = entry.consumers - 1))
                },
            )
            if (repositoryState.compareAndSet(current, next)) {
                cancel = finalConsumer && !deferred.isCompleted
                break
            }
        }
        if (cancel) deferred.cancel(FinalAssessmentConsumerCancelledException())
    }

    private fun putCompletedIfCurrent(
        key: AssessmentCacheKey,
        deferred: Deferred<AssessmentComputation>,
        value: AssessedPlans,
    ) {
        while (true) {
            val current = repositoryState.load()
            if (current.inFlight[key]?.deferred !== deferred) return
            var order = current.completedOrder.filterNot { it == key } + key
            var completed = current.completed + (key to value)
            if (order.size > MAX_CACHE_ENTRIES) {
                completed = completed - order.first()
                order = order.drop(1)
            }
            val next = current.copy(completed = completed, completedOrder = order)
            if (repositoryState.compareAndSet(current, next)) return
        }
    }

    companion object {
        const val MAX_CACHE_ENTRIES: Int = 128
        const val MAX_IN_FLIGHT_ENTRIES: Int = 128
        private const val MAX_ESTIMATOR_VERSION: Int = 1_000_000
        private const val UNKNOWN_ENGINE_VERSION: String = "engine-unavailable"
        private const val UNAVAILABLE_ASSESSMENT_KEY: String = "assessment-unavailable"
    }
}

internal fun assessmentCacheKey(
    descriptor: ModelDescriptor,
    workload: WorkloadConfig,
    hardware: HardwareProfile,
    engineVersion: String,
    estimatorVersion: Int,
    calibrationRevision: Long,
): AssessmentCacheKey? {
    val inputs = normalizedAssessmentInputs(descriptor, workload, hardware) ?: return null
    return assessmentCacheKey(inputs, engineVersion, estimatorVersion, calibrationRevision)
}

private fun assessmentCacheKey(
    inputs: NormalizedAssessmentInputs,
    engineVersion: String,
    estimatorVersion: Int,
    calibrationRevision: Long,
): AssessmentCacheKey? {
    if (!validEngineVersion(engineVersion) || estimatorVersion !in 1..1_000_000 || calibrationRevision < 0) {
        return null
    }
    val normalizedDescriptor = inputs.descriptor
    val identities = when (normalizedDescriptor) {
        is LlmModelDescriptor -> normalizedDescriptor.files
        is DiffusionModelDescriptor -> normalizedDescriptor.components.map { it.file }
    }
    val identity = when (normalizedDescriptor) {
        is LlmModelDescriptor -> normalizedDescriptor.file
        is DiffusionModelDescriptor -> normalizedDescriptor.components.firstOrNull { it.isPrimary }?.file
            ?: return null
    }
    return AssessmentCacheKey(
        identity = identity,
        workload = inputs.workload,
        hardwareFingerprint = inputs.hardwareFingerprint,
        engineVersion = engineVersion,
        estimatorVersion = estimatorVersion,
        calibrationRevision = calibrationRevision,
        descriptor = normalizedDescriptor,
        componentIdentities = identities,
    )
}

private fun normalizedAssessmentInputs(
    descriptor: ModelDescriptor,
    workload: WorkloadConfig,
    hardware: HardwareProfile,
): NormalizedAssessmentInputs? {
    val normalizedDescriptor = normalizedDescriptor(descriptor) ?: return null
    val normalizedWorkload = normalizedWorkload(workload) ?: return null
    val normalizedHardware = hardwareFingerprint(hardware) ?: return null
    return NormalizedAssessmentInputs(normalizedDescriptor, normalizedWorkload, normalizedHardware)
}

private fun normalizedDescriptor(value: ModelDescriptor): ModelDescriptor? {
    if (!isValidRepositoryId(value.repositoryId) ||
        value.revision.length !in 40..64 || !value.revision.all(Char::isHexDigit) ||
        value.requiredEngineFeatures.size > DescriptorLimits.MAX_METADATA_COLLECTION_SIZE ||
        value.requiredEngineFeatures.any { it.isBlank() || it.length > DescriptorLimits.MAX_METADATA_STRING_LENGTH }
    ) return null
    return when (value) {
        is LlmModelDescriptor -> {
            if (value.files.isEmpty() || value.files.size > DescriptorLimits.MAX_COMPONENTS) return null
            val files = value.files.map { normalizedFileIdentity(it) ?: return null }.sortedBy { it.path }
            if (files.distinctBy { it.path }.size != files.size ||
                files.any { it.repositoryId != value.repositoryId || it.revision != value.revision } ||
                value.architecture?.length?.let { it > DescriptorLimits.MAX_METADATA_STRING_LENGTH } == true
            ) return null
            val normalized = LlmModelDescriptor(
                repositoryId = value.repositoryId,
                revision = value.revision,
                files = files,
                architecture = value.architecture,
                quantization = normalizedQuantization(value.quantization) ?: return null,
                parameterCount = value.parameterCount,
                contextLimit = value.contextLimit,
                transformerShape = value.transformerShape,
                ggufVersion = value.ggufVersion,
                requiredEngineFeatures = value.requiredEngineFeatures.sorted(),
                evidence = emptyList(),
            )
            if (normalized.checkedTotalFileBytes() is CheckedLong.Invalid) return null
            normalized
        }
        is DiffusionModelDescriptor -> {
            if (value.components.isEmpty() || value.components.size > DescriptorLimits.MAX_COMPONENTS ||
                value.family.isBlank() || value.family.length > DescriptorLimits.MAX_METADATA_STRING_LENGTH ||
                value.quantizationDistribution.size > DescriptorLimits.MAX_METADATA_COLLECTION_SIZE ||
                value.quantizationDistribution.any { it.isBlank() || it.length > DescriptorLimits.MAX_METADATA_STRING_LENGTH }
            ) return null
            val components = value.components.map { component ->
                DiffusionComponentDescriptor(
                    file = normalizedFileIdentity(component.file) ?: return null,
                    role = component.role,
                    required = component.required,
                    isPrimary = component.isPrimary,
                    quantization = normalizedQuantization(component.quantization) ?: return null,
                )
            }
            DiffusionModelDescriptor(
                repositoryId = value.repositoryId,
                revision = value.revision,
                components = components,
                mode = value.mode,
                family = value.family,
                architecture = value.architecture,
                width = value.width,
                height = value.height,
                quantizationDistribution = value.quantizationDistribution.sorted(),
                requiredComponentsPresent = value.requiredComponentsPresent,
                requiredEngineFeatures = value.requiredEngineFeatures.sorted(),
                evidence = emptyList(),
            )
        }
    }
}

private fun normalizedWorkload(value: WorkloadConfig): WorkloadConfig? = when (value) {
    is LlmWorkloadConfig -> {
        if (value.allowedKvCacheTypes.size > KvCacheType.entries.size) return null
        LlmWorkloadConfig(
            userRequestedContextTokens = value.userRequestedContextTokens,
            contextTokens = value.contextTokens,
            minimumContextTokens = value.minimumContextTokens,
            promptTokens = value.promptTokens,
            generationReserveTokens = value.generationReserveTokens,
            batchSize = value.batchSize,
            microBatchSize = value.microBatchSize,
            sequenceCount = value.sequenceCount,
            kvCacheSelection = value.kvCacheSelection,
            allowContextFallback = value.allowContextFallback,
            allowBatchFallback = value.allowBatchFallback,
            allowKvCacheFallback = value.allowKvCacheFallback,
            allowedKvCacheTypes = value.allowedKvCacheTypes,
            evidence = emptyList(),
        )
    }
    is DiffusionWorkloadConfig -> DiffusionWorkloadConfig(
        mode = value.mode,
        width = value.width,
        height = value.height,
        minimumWidth = value.minimumWidth,
        minimumHeight = value.minimumHeight,
        frameCount = value.frameCount,
        minimumFrameCount = value.minimumFrameCount,
        batchSize = value.batchSize,
        steps = value.steps,
        vaeTiling = value.vaeTiling,
        offloadToCpu = value.offloadToCpu,
        keepClipOnCpu = value.keepClipOnCpu,
        keepVaeOnCpu = value.keepVaeOnCpu,
        maxVramBytes = value.maxVramBytes,
        layerStreaming = value.layerStreaming,
        allowResolutionFallback = value.allowResolutionFallback,
        allowFrameCountFallback = value.allowFrameCountFallback,
        allowVaeTilingFallback = value.allowVaeTilingFallback,
        allowMaxVramFallback = value.allowMaxVramFallback,
        allowLayerStreamingFallback = value.allowLayerStreamingFallback,
        evidence = emptyList(),
    )
}

private fun hardwareFingerprint(value: HardwareProfile): HardwareFingerprint? {
    if (value.cpuArchitecture.isBlank() || value.cpuArchitecture.length > 128 ||
        value.logicalCoreCount !in 1..1_024 ||
        value.performanceCoreCount?.let { it !in 1..value.logicalCoreCount } == true ||
        value.instructionSets.size > RecommendationPolicyV1.MAX_INSTRUCTION_SETS ||
        value.instructionSets.any { it.isBlank() || it.length > 64 } ||
        value.backends.size > RecommendationPolicyV1.MAX_BACKEND_CAPABILITIES ||
        value.backends.map { it.kind }.distinct().size != value.backends.size
    ) return null
    return HardwareFingerprint(
        cpuArchitecture = value.cpuArchitecture,
        logicalCoreCount = value.logicalCoreCount,
        performanceCoreCount = value.performanceCoreCount,
        instructionSets = value.instructionSets.sorted(),
        backends = value.backends.map {
            HardwareBackendFingerprint(it.kind, it.status, it.availabilityConfidence)
        }.sortedBy { it.kind.name },
        memoryTopology = value.memoryTopology,
    )
}

private fun HardwareFingerprint.toHardwareProfile(): HardwareProfile = HardwareProfile(
    cpuArchitecture = cpuArchitecture,
    logicalCoreCount = logicalCoreCount,
    performanceCoreCount = performanceCoreCount,
    instructionSets = instructionSets,
    backends = backends.map {
        BackendCapability(
            kind = it.kind,
            status = it.status,
            additionalAllocatableBytes = null,
            availabilityConfidence = it.availabilityConfidence,
            headroomConfidence = null,
            evidence = emptyList(),
        )
    },
    memoryTopology = memoryTopology,
    evidence = emptyList(),
)

private fun normalizedFileIdentity(value: ModelFileIdentity): ModelFileIdentity? {
    val fingerprint = fileIdentityFingerprint(value) ?: return null
    return ModelFileIdentity(
        repositoryId = fingerprint.repositoryId,
        revision = fingerprint.revision,
        path = fingerprint.path,
        sizeBytes = fingerprint.sizeBytes,
        gitOid = fingerprint.gitOid,
        lfsOid = fingerprint.lfsOid,
        xetHash = fingerprint.xetHash,
        evidence = emptyList(),
    )
}

private fun fileIdentityFingerprint(value: ModelFileIdentity): FileIdentityFingerprint? {
    if (!isValidRepositoryId(value.repositoryId) ||
        value.revision.length !in 40..64 || !value.revision.all(Char::isHexDigit) ||
        !isValidRelativePath(value.path) ||
        value.sizeBytes !in 1..DescriptorLimits.MAX_FILE_BYTES
    ) return null
    val objectIds = listOf(value.gitOid, value.lfsOid, value.xetHash)
    if (objectIds.any { !isSafeOpaqueIdentity(it) }) {
        return null
    }
    return FileIdentityFingerprint(
        value.repositoryId,
        value.revision,
        value.path,
        value.sizeBytes,
        value.gitOid,
        value.lfsOid,
        value.xetHash,
    )
}

private fun normalizedQuantization(value: QuantizationEvidence): QuantizationEvidence? = when (value) {
    is QuantizationEvidence.Known -> value.takeIf {
        it.quantization.isNotBlank() && it.quantization.length <= DescriptorLimits.MAX_METADATA_STRING_LENGTH
    }
    is QuantizationEvidence.Mixed -> value.takeIf {
        it.quantizations.size <= DescriptorLimits.MAX_METADATA_COLLECTION_SIZE &&
            it.quantizations.all { item -> item.isNotBlank() && item.length <= DescriptorLimits.MAX_METADATA_STRING_LENGTH }
    }?.let { QuantizationEvidence.Mixed(it.quantizations.sorted()) }
    QuantizationEvidence.Unknown -> QuantizationEvidence.Unknown
}

private fun validEngineVersion(value: String): Boolean =
    value.length in 1..DescriptorLimits.MAX_METADATA_STRING_LENGTH &&
        value.first().isLetterOrDigit() &&
        value.all { it.isLetterOrDigit() || it == '.' || it == '_' || it == '+' || it == '-' }

private fun isValidRepositoryId(value: String): Boolean {
    if (value.isEmpty() || value != value.trim() ||
        value.length > DescriptorLimits.MAX_MODEL_ID_LENGTH || '\\' in value
    ) return false
    val segments = value.split('/')
    return segments.size in 1..2 && segments.all { segment ->
        segment.isNotEmpty() &&
            segment.length <= DescriptorLimits.MAX_REPOSITORY_SEGMENT_LENGTH &&
            segment != "." && segment != ".." && ".." !in segment && "--" !in segment &&
            segment.first() != '.' && segment.first() != '-' &&
            segment.last() != '.' && segment.last() != '-' &&
            segment.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }
    }
}

private fun isValidRelativePath(value: String): Boolean {
    if (value.isEmpty() || value != value.trim() || value.length > DescriptorLimits.MAX_RELATIVE_PATH_LENGTH ||
        value.startsWith('/') || value.startsWith('\\') || '\\' in value
    ) return false
    return value.split('/').all { segment ->
        segment.isNotEmpty() && segment != "." && segment != ".." &&
            segment.length <= DescriptorLimits.MAX_PATH_SEGMENT_LENGTH &&
            segment.none { it.code < 32 || it.code == 127 || it in ":*?\"<>|" }
    }
}

private fun isSafeOpaqueIdentity(value: String?): Boolean = value == null ||
    value.isNotEmpty() && value.length <= DescriptorLimits.MAX_METADATA_STRING_LENGTH &&
    value.none { it.code < 32 || it.code == 127 }

private fun Char.isHexDigit(): Boolean = this in '0'..'9' || lowercaseChar() in 'a'..'f'

private data class FileIdentityFingerprint(
    val repositoryId: String,
    val revision: String,
    val path: String,
    val sizeBytes: Long,
    val gitOid: String?,
    val lfsOid: String?,
    val xetHash: String?,
)

private data class CalibrationState(val engineVersion: String, val revision: Long)
private data class NormalizedAssessmentInputs(
    val descriptor: ModelDescriptor,
    val workload: WorkloadConfig,
    val hardwareFingerprint: HardwareFingerprint,
)

private sealed interface AssessmentPreparation {
    data class Ready(val key: AssessmentCacheKey) : AssessmentPreparation
    data class Unavailable(val reason: AssessmentReason) : AssessmentPreparation
    data class Cancelled(val exception: CancellationException) : AssessmentPreparation
}

private data class RepositoryState(
    val inFlight: Map<AssessmentCacheKey, RefCountedAssessment> = emptyMap(),
    val completed: Map<AssessmentCacheKey, AssessedPlans> = emptyMap(),
    val completedOrder: List<AssessmentCacheKey> = emptyList(),
)

private fun AssessmentCacheKey.matches(identity: FileIdentityFingerprint): Boolean =
    componentIdentities.any { fileIdentityFingerprint(it) == identity }

private sealed interface AssessmentComputation {
    data class Success(val value: AssessedPlans) : AssessmentComputation
    data class Failure(val throwable: Throwable) : AssessmentComputation
}

private data class RefCountedAssessment(val deferred: Deferred<AssessmentComputation>, val consumers: Int)
private data class AcquiredAssessment(
    val cached: AssessedPlans? = null,
    val deferred: Deferred<AssessmentComputation>? = null,
)

private class FinalAssessmentConsumerCancelledException : CancellationException("No assessment consumers remain")
private class AssessmentInvalidatedCancellationException : CancellationException("Assessment identity was invalidated")
