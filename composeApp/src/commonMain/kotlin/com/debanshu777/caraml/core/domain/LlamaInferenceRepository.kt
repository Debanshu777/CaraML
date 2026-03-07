package com.debanshu777.caraml.core.domain

import com.debanshu777.caraml.core.platform.PlatformPaths
import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import com.debanshu777.huggingfacemanager.download.StoragePathProvider
import com.debanshu777.runner.LlamaRunner
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
                val loaded = runner.loadModel(
                    modelPath = modelPath,
                    nGpuLayers = PlatformPaths.getDefaultGpuLayers()
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

    override suspend fun unloadCurrentModel() {
            try {
                runner.unloadModel()
            } catch (_: Exception) {
            }
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

    override fun cancelGeneration() {
        runner.cancelGenerate()
    }

    override fun shutdown() {
        try {
            runner.unloadModel()
            runner.shutdown()
        } catch (_: Exception) {
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
