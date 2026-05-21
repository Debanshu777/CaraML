package com.debanshu777.caraml.core.data.Inference

import com.debanshu777.caraml.core.platform.AppLogger
import com.debanshu777.caraml.core.platform.DeviceCapabilities
import com.debanshu777.caraml.core.platform.PlatformPaths
import com.debanshu777.caraml.core.settings.AppSettings
import com.debanshu777.caraml.core.data.settings.SettingsRepository
import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import com.debanshu777.huggingfacemanager.download.StoragePathProvider
import com.debanshu777.runner.LlamaRunner
import com.debanshu777.runner.NativeRunnerConfig
import com.debanshu777.runner.STRICT_THINKING_OUTPUT_GRAMMAR
import com.debanshu777.runner.generateFlowTokens
import com.debanshu777.runner.structuredOutputSystemPromptSuffix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class LlamaInferenceRepository(
    private val storagePathProvider: StoragePathProvider,
    private val runner: LlamaRunner,
    private val deviceCapabilities: DeviceCapabilities,
    private val settingsRepository: SettingsRepository,
) : InferenceRepository {

    companion object {
        private const val TAG = "Inference"
        const val CONTEXT_THRESHOLD = 0.85f
        private const val FALLBACK_SYSTEM_PROMPT = "You are a helpful assistant."
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
                    temperature = settings.temperature
                )
                AppLogger.i(TAG) {
                    "config: threads=${config.nThreads}/${config.nThreadsBatch}, " +
                    "batch=${config.nBatch}, ctx=${config.nCtx}, " +
                    "gpuLayers=${config.nGpuLayers}, kv=${config.typeK}/${config.typeV}"
                }

                val loaded = runner.loadModel(
                    modelPath = modelPath,
                    config = config,
                )

                if (!loaded) {
                    return@withLock ModelLoadResult.Error(
                        "Failed to load model. The file may be corrupted or unsupported."
                    )
                }
                nativeLoaded = true

                val systemPrompt = settings.systemPrompt.ifBlank { FALLBACK_SYSTEM_PROMPT } +
                    structuredOutputSystemPromptSuffix()

                val spRet = runner.processSystemPrompt(systemPrompt)
                if (spRet != 0) {
                    return@withLock ModelLoadResult.Error(
                        "Failed to initialize conversation context."
                    )
                }

                val ctxSize = runner.getContextLimit()
                AppLogger.i(TAG) { "loadModel: success, contextSize=$ctxSize" }
                ModelLoadResult.Success(contextSize = ctxSize)
            } catch (e: kotlinx.coroutines.CancellationException) {
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
    ): NativeRunnerConfig {
        val hints = deviceCapabilities.getDeviceHints()
        AppLogger.i(TAG) {
            "device: cores=${hints.performanceCoreCount}/${hints.totalCoreCount}, " +
            "memMB=${hints.memoryBudgetMB}, gpu=${hints.gpuBackendAvailable}"
        }
        val userRequestedCtx = model.contextLength ?: 0
        val modelSizeMB = getModelFileSizeMB(model)
        val isLargeModel = modelSizeMB > 4096

        val gpuActive = hints.gpuBackendAvailable
        val nThreads = when {
            gpuActive -> 2.coerceAtLeast(hints.performanceCoreCount / 2)
            isLargeModel -> (hints.performanceCoreCount - 1).coerceAtLeast(2)
            else -> hints.performanceCoreCount
        }
        val nThreadsBatch = if (gpuActive) {
            (hints.totalCoreCount / 2).coerceAtLeast(2)
        } else {
            (hints.totalCoreCount - 1).coerceAtLeast(hints.performanceCoreCount)
        }

        val batchSize = when {
            modelSizeMB > 8192 -> 128
            modelSizeMB > 4096 -> 256
            else -> if (hints.memoryBudgetMB >= 4096) 512 else 256
        }

        return NativeRunnerConfig(
            nCtx           = userRequestedCtx,
            nCtxMin        = 512,
            nThreads       = nThreads,
            nThreadsBatch  = nThreadsBatch,
            nBatch         = batchSize,
            nUbatch        = batchSize / 2,
            flashAttn      = -1,
            offloadKqv     = gpuActive,
            typeK          = if (hints.memoryBudgetMB < 4096) 8 else 1,
            typeV          = if (hints.memoryBudgetMB < 4096) 8 else 1,
            nGpuLayers     = if (gpuActive) -1 else 0,
            useMmap        = true,
            temperature    = temperature,
            autoFit        = true,
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
