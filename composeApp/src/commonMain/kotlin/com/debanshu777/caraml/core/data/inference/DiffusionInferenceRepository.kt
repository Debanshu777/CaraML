package com.debanshu777.caraml.core.data.inference

import com.debanshu777.caraml.core.platform.AppLogger
import com.debanshu777.caraml.core.platform.BackendKind
import com.debanshu777.caraml.core.platform.DeviceCapabilities
import com.debanshu777.caraml.core.recommendation.DeviceSnapshotProvider
import com.debanshu777.caraml.core.recommendation.DiffusionRunPlan
import com.debanshu777.caraml.core.recommendation.InferenceObservationPhase
import com.debanshu777.caraml.core.recommendation.InferenceObservationPlan
import com.debanshu777.caraml.core.recommendation.InferenceObservationRecorder
import com.debanshu777.caraml.core.recommendation.diffusionObservationUnits
import com.debanshu777.caraml.core.recommendation.MeasuredResult
import com.debanshu777.caraml.core.recommendation.ObservationOutcome
import com.debanshu777.caraml.core.recommendation.CoordinatedLoadResult
import com.debanshu777.caraml.core.recommendation.LoadAdmissionController
import com.debanshu777.caraml.core.recommendation.LoadRecoveryRepository
import com.debanshu777.caraml.core.recommendation.LoadRequest
import com.debanshu777.caraml.core.recommendation.LoadSessionCoordinator
import com.debanshu777.caraml.core.recommendation.LocalArtifactIdentityResolver
import com.debanshu777.caraml.core.recommendation.NativeLoadPreflight
import com.debanshu777.caraml.core.recommendation.NativeLoadOutcome
import com.debanshu777.caraml.core.recommendation.NATIVE_LOAD_ENGINE_VERSION
import com.debanshu777.caraml.core.recommendation.NativeRunPlanAdapter
import com.debanshu777.caraml.core.recommendation.PersonalizedRecommendation
import com.debanshu777.caraml.core.recommendation.RecommendationCategory
import com.debanshu777.caraml.core.recommendation.toNativeDiffusionBudget
import com.debanshu777.caraml.core.recommendation.RecommendationPolicy
import com.debanshu777.caraml.core.recommendation.StableLoadFailure
import com.debanshu777.caraml.core.recommendation.SuitabilityEngine
import com.debanshu777.caraml.core.recommendation.VerifiedArtifactLoadTarget
import com.debanshu777.caraml.core.recommendation.toInferenceObservationPlan
import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import com.debanshu777.diffusionrunner.DiffusionModelConfig
import com.debanshu777.diffusionrunner.DiffusionBackendDeviceType
import com.debanshu777.diffusionrunner.DiffusionBackendKind
import com.debanshu777.diffusionrunner.DiffusionComponentRole
import com.debanshu777.diffusionrunner.DiffusionFitReport
import com.debanshu777.diffusionrunner.DiffusionParameterPlacement
import com.debanshu777.diffusionrunner.DiffusionPreflightComponent
import com.debanshu777.diffusionrunner.DiffusionPreflightResult
import com.debanshu777.diffusionrunner.DiffusionRunner
import com.debanshu777.diffusionrunner.DiffusionRuntimePlacement
import com.debanshu777.diffusionrunner.ImageGenParams
import com.debanshu777.diffusionrunner.VideoGenParams
import com.debanshu777.diffusionrunner.generateImage
import com.debanshu777.diffusionrunner.generateVideo
import com.debanshu777.caraml.core.platform.PlatformPaths
import com.debanshu777.caraml.core.data.settings.SettingsRepository
import com.debanshu777.huggingfacemanager.sdcpp.SdCppRecommendedParams
import com.debanshu777.huggingfacemanager.sdcpp.getModelSetup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile

/**
 * Loads stable-diffusion.cpp models via [DiffusionRunner], resolving all auxiliary components 
 * (VAE, CLIP, T5, LLM) based on model setup requirements.
 */
class DiffusionInferenceRepository(
    private val runner: DiffusionRunner,
    private val deviceCapabilities: DeviceCapabilities,
    private val settingsRepository: SettingsRepository,
    private val snapshotProvider: DeviceSnapshotProvider? = null,
    private val suitabilityEngine: SuitabilityEngine? = null,
    private val recommendationPolicy: RecommendationPolicy? = null,
    private val loadRecoveryRepository: LoadRecoveryRepository? = null,
    private val artifactIdentityResolver: LocalArtifactIdentityResolver? = null,
    private val loadSessionCoordinator: LoadSessionCoordinator? = null,
    private val engineVersion: String = NATIVE_LOAD_ENGINE_VERSION,
    private val observationRecorder: InferenceObservationRecorder? = null,
) {
    private val nativeSession = NativeSessionGate()
    private var lastLoadedArchStr: String? = null
    private var loadedWeightsBytes: Long = 0L
    private val exactExecutionState = AdmittedDiffusionExecutionState()
    @Volatile private var generationObservation: InferenceObservationPlan? = null

    /**
     * Live diffusion progress.
     * - [step] / [totalSteps] are 0 during pre-sampling (text encode, latent prep), then jump
     *   to (1, requestedSteps) once denoising starts.
     * - [requestedSteps] is the user-configured step budget — known upfront from the params.
     * - [elapsedSeconds] is monotonically increasing; lets the UI show "Preparing… 12s".
     */
    data class DiffusionProgress(
        val step: Int,
        val totalSteps: Int,
        val requestedSteps: Int,
        val elapsedSeconds: Int,
    )

    private val _imageGenProgress = MutableStateFlow<DiffusionProgress?>(null)
    val imageGenProgress: StateFlow<DiffusionProgress?> = _imageGenProgress.asStateFlow()
    companion object {
        private const val TAG = "DiffusionInference"
    }

    suspend fun loadModel(request: LoadRequest): ModelLoadResult = nativeSession.exclusive {
        withContext(Dispatchers.Default) {
            val artifact = request.artifact
                ?: return@withContext ModelLoadResult.Error("The installed model could not be verified.")
            if (artifact.identity != request.identity) {
                return@withContext ModelLoadResult.Error("The installed model identity is invalid.")
            }
            val plan = request.plan as? DiffusionRunPlan
                ?: return@withContext ModelLoadResult.Error("The selected model configuration is invalid.")
            val resolver = artifactIdentityResolver
                ?: return@withContext ModelLoadResult.Error("Load admission is unavailable.")
            val coordinator = loadSessionCoordinator
                ?: return@withContext ModelLoadResult.Error("Load recovery is unavailable.")
            val loadTarget = artifact.loadTarget
            val modelPath = loadTarget.path
            val nativeLibDir = PlatformPaths.getNativeLibDir()
            if (nativeLibDir.isBlank()) {
                return@withContext ModelLoadResult.Error("Failed to initialize. Please restart the app.")
            }
            val baseConfig = exactBaseConfig(request.model.modelId, artifact, loadTarget)
            val execution = runCatching { NativeRunPlanAdapter.toDiffusionExecutionConfig(plan, baseConfig) }
                .getOrElse { return@withContext ModelLoadResult.Error("The selected model configuration is invalid.") }
            val loadObservation = request.toInferenceObservationPlan(engineVersion, InferenceObservationPhase.LOAD)
            val nextGenerationObservation =
                request.toInferenceObservationPlan(engineVersion, InferenceObservationPhase.GENERATION)
            try {
                runner.release()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                return@withContext ModelLoadResult.Error("The previous model could not be released safely.")
            }
            lastLoadedArchStr = null
            loadedWeightsBytes = 0L
            exactExecutionState.clear()
            generationObservation = null
            try {
                runner.initialize(nativeLibDir)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                return@withContext ModelLoadResult.Error("Failed to initialize. Please restart the app.")
            }
            val controller = admissionController { candidate ->
                val candidatePlan = candidate.plan as? DiffusionRunPlan
                    ?: return@admissionController NativeLoadPreflight.Invalid
                val candidateArtifact = candidate.artifact
                    ?: return@admissionController NativeLoadPreflight.Invalid
                val candidateTarget = candidateArtifact.loadTarget
                val candidateBase = exactBaseConfig(candidate.model.modelId, candidateArtifact, candidateTarget)
                val candidateExecution = runCatching {
                    NativeRunPlanAdapter.toDiffusionExecutionConfig(candidatePlan, candidateBase)
                }.getOrElse { return@admissionController NativeLoadPreflight.Invalid }
                exactDiffusionNativePreflight(
                    candidatePlan,
                    candidateExecution.model,
                    runner.preflightModel(candidateExecution.model),
                )
            } ?: return@withContext ModelLoadResult.Error("Load admission is unavailable.")
            try {
                when (val result = coordinator.execute(
                    request = request,
                    evaluateAdmission = { controller.evaluate(request, request.riskAcknowledgement) },
                    artifactValidator = { candidate ->
                        candidate.artifact?.let { resolver.revalidate(it) } == true
                    },
                    releasePartialState = {
                        runner.release()
                        lastLoadedArchStr = null
                        loadedWeightsBytes = 0L
                        exactExecutionState.clear()
                        generationObservation = null
                    },
                    nativeLoad = {
                        val loaded = loadObservation?.let { observation ->
                            observationRecorder?.measureLoad(observation.key, observation.prediction) {
                                val succeeded = runner.loadModel(execution.model)
                                MeasuredResult(
                                    value = succeeded,
                                    completedUnits = 1,
                                    outcome = if (succeeded) {
                                        ObservationOutcome.SUCCESS
                                    } else {
                                        ObservationOutcome.UNKNOWN_FAILURE
                                    },
                                )
                            }
                        } ?: runner.loadModel(execution.model)
                        if (!loaded) {
                            return@execute NativeLoadOutcome.Failed(
                                ModelLoadResult.Error("The model could not be loaded with this configuration."),
                                StableLoadFailure.UNKNOWN,
                            )
                        }
                        loadedWeightsBytes = artifact.components.fold(0L) { total, component ->
                            if (total > Long.MAX_VALUE - component.byteCount) Long.MAX_VALUE else total + component.byteCount
                        }
                        lastLoadedArchStr = runner.getDiffusionModelMetadata(modelPath)?.architecture
                        exactExecutionState.publish(execution)
                        generationObservation = nextGenerationObservation
                        NativeLoadOutcome.Succeeded(ModelLoadResult.Success(contextSize = 0))
                    },
                )) {
                    is CoordinatedLoadResult.AdmissionRequired ->
                        ModelLoadResult.AdmissionRequired(result.admission)
                    is CoordinatedLoadResult.ArtifactChanged ->
                        ModelLoadResult.Error("The installed model changed and could not be verified.")
                    is CoordinatedLoadResult.Completed -> result.value
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                runCatching { runner.release() }
                lastLoadedArchStr = null
                loadedWeightsBytes = 0L
                exactExecutionState.clear()
                generationObservation = null
                ModelLoadResult.Error("An error occurred while loading the model.")
            }
        }
    }

    suspend fun allowExplicitRetry(request: LoadRequest) {
        loadSessionCoordinator?.allowExplicitRetry(request, engineVersion)
    }

    private suspend fun exactBaseConfig(
        modelId: String,
        artifact: com.debanshu777.caraml.core.recommendation.ResolvedLocalArtifact,
        loadTarget: VerifiedArtifactLoadTarget,
    ): DiffusionModelConfig {
        val paths = artifact.components.associate { it.logicalRole.lowercase() to it.localPath }
        val setup = getModelSetup(modelId)
        val params = setup?.recommendedParams
        val settings = settingsRepository.getSettings().first()
        val hints = deviceCapabilities.getDeviceHints()
        val gpuEnabled = settings.useGpu && hints.gpuBackendAvailable
        return DiffusionModelConfig(
            modelPath = loadTarget.path,
            vaePath = paths["vae"].orEmpty(),
            llmPath = paths["llm"].orEmpty(),
            clipLPath = paths["clip_l"].orEmpty(),
            clipGPath = paths["clip_g"].orEmpty(),
            t5xxlPath = paths["t5xxl"] ?: paths["umt5xxl"].orEmpty(),
            diffusionFlashAttn = gpuEnabled,
            freeParamsImmediately = false,
            flowShift = params?.flowShift ?: Float.POSITIVE_INFINITY,
            prediction = params?.prediction ?: -1,
        )
    }

    private fun admissionController(
        nativePreflight: suspend (LoadRequest) -> NativeLoadPreflight,
    ): LoadAdmissionController? {
        val snapshots = snapshotProvider ?: return null
        val engine = suitabilityEngine ?: return null
        val policy = recommendationPolicy ?: return null
        val recovery = loadRecoveryRepository ?: return null
        return LoadAdmissionController(
            snapshotSource = snapshots::capture,
            recommendationSource = { candidate, snapshot ->
                candidate.assessedPlans?.let { plans ->
                    policy.recommend(engine.assemble(plans, snapshot), snapshot, candidate.profile)
                } ?: PersonalizedRecommendation(
                    assessmentKey = candidate.assessmentKey,
                    category = RecommendationCategory.NEEDS_INFORMATION,
                    selectedPlan = null,
                    reasons = emptyList(),
                    profile = candidate.profile,
                )
            },
            artifactValidator = { candidate ->
                candidate.artifact?.let { artifactIdentityResolver?.revalidate(it) } == true
            },
            nativePreflight = nativePreflight,
            recoveryState = recovery,
            engineVersion = engineVersion,
            clock = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
        )
    }

    suspend fun generateImage(params: ImageGenParams): Result<ByteArray> =
        nativeSession.exclusive {
            withContext(Dispatchers.Default) {
                val executionParams = exactExecutionState.applyTo(params)
                if (executionParams == null) {
                    return@withContext Result.failure(Exception("The selected model configuration does not support images."))
                }
                generationMemoryError(executionParams.width, executionParams.height, frames = 1)?.let {
                    return@withContext Result.failure(it)
                }
            val startMs = kotlin.time.TimeSource.Monotonic.markNow()
            _imageGenProgress.value = DiffusionProgress(
                step = 0, totalSteps = 0, requestedSteps = executionParams.steps, elapsedSeconds = 0,
            )
            val pollJob = launch {
                while (isActive) {
                    delay(250)
                    val raw = runner.getStepProgress()
                    _imageGenProgress.value = DiffusionProgress(
                        step = raw.getOrElse(0) { 0 },
                        totalSteps = raw.getOrElse(1) { 0 },
                        requestedSteps = executionParams.steps,
                        elapsedSeconds = startMs.elapsedNow().inWholeSeconds.toInt(),
                    )
                }
            }
            try {
                val r = observeGeneration(executionParams.steps) {
                    runner.generateImage(executionParams)
                }
                if (r.isFailure) AppLogger.e(TAG, "generateImage failed")
                r.fold(
                    onSuccess = { Result.success(it) },
                    onFailure = { Result.failure(Exception("Generation failed. Please try again.")) },
                )
            } finally {
                pollJob.cancel()
                _imageGenProgress.value = null
            }
        }
    }

    suspend fun generateVideo(params: VideoGenParams): Result<List<ByteArray>> =
        nativeSession.exclusive {
            withContext(Dispatchers.Default) {
                val executionParams = exactExecutionState.applyTo(params)
                if (executionParams == null) {
                    return@withContext Result.failure(Exception("The selected model configuration does not support video."))
                }
                generationMemoryError(executionParams.width, executionParams.height, executionParams.videoFrames)?.let {
                    return@withContext Result.failure(it)
                }
            val startMs = kotlin.time.TimeSource.Monotonic.markNow()
            _imageGenProgress.value = DiffusionProgress(
                step = 0, totalSteps = 0, requestedSteps = executionParams.steps, elapsedSeconds = 0,
            )
            val pollJob = launch {
                while (isActive) {
                    delay(250)
                    val raw = runner.getStepProgress()
                    _imageGenProgress.value = DiffusionProgress(
                        step = raw.getOrElse(0) { 0 },
                        totalSteps = raw.getOrElse(1) { 0 },
                        requestedSteps = executionParams.steps,
                        elapsedSeconds = startMs.elapsedNow().inWholeSeconds.toInt(),
                    )
                }
            }
            try {
                val completedUnits = diffusionObservationUnits(
                    executionParams.steps,
                    executionParams.videoFrames,
                ) ?: return@withContext Result.failure(Exception("The selected video workload is invalid."))
                val r = observeGeneration(completedUnits) {
                    runner.generateVideo(executionParams)
                }
                if (r.isFailure) AppLogger.e(TAG, "generateVideo failed")
                r.fold(
                    onSuccess = { Result.success(it.frames) },
                    onFailure = { Result.failure(Exception("Generation failed. Please try again.")) },
                )
            } finally {
                pollJob.cancel()
                _imageGenProgress.value = null
            }
        }
    }

    suspend fun release() = nativeSession.exclusive {
        withContext(Dispatchers.Default) {
            runner.release()
            lastLoadedArchStr = null
            loadedWeightsBytes = 0L
            exactExecutionState.clear()
            generationObservation = null
        }
    }

    fun cancelGeneration(): Boolean = runner.cancelGeneration()

    private suspend fun <T> observeGeneration(
        completedUnits: Int,
        block: suspend () -> Result<T>,
    ): Result<T> {
        val observation = generationObservation
        val recorder = observationRecorder
        if (observation == null || recorder == null) return block()
        return recorder.measureGeneration(observation.key, observation.prediction) {
            val result = block()
            MeasuredResult(
                value = result,
                completedUnits = if (result.isSuccess) completedUnits else 0,
                outcome = ObservationOutcome.SUCCESS,
            )
        }
    }

    fun supportsVideoGeneration(): Boolean = runner.supportsVideoGeneration()

    private fun generationMemoryError(width: Int, height: Int, frames: Int): Exception? {
        val outputBytes = estimateMediaOutputBytes(width, height, frames)
        val budgetBytes = currentMemoryBudgetBytes()
        val additionalBytes = requiredDiffusionAdditionalMemoryBytes(
            loadedWeightsBytes = loadedWeightsBytes,
            outputBytes = outputBytes,
        )
        if (loadedWeightsBytes > 0L && additionalBytes <= budgetBytes) {
            return null
        }
        AppLogger.w(TAG, "Generation rejected by the current memory budget")
        return DiffusionMemoryException()
    }

    private fun currentMemoryBudgetBytes(): Long {
        val budgetMb = deviceCapabilities.getDeviceHints().memoryBudgetMB
        if (budgetMb <= 0L) return 0L
        val bytesPerMb = 1024L * 1024L
        return if (budgetMb > Long.MAX_VALUE / bytesPerMb) Long.MAX_VALUE
        else budgetMb * bytesPerMb
    }

    /** Architecture string reported by the native layer for the currently loaded model. */
    fun getLastLoadedArchitecture(): String? = lastLoadedArchStr

    /** Returns recommended inference parameters for the given model, or null for unknown/simple models. */
    fun getRecommendedParams(model: LocalModelEntity): SdCppRecommendedParams? =
        getModelSetup(model.modelId)?.recommendedParams

}

internal fun exactDiffusionNativePreflight(
    plan: DiffusionRunPlan,
    config: DiffusionModelConfig,
    result: DiffusionPreflightResult,
): NativeLoadPreflight = when (result) {
    is DiffusionPreflightResult.Fit -> if (result.report.matches(plan, config)) {
        NativeLoadPreflight.Fit
    } else {
        NativeLoadPreflight.Invalid
    }
    is DiffusionPreflightResult.NoFit -> NativeLoadPreflight.NoFit
    is DiffusionPreflightResult.InvalidModel -> NativeLoadPreflight.Invalid
    is DiffusionPreflightResult.Unavailable -> NativeLoadPreflight.Unavailable
}

private fun DiffusionFitReport.matches(
    plan: DiffusionRunPlan,
    config: DiffusionModelConfig,
): Boolean {
    if (config.segmentedCompute != plan.segmentedCompute || config.prefetch != plan.prefetch ||
        config.autoFit || autoFit || segmentedCompute != plan.segmentedCompute ||
        prefetch != plan.prefetch ||
        components.isEmpty() || backends.isEmpty() ||
        !components.matchConfiguredComponentBindings(config)
    ) {
        return false
    }

    val runtimeKind = plan.backend.toDiffusionBackendKind() ?: return false
    val requiresCpu = plan.backend == BackendKind.CPU || plan.offloadToCpu ||
        plan.keepClipOnCpu || plan.keepVaeOnCpu
    val expectedKinds = buildSet {
        add(runtimeKind)
        if (requiresCpu) add(DiffusionBackendKind.CPU)
    }
    if (backends.size != expectedKinds.size || backends.map { it.kind }.toSet() != expectedKinds) {
        return false
    }
    val expectedNativeBudget = plan.maxVramBytes?.toNativeDiffusionBudget()
    if (config.maxVram != expectedNativeBudget?.gibText.orEmpty()) return false
    val expectedBudget = expectedNativeBudget?.bytes ?: 0L
    val runtimeBackend = backends.singleOrNull { it.kind == runtimeKind } ?: return false
    if (runtimeBackend.budgetBytes != expectedBudget ||
        backends.any { it.kind == DiffusionBackendKind.CPU && it.budgetBytes != 0L }
    ) {
        return false
    }
    if (backends.any { backend ->
            when (backend.kind) {
                DiffusionBackendKind.CPU -> backend.deviceType != DiffusionBackendDeviceType.CPU
                DiffusionBackendKind.CUDA,
                DiffusionBackendKind.METAL,
                DiffusionBackendKind.VULKAN,
                -> backend.deviceType != DiffusionBackendDeviceType.DISCRETE_GPU &&
                    backend.deviceType != DiffusionBackendDeviceType.INTEGRATED_GPU
                DiffusionBackendKind.OPENCL,
                DiffusionBackendKind.SYCL,
                DiffusionBackendKind.OTHER,
                -> true
            }
        }
    ) {
        return false
    }

    val expectedParameterPlacement = if (plan.offloadToCpu) {
        DiffusionParameterPlacement.CPU
    } else {
        DiffusionParameterPlacement.DEFAULT
    }
    return components.all { component ->
        component.parameterPlacement == expectedParameterPlacement &&
            component.matchesRuntimePlacement(plan, runtimeKind, backends)
    }
}

private data class ConfiguredDiffusionComponent(
    val role: DiffusionComponentRole,
    val path: String,
)

private fun DiffusionModelConfig.configuredComponentBindings(): List<ConfiguredDiffusionComponent> {
    val split = vaePath.isNotEmpty() || llmPath.isNotEmpty() || clipLPath.isNotEmpty() ||
        clipGPath.isNotEmpty() || t5xxlPath.isNotEmpty()
    return buildList {
        add(
            ConfiguredDiffusionComponent(
                role = if (split) {
                    DiffusionComponentRole.DIFFUSION_MODEL
                } else {
                    DiffusionComponentRole.MODEL_BUNDLE
                },
                path = modelPath,
            ),
        )
        if (vaePath.isNotEmpty()) add(ConfiguredDiffusionComponent(DiffusionComponentRole.VAE, vaePath))
        if (llmPath.isNotEmpty()) add(ConfiguredDiffusionComponent(DiffusionComponentRole.LLM, llmPath))
        if (clipLPath.isNotEmpty()) add(ConfiguredDiffusionComponent(DiffusionComponentRole.CLIP_L, clipLPath))
        if (clipGPath.isNotEmpty()) add(ConfiguredDiffusionComponent(DiffusionComponentRole.CLIP_G, clipGPath))
        if (t5xxlPath.isNotEmpty()) add(ConfiguredDiffusionComponent(DiffusionComponentRole.T5XXL, t5xxlPath))
        if (taesdPath.isNotEmpty()) add(ConfiguredDiffusionComponent(DiffusionComponentRole.TAESD, taesdPath))
    }
}

private fun List<DiffusionPreflightComponent>
    .matchConfiguredComponentBindings(config: DiffusionModelConfig): Boolean {
    val reportedOrdinals = map { it.ordinal }
    if (reportedOrdinals.toSet() != indices.toSet()) return false

    val configured = config.configuredComponentBindings()
    if (configured.isEmpty() || configured.any { it.path.isEmpty() }) return false
    val bySourceOrdinal = groupBy { it.sourceOrdinal }
    if (bySourceOrdinal.keys != configured.indices.toSet()) return false

    return configured.withIndex().all { (sourceOrdinal, expected) ->
        val evidence = bySourceOrdinal.getValue(sourceOrdinal)
        if (evidence.any { it.sourceRole != expected.role }) return@all false
        val subdivisions = evidence.map { it.subdivisionRole }
        when (expected.role) {
            DiffusionComponentRole.MODEL_BUNDLE -> {
                val allowed = setOf(
                    DiffusionComponentRole.DIFFUSION_MODEL,
                    DiffusionComponentRole.VAE,
                    DiffusionComponentRole.TEXT_ENCODER,
                    DiffusionComponentRole.OTHER,
                )
                subdivisions.toSet().size == subdivisions.size &&
                    DiffusionComponentRole.DIFFUSION_MODEL in subdivisions &&
                    subdivisions.all { it in allowed }
            }
            DiffusionComponentRole.TAESD -> evidence.size == 1 &&
                subdivisions.single() == DiffusionComponentRole.VAE
            else -> evidence.size == 1 && subdivisions.single() == expected.role
        }
    }
}

private fun com.debanshu777.diffusionrunner.DiffusionPreflightComponent.matchesRuntimePlacement(
    plan: DiffusionRunPlan,
    runtimeKind: DiffusionBackendKind,
    backends: List<com.debanshu777.diffusionrunner.DiffusionPreflightBackend>,
): Boolean {
    val cpuRuntime = when (subdivisionRole) {
        DiffusionComponentRole.LLM,
        DiffusionComponentRole.CLIP_L,
        DiffusionComponentRole.CLIP_G,
        DiffusionComponentRole.T5XXL,
        DiffusionComponentRole.TEXT_ENCODER,
        -> plan.backend == BackendKind.CPU || plan.keepClipOnCpu
        DiffusionComponentRole.VAE,
        DiffusionComponentRole.TAESD,
        -> plan.backend == BackendKind.CPU || plan.keepVaeOnCpu
        DiffusionComponentRole.MODEL_BUNDLE,
        DiffusionComponentRole.DIFFUSION_MODEL,
        DiffusionComponentRole.OTHER,
        -> plan.backend == BackendKind.CPU
    }
    if (cpuRuntime) {
        return runtimePlacement == DiffusionRuntimePlacement.CPU && runtimeBackendMask == 0L
    }
    if (runtimePlacement != DiffusionRuntimePlacement.GPU || runtimeBackendMask.countOneBits() != 1) {
        return false
    }
    val backendIndex = runtimeBackendMask.countTrailingZeroBits()
    return backends.getOrNull(backendIndex)?.kind == runtimeKind
}

private fun BackendKind.toDiffusionBackendKind(): DiffusionBackendKind? = when (this) {
    BackendKind.CPU -> DiffusionBackendKind.CPU
    BackendKind.METAL -> DiffusionBackendKind.METAL
    BackendKind.VULKAN -> DiffusionBackendKind.VULKAN
    BackendKind.CUDA -> DiffusionBackendKind.CUDA
    BackendKind.OTHER -> null
}
