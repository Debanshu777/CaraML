package com.debanshu777.caraml.core.domain

import com.debanshu777.caraml.core.platform.PlatformCapabilities
import com.debanshu777.caraml.core.platform.PlatformPaths
import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import com.debanshu777.huggingfacemanager.download.StoragePathProvider
import com.debanshu777.runner.LlamaRunner
import com.debanshu777.runner.NativeRunnerConfig
import com.debanshu777.runner.generateFlowTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

class LlamaInferenceRepository(
    private val storagePathProvider: StoragePathProvider,
    private val runner: LlamaRunner,
) : InferenceRepository {

    companion object {
        const val CONTEXT_THRESHOLD = 0.85f
    }

    override suspend fun loadModel(model: LocalModelEntity): ModelLoadResult =
            try {
                runner.unloadModel()

                val modelPath = resolveModelPath(model)
                if (modelPath.isBlank()) {
                    return ModelLoadResult.Error("Model path is invalid")
                }
                if (!storagePathProvider.isModelFileReadable(modelPath)) {
                    return ModelLoadResult.Error(
                        "Model file not found or not readable. It may have been moved or deleted."
                    )
                }

                val nativeLibDir = PlatformPaths.getNativeLibDir()
                if (nativeLibDir.isBlank()) {
                    return ModelLoadResult.Error(
                        "Failed to initialize. Please restart the app."
                    )
                }

                runner.initialize(nativeLibDir)
                
                val config = buildRunnerConfig(model)
                val loaded = runner.loadModel(
                    modelPath = modelPath,
                    config = config,
                )

                if (!loaded) {
                    return ModelLoadResult.Error(
                        "Failed to load model. The file may be corrupted or unsupported."
                    )
                }

                val spRet = runner.processSystemPrompt("You are a helpful assistant.")
                if (spRet != 0) {
                    return ModelLoadResult.Error(
                        "Failed to initialize conversation context."
                    )
                }

                ModelLoadResult.Success
            } catch (e: Exception) {
                ModelLoadResult.Error("An error occurred while loading the model: ${e.message}")
            }

    private fun buildRunnerConfig(model: LocalModelEntity): NativeRunnerConfig {
        val hints = PlatformCapabilities.getDeviceHints()
        val effectiveCtx = (model.contextLength ?: hints.maxContextSize)
            .coerceAtMost(hints.maxContextSize)
        return NativeRunnerConfig(
            nCtx           = effectiveCtx,
            nCtxMin        = 512,
            nThreads       = hints.performanceCoreCount,
            nThreadsBatch  = (hints.totalCoreCount - 1).coerceAtLeast(hints.performanceCoreCount),
            nBatch         = if (hints.availableMemoryMB >= 4096) 512 else 256,
            nUbatch        = if (hints.availableMemoryMB >= 4096) 512 else 256,
            flashAttn      = -1,
            offloadKqv     = hints.gpuBackendCompiled,
            typeK          = if (hints.availableMemoryMB < 4096) 8 else 1,
            typeV          = if (hints.availableMemoryMB < 4096) 8 else 1,
            nGpuLayers     = if (hints.gpuBackendCompiled) -1 else 0,
            useMmap        = hints.supportsMmap,
            temperature    = 0.3f,
            autoFit        = true,
        )
    }

    override fun generateResponse(userPrompt: String, predictLength: Int): Flow<String> = flow {
        val ret = runner.processUserPrompt(userPrompt, predictLength)
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

    override fun unloadModel() {
        runner.unloadModel()
    }

    override fun cancelGeneration() {
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
        try {
            runner.clearContext()
            
            val spRet = runner.processSystemPrompt(
                "You are a helpful assistant. Your task is to summarize the conversation below."
            )
            if (spRet != 0) {
                throw IllegalStateException("Failed to process summarization system prompt")
            }

            // Truncate transcript if needed to fit within 60% of context
            val contextLimit = runner.getContextLimit()
            val maxTranscriptChars = (contextLimit * 0.6 * 3).toInt() // ~3 chars per token
            val truncatedTranscript = if (transcript.length > maxTranscriptChars) {
                // Keep the most recent part of the transcript
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
            try {
                runner.clearContext()
                
                // Build comprehensive system prompt with summary and last exchange
                val systemPrompt = buildString {
                    append("You are a helpful assistant.")
                    
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
}
