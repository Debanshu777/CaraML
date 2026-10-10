package com.debanshu777.caraml.core.data.inference

import com.debanshu777.caraml.core.benchmark.BenchmarkUtils
import com.debanshu777.caraml.core.platform.AppLogger
import com.debanshu777.caraml.core.platform.BackendKind
import com.debanshu777.caraml.core.platform.DeviceCapabilities
import com.debanshu777.caraml.core.platform.PlatformPaths
import com.debanshu777.caraml.core.recommendation.classifyNativeMetadataPreflight
import com.debanshu777.caraml.core.recommendation.DeviceSnapshotProvider
import com.debanshu777.caraml.core.recommendation.InferenceObservationPhase
import com.debanshu777.caraml.core.recommendation.InferenceObservationPlan
import com.debanshu777.caraml.core.recommendation.InferenceObservationRecorder
import com.debanshu777.caraml.core.recommendation.MeasuredResult
import com.debanshu777.caraml.core.recommendation.ObservationOutcome
import com.debanshu777.caraml.core.recommendation.CoordinatedLoadResult
import com.debanshu777.caraml.core.recommendation.LoadAdmission
import com.debanshu777.caraml.core.recommendation.LoadAdmissionController
import com.debanshu777.caraml.core.recommendation.LoadRecoveryRepository
import com.debanshu777.caraml.core.recommendation.LoadRequest
import com.debanshu777.caraml.core.recommendation.LoadSessionCoordinator
import com.debanshu777.caraml.core.recommendation.LlmRunPlan
import com.debanshu777.caraml.core.recommendation.LocalArtifactIdentityResolver
import com.debanshu777.caraml.core.recommendation.NativeLoadPreflight
import com.debanshu777.caraml.core.recommendation.NativeLoadOutcome
import com.debanshu777.caraml.core.recommendation.NATIVE_LOAD_ENGINE_VERSION
import com.debanshu777.caraml.core.recommendation.NativeRunPlanAdapter
import com.debanshu777.caraml.core.recommendation.PersonalizedRecommendation
import com.debanshu777.caraml.core.recommendation.RecommendationCategory
import com.debanshu777.caraml.core.recommendation.RecommendationPolicy
import com.debanshu777.caraml.core.recommendation.StableLoadFailure
import com.debanshu777.caraml.core.recommendation.SuitabilityEngine
import com.debanshu777.caraml.core.recommendation.VerifiedArtifactLoadTarget
import com.debanshu777.caraml.core.recommendation.requiresCpuOnlyLlmExecution
import com.debanshu777.caraml.core.recommendation.toInferenceObservationPlan
import com.debanshu777.caraml.core.settings.AppSettings
import com.debanshu777.caraml.core.settings.KvQuantPreset
import com.debanshu777.caraml.core.data.settings.SettingsRepository
import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import com.debanshu777.runner.InferenceChunk
import com.debanshu777.runner.LlamaRunner
import com.debanshu777.runner.LlamaPreflightReason
import com.debanshu777.runner.LlamaPreflightResult
import com.debanshu777.runner.NativeRunnerConfig
import com.debanshu777.runner.PromptProcessingResult
import com.debanshu777.runner.generateFlowTokens
import com.debanshu777.runner.generateStructuredChunks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile
import kotlin.time.TimeSource

class LlamaInferenceRepository(
    private val runner: LlamaRunner,
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
) : InferenceRepository {

    companion object {
        private const val TAG = "Inference"
        const val CONTEXT_THRESHOLD = 0.85f
        private const val FALLBACK_SYSTEM_PROMPT = "You are a helpful assistant."
        private const val MAX_RESPONSE_TOKENS = 4096

        /**
         * Upper bound for auto-fit context when the user hasn't set a preference.
         * llama_params_fit maximises context to fill available memory, which on
         * devices with ample RAM and small models can produce 200K+ contexts that
         * waste memory on KV cache, prevent GPU offload, and hurt TPS.
         * 16384 is a reasonable default for mobile; users can override via model settings.
         */
        private const val AUTO_FIT_CONTEXT_CAP = 16384

    }

    /**
     * Model architecture family — drives batch size and flash-attention decisions.
     */
    private enum class ArchFamily { DENSE, MOE, HYBRID_SSM, UNKNOWN }

    private fun archFamily(arch: String?): ArchFamily = when {
        arch == null -> ArchFamily.UNKNOWN
        arch.requiresCpuOnlyLlmExecution() -> ArchFamily.HYBRID_SSM
        arch in listOf(
            "qwen2", "llama", "gemma", "mistral", "phi3",
            "qwen3", "phi2", "stablelm", "falcon", "smollm3"
        ) -> ArchFamily.DENSE
        arch in listOf(
            "qwen3moe", "deepseek2", "mixtral", "qwen2_moe"
        ) -> ArchFamily.MOE
        else -> ArchFamily.UNKNOWN
    }

    /** Serializes every operation that reads or mutates the native model session. */
    private val nativeSession = NativeSessionGate()

    /**
     * True once the native model is successfully loaded; false after unload.
     * Only written under [nativeSession].
     */
    @Volatile private var nativeLoaded = false

    /** Native statistics copied while [nativeSession] is held for safe synchronous UI reads. */
    @Volatile private var contextUsedSnapshot = 0
    @Volatile private var contextLimitSnapshot = 0
    @Volatile private var stopReasonSnapshot = 0

    /** Cached runtime config string built after each successful model load. */
    @Volatile private var lastRuntimeConfig: String = ""

    @Volatile private var generationObservation: InferenceObservationPlan? = null

    override suspend fun loadModel(request: LoadRequest): ModelLoadResult {
        val started = TimeSource.Monotonic.markNow()
        AppLogger.i(TAG) {
            "load stage=request fallback=${request.nativeMetadataFallback} " +
                "plan=${request.plan::class.simpleName} artifactPresent=${request.artifact != null}"
        }
        fun error(reason: String, message: String): ModelLoadResult.Error {
            AppLogger.i(TAG) { "load stage=rejected reason=$reason" }
            return ModelLoadResult.Error(message)
        }
        return nativeSession.exclusive {
            val artifact = request.artifact
                ?: return@exclusive error("unverified_artifact", "The installed model could not be verified.")
            if (artifact.identity != request.identity) {
                return@exclusive error("identity_mismatch", "The installed model identity is invalid.")
            }
            val plan = request.plan as? LlmRunPlan
                ?: return@exclusive error("invalid_config", "The selected model configuration is invalid.")
            val resolver = artifactIdentityResolver
                ?: return@exclusive error("admission_unavailable", "Load admission is unavailable.")
            val coordinator = loadSessionCoordinator
                ?: return@exclusive error("recovery_unavailable", "Load recovery is unavailable.")
            val modelPath = (artifact.loadTarget as? VerifiedArtifactLoadTarget.File)?.path
                ?: return@exclusive error("unverified_artifact", "The installed model could not be verified.")
            val nativeLibDir = PlatformPaths.getNativeLibDir()
            if (nativeLibDir.isBlank()) {
                return@exclusive error("initialization_failure", "Failed to initialize. Please restart the app.")
            }
            val settings = currentSettings()
            val architecture = request.observationIdentity.architectureFamily
            val base = buildRunnerConfig(request.model, architecture, settings.temperature, settings)
            val exactConfig = runCatching {
                exactLlamaRunnerConfig(architecture, plan, base)
            }
                .getOrElse { return@exclusive error("invalid_config", "The selected model configuration is invalid.") }
                ?: return@exclusive error("invalid_config", "The selected model configuration is invalid.")
            AppLogger.i(TAG) {
                "load stage=config ctx=${exactConfig.nCtx} batch=${exactConfig.nBatch} " +
                    "ubatch=${exactConfig.nUbatch} threads=${exactConfig.nThreads} " +
                    "gpuLayers=${exactConfig.nGpuLayers} kv=${exactConfig.typeK}/${exactConfig.typeV} " +
                    "flash=${exactConfig.flashAttn} autoFit=${exactConfig.autoFit}"
            }
            val loadObservation = request.toInferenceObservationPlan(engineVersion, InferenceObservationPhase.LOAD)
            val nextGenerationObservation =
                request.toInferenceObservationPlan(engineVersion, InferenceObservationPhase.GENERATION)
            if (nativeLoaded) {
                try {
                    runner.unloadModel()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    return@exclusive error("release_failure", "The previous model could not be released safely.")
                }
                nativeLoaded = false
                resetNativeSnapshots()
            }
            AppLogger.i(TAG) { "load stage=initialize" }
            try {
                runner.initialize(nativeLibDir)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                return@exclusive error("initialization_failure", "Failed to initialize. Please restart the app.")
            }
            val controller = admissionController { candidate ->
                val candidatePlan = candidate.plan as? LlmRunPlan
                    ?: return@admissionController NativeLoadPreflight.Invalid
                val candidatePath = (candidate.artifact?.loadTarget as? VerifiedArtifactLoadTarget.File)?.path
                    ?: return@admissionController NativeLoadPreflight.Invalid
                val config = runCatching {
                    exactLlamaRunnerConfig(candidate.observationIdentity.architectureFamily, candidatePlan, base)
                }
                    .getOrElse { return@admissionController NativeLoadPreflight.Invalid }
                    ?: return@admissionController NativeLoadPreflight.Invalid
                val preflightStarted = TimeSource.Monotonic.markNow()
                val preflight = runner.preflightModel(candidatePath, config)
                AppLogger.i(TAG) {
                    "load stage=preflight outcome=${preflight::class.simpleName} " +
                        "elapsedMs=${preflightStarted.elapsedNow().inWholeMilliseconds}"
                }
                if (preflight is LlamaPreflightResult.Fit) {
                    AppLogger.i(TAG) {
                        "preflight ctx=${preflight.report.fittedContextTokens} " +
                            "gpuLayers=${preflight.report.fittedGpuLayers} pools=${preflight.report.memoryPools.size}"
                    }
                    preflight.report.memoryPools.forEach { pool ->
                        AppLogger.i(TAG) {
                            "preflight pool=${pool.kind} ordinal=${pool.ordinal} " +
                                "modelBytes=${pool.modelBytes} contextBytes=${pool.contextBytes} " +
                                "computeBytes=${pool.computeBytes} freeBytes=${pool.freeBytes}"
                        }
                    }
                }
                when (preflight) {
                    is LlamaPreflightResult.Fit -> if (candidate.nativeMetadataFallback) {
                        val snapshot = snapshotProvider?.capture()
                            ?: return@admissionController NativeLoadPreflight.Unavailable
                        classifyNativeMetadataPreflight(preflight.report, candidatePlan, snapshot)
                    } else {
                        NativeLoadPreflight.Fit
                    }
                    is LlamaPreflightResult.NoFit -> NativeLoadPreflight.NoFit
                    is LlamaPreflightResult.InvalidModel -> {
                        AppLogger.i(TAG) { "preflight: invalid (${preflight.reason})" }
                        if (preflight.reason == LlamaPreflightReason.REQUIRES_TARGET_MODEL) {
                            NativeLoadPreflight.RequiresTargetModel
                        } else {
                            NativeLoadPreflight.Invalid
                        }
                    }
                    is LlamaPreflightResult.Unavailable -> {
                        AppLogger.i(TAG) { "preflight: unavailable (${preflight.reason})" }
                        NativeLoadPreflight.Unavailable
                    }
                }
            } ?: return@exclusive error("admission_unavailable", "Load admission is unavailable.")
            try {
                when (val result = coordinator.execute(
                    request = request,
                    evaluateAdmission = { controller.evaluate(request, request.riskAcknowledgement) },
                    artifactValidator = { candidate ->
                        candidate.artifact?.let { resolver.revalidate(it) } == true
                    },
                    releasePartialState = {
                        runner.unloadModel()
                        nativeLoaded = false
                        resetNativeSnapshots()
                    },
                    nativeLoad = {
                        AppLogger.i(TAG) { "load stage=native_allocation" }
                        val loaded = loadObservation?.let { observation ->
                            observationRecorder?.measureLoad(observation.key, observation.prediction) {
                                val succeeded = runner.loadModel(modelPath, exactConfig)
                                MeasuredResult(
                                    value = succeeded,
                                    completedUnits = 1,
                                    outcome = if (succeeded) {
                                        ObservationOutcome.SUCCESS
                                    } else {
                                        ObservationOutcome.ALLOCATION_FAILURE
                                    },
                                )
                            }
                        } ?: runner.loadModel(modelPath, exactConfig)
                        if (!loaded) {
                            return@execute NativeLoadOutcome.Failed(
                                error("allocation_failure", "The model could not be loaded with this configuration."),
                                StableLoadFailure.ALLOCATION,
                            )
                        }
                        nativeLoaded = true
                        val systemPrompt = settings.systemPrompt.ifBlank { FALLBACK_SYSTEM_PROMPT }
                        val systemResult = runner.processSystemPrompt(systemPrompt)
                        AppLogger.i(TAG) { "load stage=system_prompt code=$systemResult" }
                        if (systemResult != 0) {
                            return@execute NativeLoadOutcome.Failed(
                                error("conversation_initialization_failure", "The model could not initialize a conversation."),
                                StableLoadFailure.UNSUPPORTED_CONFIGURATION,
                            )
                        }
                        updateNativeSnapshots()
                        generationObservation = nextGenerationObservation
                        val contextSize = runner.getContextLimit()
                        lastRuntimeConfig = BenchmarkUtils.formatRuntimeConfig(
                            threads = exactConfig.nThreads,
                            batchThreads = exactConfig.nThreadsBatch,
                            batchSize = exactConfig.nBatch,
                            contextLimit = contextSize,
                            gpuLayers = runner.getGpuLayers(),
                            typeK = exactConfig.typeK,
                            typeV = exactConfig.typeV,
                            flashAttn = exactConfig.flashAttn,
                        )
                        NativeLoadOutcome.Succeeded(ModelLoadResult.Success(contextSize))
                    },
                )) {
                    is CoordinatedLoadResult.AdmissionRequired -> {
                        val reason = when (val admission = result.admission) {
                            is LoadAdmission.Ready -> "ready"
                            is LoadAdmission.Blocked -> admission.reason.name
                            is LoadAdmission.TemporarilyUnavailable -> admission.reason.name
                            is LoadAdmission.ConfirmationRequired -> admission.reason.name
                            is LoadAdmission.AlternativeAvailable -> admission.reason.name
                            is LoadAdmission.SafeAlternativeAvailable -> admission.reason.name
                        }
                        AppLogger.i(TAG) { "load stage=admission_required reason=$reason" }
                        ModelLoadResult.AdmissionRequired(result.admission)
                    }
                    is CoordinatedLoadResult.ArtifactChanged ->
                        error("artifact_changed", "The installed model changed and could not be verified.")
                    is CoordinatedLoadResult.Completed -> result.value
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                AppLogger.i(TAG) { "load stage=exception kind=${failure::class.simpleName}" }
                if (nativeLoaded) {
                    runCatching { runner.unloadModel() }
                    nativeLoaded = false
                    resetNativeSnapshots()
                }
                error("admission_failure", "Load admission is temporarily unavailable.")
            }
        }.also { result ->
            AppLogger.i(TAG) {
                "load stage=complete outcome=${result::class.simpleName} " +
                    "elapsedMs=${started.elapsedNow().inWholeMilliseconds} " +
                    "context=$contextUsedSnapshot/$contextLimitSnapshot"
            }
        }
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

    override suspend fun allowExplicitRetry(request: LoadRequest) {
        loadSessionCoordinator?.allowExplicitRetry(request, engineVersion)
    }

    private fun buildRunnerConfig(
        model: LocalModelEntity,
        architecture: String?,
        temperature: Float,
        settings: AppSettings,
    ): NativeRunnerConfig {
        val hints = deviceCapabilities.getDeviceHints()
        AppLogger.i(TAG) {
            "device: cores=${hints.performanceCoreCount}/${hints.totalCoreCount}, " +
            "memMB=${hints.memoryBudgetMB}, gpu=${hints.gpuBackendAvailable}"
        }
        val gpuActive = hints.gpuBackendAvailable
        val archDenied = architecture.requiresCpuOnlyLlmExecution()
        val gpuEnabled = settings.useGpu && gpuActive && !archDenied
        if (archDenied) {
            AppLogger.i(TAG) { "buildRunnerConfig: GPU disabled for this model (archDenied=true)" }
        }
        val userRequestedCtx = (model.contextLength ?: 0).let { raw ->
            if (raw <= 0) AUTO_FIT_CONTEXT_CAP else raw.coerceAtMost(AUTO_FIT_CONTEXT_CAP)
        }
        val modelSizeMB = getModelFileSizeMB(model)
        // mlock always fails on Android (RLIMIT_MEMLOCK ~64KB) and is pointless when
        // weights are GPU-offloaded. Only meaningful for CPU-resident small models.
        val useMlock = !gpuEnabled && modelSizeMB <= 4096 && hints.memoryBudgetMB >= 6000

        // After Phase 03: gen and batch are pinned to perfCores, so cap batch to perfCores.
        // Drop isLargeModel branch — native safety net handles thread adjustment.
        val pinned = hints.perfCoreMask.isNotEmpty()
        val nThreads = hints.performanceCoreCount.coerceAtLeast(2)
        val nThreadsBatch = if (pinned) {
            hints.performanceCoreCount.coerceAtLeast(2)
        } else {
            (hints.totalCoreCount - 1).coerceAtLeast(hints.performanceCoreCount)
        }

        // Phase 08: per-architecture adaptive batch size and flash attention.
        val archFam = archFamily(architecture)
        AppLogger.i(TAG) { "buildRunnerConfig: family=$archFam" }
        val batchSize = when (archFam) {
            ArchFamily.DENSE -> if (hints.memoryBudgetMB >= 4096) 512 else 256
            ArchFamily.MOE -> 256
            ArchFamily.HYBRID_SSM, ArchFamily.UNKNOWN -> when {
                modelSizeMB > 8192 -> 128
                modelSizeMB > 4096 -> 256
                else -> if (hints.memoryBudgetMB >= 4096) 512 else 256
            }
        }
        // Flash attention: always auto (-1). For hybrid SSM, llama.cpp skips flash_attn
        // on the recurrent/GDN layers internally regardless of this flag. The 6 attention
        // layers work correctly with auto. Explicitly setting 0 (off) breaks the fused GDN
        // chunked Vulkan shader path on Mali-G715, causing ggml_abort during processSystemPrompt.
        val flashAttn = -1

        // ggml_type int values: F16=1, Q8_0=8, Q4_0=2
        val (typeK, typeV) = when (settings.kvQuantPreset) {
            KvQuantPreset.Q4_F16  -> 2 to 1
            KvQuantPreset.Q8_Q8   -> 8 to 8
            KvQuantPreset.F16_F16 -> 1 to 1
            KvQuantPreset.AUTO    -> when {
                hints.memoryBudgetMB < 3000 -> 2 to 1   // Q4_0 K + F16 V
                hints.memoryBudgetMB < 6000 -> 8 to 8   // Q8_0 K + Q8_0 V
                else                        -> 1 to 1   // F16 K + F16 V
            }
        }

        // Phase 10: when all layers are on GPU, n_ubatch = n_batch reduces kernel dispatch
        // overhead during prefill without increasing peak VRAM (embedding and output remain CPU).
        // HYBRID_SSM excluded: GDN/SSM recurrent state kernels on Vulkan have stricter ubatch
        // constraints; increasing n_ubatch past the original half-batch causes ggml_abort inside
        // the Vulkan backend (assertion failure in the recurrent state compute graph).
        val nUbatch = if (gpuEnabled && archFam != ArchFamily.HYBRID_SSM) batchSize else batchSize / 2

        return NativeRunnerConfig(
            nCtx           = userRequestedCtx,
            nCtxMin        = 512,
            nThreads       = nThreads,
            nThreadsBatch  = nThreadsBatch,
            nBatch         = batchSize,
            nUbatch        = nUbatch,
            flashAttn      = flashAttn,
            offloadKqv     = gpuEnabled,
            typeK          = typeK,
            typeV          = typeV,
            nGpuLayers     = if (gpuEnabled) -1 else 0,
            useMmap        = true,
            useMlock       = useMlock,
            temperature    = temperature,
            autoFit        = true,
            cpuMask        = hints.perfCoreMask,
            cpuMaskBatch   = hints.perfCoreMask,
        )

    }

    private fun getModelFileSizeMB(model: LocalModelEntity): Long {
        return (model.sizeBytes ?: 0L) / (1024 * 1024)
    }

    override fun generateResponse(userPrompt: String): Flow<InferenceChunk> = flow {
        if (!isPromptLengthSupported(userPrompt)) throw PromptContextFullException()
        nativeSession.exclusive {
            // Native admission applies the full template and clamps this upper bound
            // to the actual free context. Reasoning and answer share this allowance.
            val responseBudget = MAX_RESPONSE_TOKENS
            AppLogger.i(TAG) {
                "generate: promptLen=${userPrompt.length}, " +
                    "context=${runner.getContextUsed()}/${runner.getContextLimit()}"
            }
            val generationStarted = TimeSource.Monotonic.markNow()
            var tokenEvents = 0
            var firstTokenMs: Long? = null
            var outcome = "failed"
            try {
                val prefillStarted = TimeSource.Monotonic.markNow()
                val promptResult = runner.processUserPrompt(userPrompt, responseBudget)
                AppLogger.i(TAG) {
                    "generate stage=prefill code=$promptResult " +
                        "elapsedMs=${prefillStarted.elapsedNow().inWholeMilliseconds} budget=$responseBudget"
                }
                when (PromptProcessingResult.fromNativeCode(
                    promptResult
                )) {
                    PromptProcessingResult.Success -> Unit
                    PromptProcessingResult.ContextFull -> throw PromptContextFullException()
                    PromptProcessingResult.Failure ->
                        throw IllegalStateException("Failed to process message")
                }
                runner.generateStructuredChunks().collect { chunk ->
                    if (chunk.isTokenEvent) {
                        tokenEvents++
                        if (firstTokenMs == null) {
                            firstTokenMs = generationStarted.elapsedNow().inWholeMilliseconds
                            AppLogger.i(TAG) { "generate stage=first_token elapsedMs=$firstTokenMs" }
                        }
                        contextUsedSnapshot = runner.getContextUsed()
                    }
                    emit(chunk)
                }
                outcome = when (runner.getStopReason()) {
                    4 -> "cancelled"
                    5 -> "native_error"
                    else -> "completed"
                }
            } catch (cancelled: CancellationException) {
                outcome = "cancelled"
                throw cancelled
            } catch (failure: Throwable) {
                AppLogger.i(TAG) { "generate stage=exception kind=${failure::class.simpleName}" }
                throw failure
            } finally {
                try {
                    runner.finalizeGeneration()
                    updateNativeSnapshots()
                } finally {
                    AppLogger.i(TAG) {
                        "generate stage=complete outcome=$outcome tokens=$tokenEvents " +
                            "elapsedMs=${generationStarted.elapsedNow().inWholeMilliseconds} " +
                            "firstTokenMs=${firstTokenMs ?: -1} stop=$stopReasonSnapshot " +
                            "context=$contextUsedSnapshot/$contextLimitSnapshot"
                    }
                }
            }
        }
    }

    override fun currentGenerationObservation(): InferenceObservationPlan? = generationObservation

    override suspend fun unloadModel() = nativeSession.exclusive {
        if (!nativeLoaded) return@exclusive
        runner.unloadModel()
        nativeLoaded = false
        resetNativeSnapshots()
    }

    override fun cancelGeneration() {
        AppLogger.i(TAG) { "cancelled" }
        runner.cancelGenerate()
    }

    override fun getContextUsed(): Int {
        return contextUsedSnapshot
    }

    override fun getContextLimit(): Int {
        return contextLimitSnapshot
    }

    override fun getStopReason(): Int {
        return stopReasonSnapshot
    }

    override fun getRuntimeConfigString(): String = lastRuntimeConfig

    override fun isContextAboveThreshold(): Boolean {
        val limit = getContextLimit()
        if (limit == 0) return false
        val used = getContextUsed()
        return used.toFloat() / limit >= CONTEXT_THRESHOLD
    }

    override fun summarizeConversation(transcript: String): Flow<String> = flow {
        nativeSession.exclusive {
            val systemPrompt = currentSystemPrompt()
            try {
                runner.clearContext()

                val spRet = runner.processSystemPrompt(
                    "$systemPrompt Your task is to summarize the conversation below."
                )
                if (spRet != 0) {
                    throw IllegalStateException("Failed to process summarization system prompt")
                }

                val contextLimit = runner.getContextLimit()
                val maxTranscriptChars = (contextLimit * 0.6 * 3).toInt()
                val truncatedTranscript = if (transcript.length > maxTranscriptChars) {
                    transcript.takeLast(maxTranscriptChars)
                } else {
                    transcript
                }

                val promptText = """
                    |Conversation:
                    |$truncatedTranscript
                    |
                    |Summary:
                """.trimMargin()

                val ret = runner.processUserPrompt(promptText, 256)
                if (ret != 0) {
                    throw IllegalStateException("Failed to process summarization prompt")
                }

                runner.generateFlowTokens().collect { token ->
                    emit(token)
                }
            } finally {
                runner.finalizeGeneration()
                updateNativeSnapshots()
            }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun resetContext() = withContext(Dispatchers.IO) {
        nativeSession.exclusive {
            runner.clearContext()
            updateNativeSnapshots()
        }
    }

    override suspend fun resetContextWithSummary(summary: String, lastExchange: String): Boolean =
        withContext(Dispatchers.IO) {
            nativeSession.exclusive {
                val basePrompt = currentSystemPrompt()
                try {
                    runner.clearContext()

                    val systemPrompt = buildString {
                        append(basePrompt)

                        if (summary.isNotBlank()) {
                            append(" Here is a summary of our previous conversation:\n")
                            append(summary)
                        }

                        if (lastExchange.isNotBlank()) {
                            if (summary.isNotBlank()) {
                                append("\n\n")
                            }
                            append("The most recent exchange was:\n")
                            append(lastExchange)
                        }
                    }

                    val ret = runner.processSystemPrompt(systemPrompt)
                    updateNativeSnapshots()
                    ret == 0
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    false
                }
            }
        }

    private fun updateNativeSnapshots() {
        contextUsedSnapshot = runner.getContextUsed()
        contextLimitSnapshot = runner.getContextLimit()
        stopReasonSnapshot = runner.getStopReason()
    }

    private fun resetNativeSnapshots() {
        contextUsedSnapshot = 0
        contextLimitSnapshot = 0
        stopReasonSnapshot = 0
        generationObservation = null
    }

    private suspend fun currentSettings(): AppSettings =
        settingsRepository.getSettings().first()

    private suspend fun currentSystemPrompt(): String =
        currentSettings().systemPrompt.ifBlank { FALLBACK_SYSTEM_PROMPT }
}

internal fun exactLlamaRunnerConfig(
    architecture: String?,
    plan: LlmRunPlan,
    base: NativeRunnerConfig,
): NativeRunnerConfig? {
    if (architecture.requiresCpuOnlyLlmExecution() && plan.backend != BackendKind.CPU) {
        return null
    }
    return NativeRunPlanAdapter.toLlamaConfig(plan, base)
}
