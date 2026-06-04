package com.debanshu777.caraml.core.data.inference

import com.debanshu777.caraml.core.platform.AppLogger
import com.debanshu777.caraml.core.platform.DeviceCapabilities
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
import com.debanshu777.huggingfacemanager.sdcpp.SdCppRecommendedParams
import com.debanshu777.huggingfacemanager.sdcpp.getModelSetup
import com.debanshu777.huggingfacemanager.sdcpp.SdCppComponentChecker
import com.debanshu777.huggingfacemanager.sdcpp.ComponentRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Loads stable-diffusion.cpp models via [DiffusionRunner], resolving all auxiliary components 
 * (VAE, CLIP, T5, LLM) based on model setup requirements.
 */
class DiffusionInferenceRepository(
    private val storagePathProvider: StoragePathProvider,
    private val runner: DiffusionRunner,
    private val deviceCapabilities: DeviceCapabilities,
) {
    private val componentChecker = SdCppComponentChecker(storagePathProvider)

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

            // Component presence check: verify all required auxiliary files are on disk before
            // attempting to load. Missing components (e.g. text_encoder_2 for SDXL) cause
            // stable-diffusion.cpp to misidentify the architecture and crash with SIGABRT.
            val setup = getModelSetup(model.modelId)
            if (setup != null && !setup.selfContained) {
                val missing = componentChecker.getMissingComponents(setup)
                if (missing.isNotEmpty()) {
                    val labels = missing.joinToString(", ") { it.role.displayLabel }
                    return@withContext ModelLoadResult.Error(
                        "Missing required components: $labels. " +
                        "Open the model page to download them."
                    )
                }
            }

            // Pre-flight memory check: sum of main model + all components vs device RAM.
            // Native loader will OOM-kill the process silently if weights don't fit, so we
            // refuse upfront with a clear error rather than crashing.
            preflightMemoryCheck(model, modelPath)?.let { error ->
                return@withContext error
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

    /**
     * Returns a [ModelLoadResult.Error] if the model + components clearly exceed the device
     * memory budget. Returns null when the model has a reasonable chance of loading.
     *
     * This guards against silent OOM kills (the Android low-memory killer terminates the
     * process without throwing a Kotlin exception).
     */
    private fun preflightMemoryCheck(
        model: LocalModelEntity,
        modelPath: String,
    ): ModelLoadResult.Error? {
        val mainSize = storagePathProvider.getFileSize(modelPath)
        val componentSize = getModelSetup(model.modelId)?.let { setup ->
            if (setup.selfContained) 0L
            else componentChecker.resolveComponentsByRole(setup).values
                .sumOf { storagePathProvider.getFileSize(it) }
        } ?: 0L
        val totalBytes = mainSize + componentSize
        if (totalBytes <= 0L) return null

        val budgetBytes = deviceCapabilities.getDeviceHints().memoryBudgetMB * 1024L * 1024L
        if (budgetBytes <= 0L) return null

        // Weights typically need ~1.1x their on-disk size in RAM (decompression + activations
        // + KV cache headroom). Anything above the device's safe budget is rejected.
        val requiredBytes = (totalBytes * 1.1).toLong()
        AppLogger.i(TAG) {
            "preflight: weights=${formatGB(totalBytes)}, required~${formatGB(requiredBytes)}, " +
                "device budget=${formatGB(budgetBytes)}"
        }
        if (requiredBytes <= budgetBytes) return null

        return ModelLoadResult.Error(
            "This model needs about ${formatGB(requiredBytes)} of RAM but your device has " +
                "only ${formatGB(budgetBytes)} available for inference. Try a smaller " +
                "quantization (e.g. Q4_K_S or smaller) or a smaller model variant."
        )
    }

    private fun formatGB(bytes: Long): String {
        if (bytes <= 0L) return "0 GB"
        val gb = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
        return if (gb >= 10) "${gb.toInt()} GB" else "${(kotlin.math.round(gb * 10) / 10)} GB"
    }

    suspend fun generateImage(params: ImageGenParams): Result<ByteArray> =
        withContext(Dispatchers.Default) {
            val startMs = kotlin.time.TimeSource.Monotonic.markNow()
            _imageGenProgress.value = DiffusionProgress(
                step = 0, totalSteps = 0, requestedSteps = params.steps, elapsedSeconds = 0,
            )
            val pollJob = launch {
                while (isActive) {
                    delay(250)
                    val raw = runner.getStepProgress()
                    _imageGenProgress.value = DiffusionProgress(
                        step = raw[0],
                        totalSteps = raw[1],
                        requestedSteps = params.steps,
                        elapsedSeconds = startMs.elapsedNow().inWholeSeconds.toInt(),
                    )
                }
            }
            try {
                val r = runner.generateImage(params)
                r.exceptionOrNull()?.let { AppLogger.e(TAG, "generateImage failed", it) }
                r.fold(
                    onSuccess = { Result.success(it) },
                    onFailure = { Result.failure(Exception("Generation failed. Please try again.")) },
                )
            } finally {
                pollJob.cancel()
                _imageGenProgress.value = null
            }
        }

    suspend fun generateVideo(params: VideoGenParams): Result<List<ByteArray>> =
        withContext(Dispatchers.Default) {
            val startMs = kotlin.time.TimeSource.Monotonic.markNow()
            _imageGenProgress.value = DiffusionProgress(
                step = 0, totalSteps = 0, requestedSteps = params.steps, elapsedSeconds = 0,
            )
            val pollJob = launch {
                while (isActive) {
                    delay(250)
                    val raw = runner.getStepProgress()
                    _imageGenProgress.value = DiffusionProgress(
                        step = raw[0],
                        totalSteps = raw[1],
                        requestedSteps = params.steps,
                        elapsedSeconds = startMs.elapsedNow().inWholeSeconds.toInt(),
                    )
                }
            }
            try {
                val r = runner.generateVideo(params)
                r.exceptionOrNull()?.let { AppLogger.e(TAG, "generateVideo failed", it) }
                r.fold(
                    onSuccess = { Result.success(it) },
                    onFailure = { Result.failure(Exception("Generation failed. Please try again.")) },
                )
            } finally {
                pollJob.cancel()
                _imageGenProgress.value = null
            }
        }

    fun release() {
        runner.release()
    }

    /** Returns recommended inference parameters for the given model, or null for unknown/simple models. */
    fun getRecommendedParams(model: LocalModelEntity): SdCppRecommendedParams? =
        getModelSetup(model.modelId)?.recommendedParams

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
        val tightMemory = shouldFreeParamsImmediately(model, modelPath)
        val taesdPath = resolveOptionalTaesdPath()

        if (modelSetup == null || modelSetup.selfContained) {
            // Fallback to legacy single-path behavior for unknown or self-contained models
            return DiffusionModelConfig(
                modelPath = modelPath,
                prediction = modelSetup?.recommendedParams?.prediction ?: -1,
                flowShift = modelSetup?.recommendedParams?.flowShift ?: Float.POSITIVE_INFINITY,
                freeParamsImmediately = tightMemory,
                taesdPath = taesdPath,
                vaeTiling = shouldEnableVaeTiling(modelSetup?.recommendedParams),
            )
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
            freeParamsImmediately = tightMemory,
            flowShift = params?.flowShift ?: Float.POSITIVE_INFINITY,
            prediction = params?.prediction ?: -1,
            taesdPath = taesdPath,
            vaeTiling = shouldEnableVaeTiling(params),
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
                "  keepVaeOnCpu: ${config.keepVaeOnCpu}\n" +
                "  diffusionFlashAttn: ${config.diffusionFlashAttn}\n" +
                "  freeParamsImmediately: ${config.freeParamsImmediately}\n" +
                "  flowShift: ${config.flowShift}\n" +
                "  prediction: ${config.prediction}\n" +
                "  taesdPath: ${config.taesdPath}\n" +
                "  vaeTiling: ${config.vaeTiling}"
            }
        }
    }

    /**
     * Auto-resolves a TAESD (tiny autoencoder) path if the "madebyollin/taesd" model has been
     * downloaded. Returns empty string when not available — the field is optional in the runner.
     */
    private fun resolveOptionalTaesdPath(): String {
        val taesdSetup = getModelSetup("madebyollin/taesd") ?: return ""
        // TAESD is self-contained: its model file sits directly under its storage dir.
        // The main model file has no fixed filename in the registry, so probe the dir for
        // any .safetensors file (TAESD is typically a single small safetensors file).
        val dir = storagePathProvider.getModelsStorageDirectory("madebyollin/taesd")
        val candidates = listOf(
            "$dir/taesd_decoder.safetensors",
            "$dir/diffusion_pytorch_model.safetensors",
        )
        return candidates.firstOrNull { storagePathProvider.fileExists(it) } ?: ""
    }

    /**
     * Returns true when the recommended (or default) output dimensions exceed 512×512.
     * In that case VAE tiling is needed to avoid OOM during the decode step.
     */
    private fun shouldEnableVaeTiling(params: SdCppRecommendedParams?): Boolean {
        val w = params?.width ?: 512
        val h = params?.height ?: 512
        return w * h > 512 * 512
    }

    /**
     * Frees weights after each generation when device memory is tight (weights >= 65% of
     * budget). Cuts steady-state RAM in half at the cost of re-loading on the next gen.
     */
    private fun shouldFreeParamsImmediately(model: LocalModelEntity, modelPath: String): Boolean {
        val mainSize = storagePathProvider.getFileSize(modelPath)
        val componentSize = getModelSetup(model.modelId)?.let { setup ->
            if (setup.selfContained) 0L
            else componentChecker.resolveComponentsByRole(setup).values
                .sumOf { storagePathProvider.getFileSize(it) }
        } ?: 0L
        val totalBytes = mainSize + componentSize
        val budgetBytes = deviceCapabilities.getDeviceHints().memoryBudgetMB * 1024L * 1024L
        if (totalBytes <= 0L || budgetBytes <= 0L) return false
        return totalBytes.toDouble() / budgetBytes.toDouble() >= 0.65
    }

}
