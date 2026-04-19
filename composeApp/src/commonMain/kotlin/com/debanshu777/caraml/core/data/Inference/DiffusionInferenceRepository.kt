package com.debanshu777.caraml.core.data.Inference

import com.debanshu777.caraml.core.platform.AppLogger
import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import com.debanshu777.diffusionrunner.DiffusionModelConfig
import com.debanshu777.diffusionrunner.DiffusionRunner
import com.debanshu777.diffusionrunner.ImageGenParams
import com.debanshu777.diffusionrunner.VideoGenParams
import com.debanshu777.diffusionrunner.generateImage
import com.debanshu777.diffusionrunner.generateVideo
import com.debanshu777.caraml.core.platform.PlatformPaths
import com.debanshu777.huggingfacemanager.download.StoragePathProvider
import com.debanshu777.huggingfacemanager.model.DIFFUSERS_BUNDLE_DB_FILENAME
import com.debanshu777.huggingfacemanager.model.isDiffusersModelDirectory
import com.debanshu777.huggingfacemanager.sdcpp.getModelSetup
import com.debanshu777.huggingfacemanager.sdcpp.SdCppComponentChecker
import com.debanshu777.huggingfacemanager.sdcpp.ComponentRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Loads stable-diffusion.cpp models via [DiffusionRunner], resolving all auxiliary components 
 * (VAE, CLIP, T5, LLM) based on model setup requirements.
 */
class DiffusionInferenceRepository(
    private val storagePathProvider: StoragePathProvider,
    private val runner: DiffusionRunner,
) {
    private val componentChecker = SdCppComponentChecker(storagePathProvider)
    companion object {
        private const val TAG = "DiffusionInference"
    }

    suspend fun loadModel(model: LocalModelEntity): ModelLoadResult = withContext(Dispatchers.Default) {
        try {
            val modelPath = resolveModelPath(model)
            if (modelPath.isBlank()) {
                return@withContext ModelLoadResult.Error("Model path is invalid")
            }
            if (!canLoadDiffusionModelAt(modelPath)) {
                return@withContext ModelLoadResult.Error(
                    "Model file not found or not readable. It may have been moved or deleted.",
                )
            }
            val nativeLibDir = PlatformPaths.getNativeLibDir()
            if (nativeLibDir.isBlank()) {
                return@withContext ModelLoadResult.Error(
                    "Failed to initialize. Please restart the app.",
                )
            }
            runner.release()
            runner.initialize(nativeLibDir)
            
            // Build full config with resolved component paths
            val config = buildDiffusionModelConfig(model, modelPath)
            val loaded = runner.loadModel(config)
            if (!loaded) {
                return@withContext ModelLoadResult.Error(
                    "Failed to load model. The file may be corrupted or unsupported.",
                )
            }
            AppLogger.i(TAG) { "loadModel: success" }
            ModelLoadResult.Success(contextSize = 0)
        } catch (e: Exception) {
            AppLogger.e(TAG, "loadModel: failed", e)
            ModelLoadResult.Error("An error occurred while loading the model.")
        }
    }

    suspend fun generateImage(params: ImageGenParams): Result<ByteArray> =
        withContext(Dispatchers.Default) {
            val r = runner.generateImage(params)
            r.exceptionOrNull()?.let { AppLogger.e(TAG, "generateImage failed", it) }
            r.fold(
                onSuccess = { Result.success(it) },
                onFailure = {
                    Result.failure(Exception("Generation failed. Please try again."))
                },
            )
        }

    suspend fun generateVideo(params: VideoGenParams): Result<List<ByteArray>> =
        withContext(Dispatchers.Default) {
            val r = runner.generateVideo(params)
            r.exceptionOrNull()?.let { AppLogger.e(TAG, "generateVideo failed", it) }
            r.fold(
                onSuccess = { Result.success(it) },
                onFailure = {
                    Result.failure(Exception("Generation failed. Please try again."))
                },
            )
        }

    fun release() {
        runner.release()
    }

    private fun resolveModelPath(model: LocalModelEntity): String {
        val dir = storagePathProvider.getModelsStorageDirectory(model.modelId)
        val relative = model.filename.trim().replace('\\', '/').trimStart('/')

        val candidates = buildList {
            if (model.localPath.isNotBlank()) add(model.localPath)
            if (model.filename == DIFFUSERS_BUNDLE_DB_FILENAME) {
                add(dir)
            }
            if (relative.isNotBlank()) add("$dir/$relative")
        }

        for (path in candidates.distinct()) {
            if (!storagePathProvider.fileExists(path)) continue
            if (isDiffusersModelDirectory(path, storagePathProvider::fileExists) &&
                storagePathProvider.isDirectoryReadable(path)
            ) {
                return path
            }
            if (storagePathProvider.isModelFileReadable(path)) {
                return path
            }
        }
        return candidates.firstOrNull { it.isNotBlank() } ?: ""
    }

    private fun canLoadDiffusionModelAt(path: String): Boolean {
        if (!storagePathProvider.fileExists(path)) return false
        if (isDiffusersModelDirectory(path, storagePathProvider::fileExists)) {
            return storagePathProvider.isDirectoryReadable(path)
        }
        return storagePathProvider.isModelFileReadable(path)
    }

    private fun buildDiffusionModelConfig(model: LocalModelEntity, modelPath: String): DiffusionModelConfig {
        val modelSetup = getModelSetup(model.modelId)
        
        if (modelSetup == null || modelSetup.selfContained) {
            // Fallback to legacy single-path behavior for unknown or self-contained models
            return DiffusionModelConfig(modelPath = modelPath)
        }
        
        // Resolve all component paths by role
        val componentPaths = componentChecker.resolveComponentsByRole(modelSetup)
        val params = modelSetup.recommendedParams
        
        return DiffusionModelConfig(
            modelPath = modelPath,
            vaePath = componentPaths[ComponentRole.VAE] ?: "",
            llmPath = componentPaths[ComponentRole.LLM] ?: "",
            clipLPath = componentPaths[ComponentRole.CLIP_L] ?: "",
            clipGPath = componentPaths[ComponentRole.CLIP_G] ?: "",
            t5xxlPath = componentPaths[ComponentRole.T5XXL] ?: componentPaths[ComponentRole.UMT5XXL] ?: "",
            offloadToCpu = params?.offloadToCpu ?: false,
            keepClipOnCpu = params?.clipOnCpu ?: false,
            keepVaeOnCpu = params?.keepVaeOnCpu ?: false,
            diffusionFlashAttn = params?.diffusionFlashAttn ?: false,
        ).also { config ->
            AppLogger.d(TAG) { 
                "Built DiffusionModelConfig for ${model.modelId}:\n" +
                "  modelPath: $modelPath\n" +
                "  vaePath: ${config.vaePath}\n" +
                "  llmPath: ${config.llmPath}\n" +
                "  clipLPath: ${config.clipLPath}\n" +
                "  clipGPath: ${config.clipGPath}\n" +
                "  t5xxlPath: ${config.t5xxlPath}\n" +
                "  offloadToCpu: ${config.offloadToCpu}\n" +
                "  keepClipOnCpu: ${config.keepClipOnCpu}\n" +
                "  diffusionFlashAttn: ${config.diffusionFlashAttn}"
            }
        }
    }

}
