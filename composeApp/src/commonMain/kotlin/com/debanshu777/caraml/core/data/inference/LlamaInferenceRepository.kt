package com.debanshu777.caraml.core.data.inference

import com.debanshu777.caraml.core.benchmark.BenchmarkUtils
import com.debanshu777.caraml.core.platform.AppLogger
import com.debanshu777.caraml.core.platform.DeviceCapabilities
import com.debanshu777.caraml.core.platform.PlatformPaths
import com.debanshu777.caraml.core.settings.AppSettings
import com.debanshu777.caraml.core.settings.KvQuantPreset
import com.debanshu777.caraml.core.data.settings.SettingsRepository
import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import com.debanshu777.caraml.core.storage.localmodel.LocalModelRepository
import com.debanshu777.huggingfacemanager.download.StoragePathProvider
import com.debanshu777.runner.LlamaRunner
import com.debanshu777.runner.NativeRunnerConfig
import com.debanshu777.runner.STRICT_THINKING_OUTPUT_GRAMMAR
import com.debanshu777.runner.generateFlowTokens
import com.debanshu777.runner.structuredOutputSystemPromptSuffix
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile

class LlamaInferenceRepository(
    private val storagePathProvider: StoragePathProvider,
    private val runner: LlamaRunner,
    private val deviceCapabilities: DeviceCapabilities,
    private val settingsRepository: SettingsRepository,
    private val localModelRepository: LocalModelRepository? = null,
) : InferenceRepository {

    companion object {
        private const val TAG = "Inference"
        const val CONTEXT_THRESHOLD = 0.85f
        private const val FALLBACK_SYSTEM_PROMPT = "You are a helpful assistant."

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
        arch in listOf(
            "qwen2", "llama", "gemma", "mistral", "phi3",
            "qwen3", "phi2", "stablelm", "falcon"
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

    /**
     * Caches the result of llama_params_fit() so repeated loads of the same model
     * skip the ~1.2s probe. Key: "modelPath:memTierGB:gpuEnabled".
     * Cleared when the entry is used and the subsequent load fails (mem conditions changed).
     */
    private data class ParamsFitResult(val nGpuLayers: Int, val nCtx: Int)
    private val paramsFitCache = mutableMapOf<String, ParamsFitResult>()

    private fun paramsFitCacheKey(modelPath: String, memBudgetMB: Long, gpuEnabled: Boolean): String {
        val memTierGB = memBudgetMB / 1024  // round down to GB — tolerates minor fluctuations
        return "$modelPath:$memTierGB:$gpuEnabled"
    }

    /**
     * Serializes all native load/unload operations so they never run concurrently.
     * Without this, a cancelled load job that is still inside JNI can race with
     * the next load job's unload call → double-free in llama_sampler_free.
     */
    private val nativeLock = Mutex()

    /**
     * True once the native model is successfully loaded; false after unload.
     * Only written under [nativeLock].
     */
    @Volatile private var nativeLoaded = false

    /** Cached runtime config string built after each successful model load. */
    @Volatile private var lastRuntimeConfig: String = ""

    override suspend fun loadModel(model: LocalModelEntity): ModelLoadResult =
        nativeLock.withLock {
            try {
                val sizeMB = getModelFileSizeMB(model)
                AppLogger.i(TAG) { "loadModel: modelId=${model.modelId}, sizeMB=$sizeMB" }

                val modelPath = resolveModelPath(model)
                if (modelPath.isBlank()) {
                    return@withLock ModelLoadResult.Error("Model path is invalid")
                }
                if (!storagePathProvider.isModelFileReadable(modelPath)) {
                    return@withLock ModelLoadResult.Error(
                        "Model file not found or not readable. It may have been moved or deleted."
                    )
                }

                val nativeLibDir = PlatformPaths.getNativeLibDir()
                if (nativeLibDir.isBlank()) {
                    return@withLock ModelLoadResult.Error(
                        "Failed to initialize. Please restart the app."
                    )
                }

                // Unload inline — we already hold the lock, so no double-free risk.
                if (nativeLoaded) {
                    runner.unloadModel()
                    nativeLoaded = false
                }

                runner.initialize(nativeLibDir)

                val settings = currentSettings()

                val config = buildRunnerConfig(
                    model = model,
                    temperature = settings.temperature,
                    settings = settings,
                    modelPath = modelPath,
                )
                AppLogger.i(TAG) {
                    "config: threads=${config.nThreads}/${config.nThreadsBatch}, " +
                    "batch=${config.nBatch}, ctx=${config.nCtx}, " +
                    "gpuLayers=${config.nGpuLayers}, kv=${config.typeK}/${config.typeV}"
                }

                var loaded = runner.loadModel(
                    modelPath = modelPath,
                    config = config,
                )

                // If GPU-accelerated load fails (e.g. Vulkan device lacks required
                // features — throws std::vector/length_error inside ggml_vk_init),
                // retry with CPU-only.  This keeps the app functional on devices
                // that claim Vulkan support but can't satisfy ggml-vulkan's
                // feature requirements (shaderIntegerDotProduct etc.).
                val cpuFallbackConfig = if (!loaded && config.nGpuLayers != 0) {
                    AppLogger.w(TAG, "loadModel: GPU load failed — retrying CPU-only")
                    val fallback = config.copy(
                        nGpuLayers   = 0,
                        offloadKqv   = false,
                        nThreads     = config.nThreads.coerceAtLeast(config.nThreadsBatch),
                        nThreadsBatch = config.nThreadsBatch,
                    )
                    loaded = runner.loadModel(modelPath = modelPath, config = fallback)
                    if (loaded) fallback else null
                } else null

                val effectiveConfig = cpuFallbackConfig ?: config

                if (!loaded) {
                    return@withLock ModelLoadResult.Error(
                        "Failed to load model. The file may be corrupted or unsupported."
                    )
                }
                nativeLoaded = true

                // Backfill arch in DB from native metadata if not yet detected.
                if (model.arch.isNullOrEmpty()) {
                    try {
                        val nativeArch = runner.getModelArchitecture()
                        if (!nativeArch.isNullOrEmpty()) {
                            AppLogger.i(TAG) { "loadModel: detected arch='$nativeArch', backfilling DB" }
                            localModelRepository?.updateArch(model.modelId, nativeArch)
                        }
                    } catch (_: Exception) { /* non-fatal */ }
                }

                val actualGpuLayers = runner.getGpuLayers()
                AppLogger.i(TAG) {
                    "loadModel: actual gpuLayers=$actualGpuLayers " +
                    "(requested=${effectiveConfig.nGpuLayers})"
                }
                if (effectiveConfig.nGpuLayers != 0 && actualGpuLayers == 0) {
                    AppLogger.w(
                        TAG,
                        message =
                            "loadModel: GPU offload was requested but unavailable. " +
                                    "Native safety net raised threads for CPU-only inference."

                    )
                }

                val systemPrompt = settings.systemPrompt.ifBlank { FALLBACK_SYSTEM_PROMPT } +
                    structuredOutputSystemPromptSuffix()

                val spRet = runner.processSystemPrompt(systemPrompt)
                if (spRet != 0) {
                    return@withLock ModelLoadResult.Error(
                        "Failed to initialize conversation context."
                    )
                }

                val ctxSize = runner.getContextLimit()

                // Phase 10: populate params_fit cache so the next load of this model
                // skips the ~1.2s probe. Only cache the primary-config path; if we
                // fell back to CPU (cpuFallbackConfig != null) the GPU load failed and
                // we shouldn't cache misleading nGpuLayers=0 under a gpuEnabled key.
                if (modelPath.isNotBlank() && cpuFallbackConfig == null && actualGpuLayers >= 0) {
                    val fitHints = deviceCapabilities.getDeviceHints()
                    val gpuEnabled = settings.useGpu && fitHints.gpuBackendAvailable
                    val fitCacheKey = paramsFitCacheKey(modelPath, fitHints.memoryBudgetMB, gpuEnabled)
                    paramsFitCache[fitCacheKey] = ParamsFitResult(nGpuLayers = actualGpuLayers, nCtx = ctxSize)
                    AppLogger.i(TAG) {
                        "loadModel: params_fit cached — key=$fitCacheKey, gpuLayers=$actualGpuLayers, ctx=$ctxSize"
                    }
                }

                // Store config string for benchmarking.
                lastRuntimeConfig = BenchmarkUtils.formatRuntimeConfig(
                    threads = effectiveConfig.nThreads,
                    batchThreads = effectiveConfig.nThreadsBatch,
                    batchSize = effectiveConfig.nBatch,
                    contextLimit = runner.getContextLimit(),
                    gpuLayers = runner.getGpuLayers(),
                    typeK = effectiveConfig.typeK,
                    typeV = effectiveConfig.typeV,
                    flashAttn = effectiveConfig.flashAttn,
                )

                // Consolidated runtime telemetry for benchmarking/thermal analysis
                AppLogger.i(TAG) {
                    "loadModel: success - contextSize=$ctxSize, actualGpuLayers=$actualGpuLayers, " +
                    "finalConfig(t=${effectiveConfig.nThreads}/${effectiveConfig.nThreadsBatch}, b=${effectiveConfig.nBatch}, " +
                    "kv=${effectiveConfig.typeK}/${effectiveConfig.typeV})"
                }
                ModelLoadResult.Success(contextSize = ctxSize)
            } catch (e: CancellationException) {
                // Must rethrow — swallowing CancellationException breaks coroutine cancellation
                // and can leave the native sampler in an invalid state for the next caller.
                AppLogger.i(TAG) { "loadModel: cancelled" }
                throw e
            } catch (e: Exception) {
                AppLogger.e(TAG, "loadModel: failed", e)
                ModelLoadResult.Error("An error occurred while loading the model: ${e.message}")
            }
        }

    private fun buildRunnerConfig(
        model: LocalModelEntity,
        temperature: Float,
        settings: AppSettings,
        modelPath: String = "",
    ): NativeRunnerConfig {
        val hints = deviceCapabilities.getDeviceHints()
        AppLogger.i(TAG) {
            "device: cores=${hints.performanceCoreCount}/${hints.totalCoreCount}, " +
            "memMB=${hints.memoryBudgetMB}, gpu=${hints.gpuBackendAvailable}"
        }
        // Phase 10: params_fit cache — skip the ~1.2s probe on repeated loads of the same model.
        // Cache is keyed on modelPath + memory tier (GB) + gpuEnabled flag.
        val gpuActive = hints.gpuBackendAvailable
        val gpuEnabled = settings.useGpu && gpuActive
        val cacheKey = if (modelPath.isNotBlank()) paramsFitCacheKey(modelPath, hints.memoryBudgetMB, gpuEnabled) else ""
        val cachedFit = if (cacheKey.isNotBlank()) paramsFitCache[cacheKey] else null
        if (cachedFit != null) {
            AppLogger.i(TAG) { "buildRunnerConfig: params_fit cache hit — skipping probe (nGpuLayers=${cachedFit.nGpuLayers}, nCtx=${cachedFit.nCtx})" }
        }

        val userRequestedCtx = (model.contextLength ?: 0).let { raw ->
            if (raw <= 0) AUTO_FIT_CONTEXT_CAP else raw.coerceAtMost(AUTO_FIT_CONTEXT_CAP)
        }
        val modelSizeMB = getModelFileSizeMB(model)
        val useMlock = modelSizeMB <= 4096 && hints.memoryBudgetMB >= 6000

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

        // Phase 10: use cached params_fit result to skip the ~1.2s probe on re-loads.
        val (resolvedNGpuLayers, resolvedNCtx, resolvedAutoFit) = if (cachedFit != null) {
            Triple(cachedFit.nGpuLayers, cachedFit.nCtx, false)
        } else {
            Triple(if (gpuEnabled) -1 else 0, userRequestedCtx, true)
        }

        return NativeRunnerConfig(
            nCtx           = resolvedNCtx,
            nCtxMin        = 512,
            nThreads       = nThreads,
            nThreadsBatch  = nThreadsBatch,
            nBatch         = batchSize,
            nUbatch        = nUbatch,
            flashAttn      = flashAttn,
            offloadKqv     = gpuEnabled,
            typeK          = typeK,
            typeV          = typeV,
            nGpuLayers     = resolvedNGpuLayers,
            useMmap        = true,
            useMlock       = useMlock,
            temperature    = temperature,
            autoFit        = resolvedAutoFit,
            cpuMask        = hints.perfCoreMask,
            cpuMaskBatch   = hints.perfCoreMask,
        )
    }

    private fun getModelFileSizeMB(model: LocalModelEntity): Long {
        return (model.sizeBytes ?: 0L) / (1024 * 1024)
    }

    override fun generateResponse(userPrompt: String): Flow<String> = flow {
        val remainingCtx = (runner.getContextLimit() - runner.getContextUsed()).coerceAtLeast(1)
        AppLogger.i(TAG) {
            "generate: promptLen=${userPrompt.length}, remainingCtx=$remainingCtx, " +
            "context=${runner.getContextUsed()}/${runner.getContextLimit()}"
        }
        val ret = runner.processUserPrompt(userPrompt, remainingCtx, STRICT_THINKING_OUTPUT_GRAMMAR)
        if (ret != 0) {
            throw IllegalStateException("Failed to process message")
        }
        try {
            runner.generateFlowTokens().collect { token ->
                emit(token)
            }
        } finally {
            runner.finalizeGeneration()
        }
    }

    override suspend fun unloadModel() = nativeLock.withLock {
        if (!nativeLoaded) return@withLock
        runner.unloadModel()
        nativeLoaded = false
    }

    override fun cancelGeneration() {
        AppLogger.i(TAG) { "cancelled" }
        runner.cancelGenerate()
    }

    override fun getContextUsed(): Int {
        return runner.getContextUsed()
    }

    override fun getContextLimit(): Int {
        return runner.getContextLimit()
    }

    override fun getStopReason(): Int {
        return runner.getStopReason()
    }

    override fun getRuntimeConfigString(): String = lastRuntimeConfig

    override fun isContextAboveThreshold(): Boolean {
        val limit = getContextLimit()
        if (limit == 0) return false
        val used = getContextUsed()
        return used.toFloat() / limit >= CONTEXT_THRESHOLD
    }

    override fun summarizeConversation(transcript: String): Flow<String> = flow {
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
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun resetContext() = withContext(Dispatchers.IO) {
        runner.clearContext()
    }

    override suspend fun resetContextWithSummary(summary: String, lastExchange: String): Boolean =
        withContext(Dispatchers.IO) {
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
                    append(structuredOutputSystemPromptSuffix())
                }

                val ret = runner.processSystemPrompt(systemPrompt)
                ret == 0
            } catch (e: Exception) {
                false
            }
        }

    private fun resolveModelPath(model: LocalModelEntity): String {
        if (model.localPath.isNotBlank() && storagePathProvider.fileExists(model.localPath)) {
            return model.localPath
        }
        val dir = storagePathProvider.getModelsStorageDirectory(model.modelId)
        return "$dir/${model.filename}"
    }

    private suspend fun currentSettings(): AppSettings =
        settingsRepository.getSettings().first()

    private suspend fun currentSystemPrompt(): String =
        currentSettings().systemPrompt.ifBlank { FALLBACK_SYSTEM_PROMPT }
}
