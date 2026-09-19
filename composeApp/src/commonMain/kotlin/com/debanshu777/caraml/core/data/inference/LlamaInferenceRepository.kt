package com.debanshu777.caraml.core.data.inference

import com.debanshu777.caraml.core.benchmark.BenchmarkUtils
import com.debanshu777.caraml.core.platform.AppLogger
import com.debanshu777.caraml.core.platform.DeviceCapabilities
import com.debanshu777.caraml.core.platform.PlatformPaths
import com.debanshu777.caraml.core.recommendation.DeviceSnapshotProvider
import com.debanshu777.caraml.core.recommendation.InferenceObservationPhase
import com.debanshu777.caraml.core.recommendation.InferenceObservationPlan
import com.debanshu777.caraml.core.recommendation.InferenceObservationRecorder
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
import com.debanshu777.caraml.core.recommendation.NativeRunPlanAdapter
import com.debanshu777.caraml.core.recommendation.PersonalizedRecommendation
import com.debanshu777.caraml.core.recommendation.RecommendationCategory
import com.debanshu777.caraml.core.recommendation.RecommendationPolicy
import com.debanshu777.caraml.core.recommendation.StableLoadFailure
import com.debanshu777.caraml.core.recommendation.SuitabilityEngine
import com.debanshu777.caraml.core.recommendation.toInferenceObservationPlan
import com.debanshu777.caraml.core.recommendation.VerifiedArtifactLoadTarget
import com.debanshu777.caraml.core.settings.AppSettings
import com.debanshu777.caraml.core.settings.KvQuantPreset
import com.debanshu777.caraml.core.data.settings.SettingsRepository
import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import com.debanshu777.runner.InferenceChunk
import com.debanshu777.runner.LlamaRunner
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
    private val engineVersion: String = "native-engine-v1",
    private val observationRecorder: InferenceObservationRecorder? = null,
) : InferenceRepository {

    companion object {
        private const val TAG = "Inference"
        const val CONTEXT_THRESHOLD = 0.85f
        private const val FALLBACK_SYSTEM_PROMPT = "You are a helpful assistant."
        private const val MAX_RESPONSE_TOKENS = 1024

        /**
         * Upper bound for auto-fit context when the user hasn't set a preference.
         * llama_params_fit maximises context to fill available memory, which on
         * devices with ample RAM and small models can produce 200K+ contexts that
         * waste memory on KV cache, prevent GPU offload, and hurt TPS.
         * 16384 is a reasonable default for mobile; users can override via model settings.
         */
        private const val AUTO_FIT_CONTEXT_CAP = 16384

        /** When true, skip GPU attempt on first load for hybrid-SSM archs (they always fail on Vulkan).
         *  Task 1 self-learns after runtime failure regardless; disable if ggml-vulkan adds qwen35 support. */
        private const val DENYLIST_HYBRID_SSM_VULKAN = true
    }

    /**
     * Model architecture family — drives batch size and flash-attention decisions.
     */
    private enum class ArchFamily { DENSE, MOE, HYBRID_SSM, UNKNOWN }

    private fun archFamily(arch: String?): ArchFamily = when {
        arch == null -> ArchFamily.UNKNOWN
        arch in listOf(
            "qwen2", "llama", "gemma", "mistral", "phi3",
            "qwen3", "phi2", "stablelm", "falcon", "smollm3"
        ) -> ArchFamily.DENSE
        arch in listOf(
            "qwen3moe", "deepseek2", "mixtral", "qwen2_moe"
        ) -> ArchFamily.MOE
        arch in listOf(
            "qwen3next", "qwen35", "jamba", "mamba", "ssm",
            "recurrent_gemma", "granite_hybrid"
        ) -> ArchFamily.HYBRID_SSM
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

    override suspend fun loadModel(request: LoadRequest): ModelLoadResult =
        nativeSession.exclusive {
            val artifact = request.artifact
                ?: return@exclusive ModelLoadResult.Error("The installed model could not be verified.")
            if (artifact.identity != request.identity) {
                return@exclusive ModelLoadResult.Error("The installed model identity is invalid.")
            }
            val plan = request.plan as? com.debanshu777.caraml.core.recommendation.LlmRunPlan
                ?: return@exclusive ModelLoadResult.Error("The selected model configuration is invalid.")
            val resolver = artifactIdentityResolver
                ?: return@exclusive ModelLoadResult.Error("Load admission is unavailable.")
            val coordinator = loadSessionCoordinator
                ?: return@exclusive ModelLoadResult.Error("Load recovery is unavailable.")
            val modelPath = (artifact.loadTarget as? VerifiedArtifactLoadTarget.File)?.path
                ?: return@exclusive ModelLoadResult.Error("The installed model could not be verified.")
            val nativeLibDir = PlatformPaths.getNativeLibDir()
            if (nativeLibDir.isBlank()) {
                return@exclusive ModelLoadResult.Error("Failed to initialize. Please restart the app.")
            }
            val settings = currentSettings()
            val base = buildRunnerConfig(request.model, settings.temperature, settings)
            val exactConfig = runCatching { NativeRunPlanAdapter.toLlamaConfig(plan, base) }
                .getOrElse { return@exclusive ModelLoadResult.Error("The selected model configuration is invalid.") }
            val loadObservation = request.toInferenceObservationPlan(engineVersion, InferenceObservationPhase.LOAD)
            val nextGenerationObservation =
                request.toInferenceObservationPlan(engineVersion, InferenceObservationPhase.GENERATION)
            if (nativeLoaded) {
                try {
                    runner.unloadModel()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    return@exclusive ModelLoadResult.Error("The previous model could not be released safely.")
                }
                nativeLoaded = false
                resetNativeSnapshots()
            }
            try {
                runner.initialize(nativeLibDir)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                return@exclusive ModelLoadResult.Error("Failed to initialize. Please restart the app.")
            }
            val controller = admissionController { candidate ->
                val candidatePlan = candidate.plan as? com.debanshu777.caraml.core.recommendation.LlmRunPlan
                    ?: return@admissionController NativeLoadPreflight.Invalid
                val candidatePath = (candidate.artifact?.loadTarget as? VerifiedArtifactLoadTarget.File)?.path
                    ?: return@admissionController NativeLoadPreflight.Invalid
                val config = runCatching { NativeRunPlanAdapter.toLlamaConfig(candidatePlan, base) }
                    .getOrElse { return@admissionController NativeLoadPreflight.Invalid }
                when (runner.preflightModel(candidatePath, config)) {
                    is LlamaPreflightResult.Fit -> NativeLoadPreflight.Fit
                    is LlamaPreflightResult.NoFit -> NativeLoadPreflight.NoFit
                    is LlamaPreflightResult.InvalidModel -> NativeLoadPreflight.Invalid
                    is LlamaPreflightResult.Unavailable -> NativeLoadPreflight.Unavailable
                }
            } ?: return@exclusive ModelLoadResult.Error("Load admission is unavailable.")
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
                                ModelLoadResult.Error("The model could not be loaded with this configuration."),
                                StableLoadFailure.ALLOCATION,
                            )
                        }
                        nativeLoaded = true
                        val systemPrompt = settings.systemPrompt.ifBlank { FALLBACK_SYSTEM_PROMPT }
                        if (runner.processSystemPrompt(systemPrompt) != 0) {
                            return@execute NativeLoadOutcome.Failed(
                                ModelLoadResult.Error("The model could not initialize a conversation."),
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
                    is CoordinatedLoadResult.AdmissionRequired ->
                        ModelLoadResult.AdmissionRequired(result.admission)
                    is CoordinatedLoadResult.ArtifactChanged ->
                        ModelLoadResult.Error("The installed model changed and could not be verified.")
                    is CoordinatedLoadResult.Completed -> result.value
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                if (nativeLoaded) {
                    runCatching { runner.unloadModel() }
                    nativeLoaded = false
                    resetNativeSnapshots()
                }
                ModelLoadResult.Error("Load admission is temporarily unavailable.")
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
        temperature: Float,
        settings: AppSettings,
    ): NativeRunnerConfig {
        val hints = deviceCapabilities.getDeviceHints()
        AppLogger.i(TAG) {
            "device: cores=${hints.performanceCoreCount}/${hints.totalCoreCount}, " +
            "memMB=${hints.memoryBudgetMB}, gpu=${hints.gpuBackendAvailable}"
        }
        val gpuActive = hints.gpuBackendAvailable
        val archDenied = DENYLIST_HYBRID_SSM_VULKAN && archFamily(model.arch) == ArchFamily.HYBRID_SSM
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
        val archFam = archFamily(model.arch)
        AppLogger.i(TAG) { "buildRunnerConfig: arch='${model.arch}', family=$archFam" }
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
            val contextLimit = runner.getContextLimit()
            val remainingCtx = (contextLimit - runner.getContextUsed()).coerceAtLeast(1)
            val responseBudget = minOf(
                MAX_RESPONSE_TOKENS,
                (contextLimit / 4).coerceAtLeast(1),
                remainingCtx,
            )
            AppLogger.i(TAG) {
                "generate: promptLen=${userPrompt.length}, remainingCtx=$remainingCtx, " +
                    "context=${runner.getContextUsed()}/${runner.getContextLimit()}"
            }
            try {
                when (PromptProcessingResult.fromNativeCode(
                    runner.processUserPrompt(userPrompt, responseBudget)
                )) {
                    PromptProcessingResult.Success -> Unit
                    PromptProcessingResult.ContextFull -> throw PromptContextFullException()
                    PromptProcessingResult.Failure ->
                        throw IllegalStateException("Failed to process message")
                }
                runner.generateStructuredChunks().collect { chunk ->
                    if (chunk.isTokenEvent) {
                        contextUsedSnapshot = runner.getContextUsed()
                    }
                    emit(chunk)
                }
            } finally {
                runner.finalizeGeneration()
                updateNativeSnapshots()
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
