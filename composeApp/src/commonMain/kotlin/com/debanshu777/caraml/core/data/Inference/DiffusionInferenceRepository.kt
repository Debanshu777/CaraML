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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Loads stable-diffusion.cpp checkpoints via [DiffusionRunner] using a single primary weight path.
 */
class DiffusionInferenceRepository(
    private val storagePathProvider: StoragePathProvider,
    private val runner: DiffusionRunner,
) {
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
            val config = DiffusionModelConfig(modelPath = modelPath)
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

}
