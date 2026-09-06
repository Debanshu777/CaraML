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
import com.debanshu777.caraml.core.data.settings.SettingsRepository
import com.debanshu777.huggingfacemanager.download.StoragePathProvider
import com.debanshu777.huggingfacemanager.download.DownloadManager
import com.debanshu777.huggingfacemanager.download.ArtifactManifest
import com.debanshu777.huggingfacemanager.sdcpp.SdCppRecommendedParams
import com.debanshu777.huggingfacemanager.sdcpp.getModelSetup
import com.debanshu777.huggingfacemanager.sdcpp.ComponentRole
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

/**
 * Loads stable-diffusion.cpp models via [DiffusionRunner], resolving all auxiliary components 
 * (VAE, CLIP, T5, LLM) based on model setup requirements.
 */
class DiffusionInferenceRepository(
    private val storagePathProvider: StoragePathProvider,
    private val runner: DiffusionRunner,
    private val deviceCapabilities: DeviceCapabilities,
    private val settingsRepository: SettingsRepository,
) {
    private val downloadManager = DownloadManager(storagePathProvider)
    private var lastLoadedArchStr: String? = null

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
            val aggregate = downloadManager.validatedBundle(model.modelId)
                ?: return@withContext ModelLoadResult.Error(
                    "The installed model could not be verified. Open the model page to repair it.",
                )
            val modelSetup = getModelSetup(model.modelId)
            if (!aggregate.isCompleteDiffusionInstallation(model.modelId, modelSetup)) {
                return@withContext ModelLoadResult.Error(
                    "The installed model is incomplete. Open the model page to repair it.",
                )
            }
            val modelRoot = storagePathProvider.getModelsStorageDirectory(model.modelId).trimEnd('/', '\\')
            val loadTarget = aggregate.verifiedDiffusionLoadTarget(model.modelId)
                ?: return@withContext ModelLoadResult.Error(
                    "The installed model is incomplete. Open the model page to repair it.",
                )
            val modelPath = when (loadTarget) {
                VerifiedDiffusionLoadTarget.Directory -> modelRoot
                is VerifiedDiffusionLoadTarget.File -> "$modelRoot/${loadTarget.relativePath}"
            }
            val targetReadable = when (loadTarget) {
                VerifiedDiffusionLoadTarget.Directory -> storagePathProvider.isDirectoryReadable(modelPath)
                is VerifiedDiffusionLoadTarget.File -> storagePathProvider.isModelFileReadable(modelPath)
            }
            if (!targetReadable) {
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

            // Pre-flight memory check: sum of main model + all components vs device RAM.
            // Native loader will OOM-kill the process silently if weights don't fit, so we
            // refuse upfront with a clear error rather than crashing.
            preflightMemoryCheck(aggregate)?.let { error ->
                return@withContext error
            }

            runner.release()
            runner.initialize(nativeLibDir)

            // Build full config with resolved component paths
            val config = buildDiffusionModelConfig(model, modelPath, aggregate)
            val loaded = runner.loadModel(config)
            if (!loaded) {
                return@withContext ModelLoadResult.Error(
                    "Failed to load model. The file may be corrupted or unsupported.",
                )
            }
            // Cache the native architecture string for step-count policy lookups.
            lastLoadedArchStr = runner.getDiffusionModelMetadata(modelPath)?.architecture
            AppLogger.i(TAG) { "loadModel: success" }
            ModelLoadResult.Success(contextSize = 0)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            AppLogger.e(TAG, "loadModel: failed")
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
    private fun preflightMemoryCheck(aggregate: ArtifactManifest): ModelLoadResult.Error? {
        val totalBytes = aggregate.entries.fold(0L) { total, entry ->
            if (total > Long.MAX_VALUE - entry.byteCount) Long.MAX_VALUE else total + entry.byteCount
        }
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
        lastLoadedArchStr = null
    }

    /** Architecture string reported by the native layer for the currently loaded model. */
    fun getLastLoadedArchitecture(): String? = lastLoadedArchStr

    /** Returns recommended inference parameters for the given model, or null for unknown/simple models. */
    fun getRecommendedParams(model: LocalModelEntity): SdCppRecommendedParams? =
        getModelSetup(model.modelId)?.recommendedParams

    private suspend fun buildDiffusionModelConfig(
        model: LocalModelEntity,
        modelPath: String,
        aggregate: ArtifactManifest,
    ): DiffusionModelConfig {
        val modelSetup = getModelSetup(model.modelId)
        val tightMemory = shouldFreeParamsImmediately(aggregate)
        val taesdPath = resolveOptionalTaesdPath()
        val hints = deviceCapabilities.getDeviceHints()
        val settings = settingsRepository.getSettings().first()
        val gpuEnabled = settings.useGpu && hints.gpuBackendAvailable

        if (modelSetup == null || modelSetup.selfContained) {
            // Fallback to legacy single-path behavior for unknown or self-contained models
            return DiffusionModelConfig(
                modelPath = modelPath,
                offloadToCpu = !gpuEnabled,
                prediction = modelSetup?.recommendedParams?.prediction ?: -1,
                flowShift = modelSetup?.recommendedParams?.flowShift ?: Float.POSITIVE_INFINITY,
                freeParamsImmediately = tightMemory,
                diffusionFlashAttn = gpuEnabled,
                taesdPath = taesdPath,
                vaeTiling = shouldEnableVaeTiling(modelSetup?.recommendedParams),
            )
        }

        // Resolve all component paths by role
        val componentPaths = modelSetup.components.associate { component ->
            val entry = aggregate.entries.singleOrNull {
                it.logicalRole == component.role.name.lowercase() &&
                    it.identity.repositoryId == component.repoId &&
                    it.identity.relativePath == component.filePath
            }
            component.role to (entry?.let {
                "${storagePathProvider.getModelsStorageDirectory(it.identity.repositoryId).trimEnd('/', '\\')}/${it.localRelativePath}"
            } ?: "")
        }
        val params = modelSetup.recommendedParams

        return DiffusionModelConfig(
            modelPath = modelPath,
            vaePath = componentPaths[ComponentRole.VAE] ?: "",
            llmPath = componentPaths[ComponentRole.LLM] ?: "",
            clipLPath = componentPaths[ComponentRole.CLIP_L] ?: "",
            clipGPath = componentPaths[ComponentRole.CLIP_G] ?: "",
            t5xxlPath = componentPaths[ComponentRole.T5XXL] ?: componentPaths[ComponentRole.UMT5XXL] ?: "",
            offloadToCpu = !gpuEnabled || (params?.offloadToCpu ?: false),
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
                "Built DiffusionModelConfig:\n" +
                "  hasVae: ${config.vaePath.isNotBlank()}\n" +
                "  hasLlm: ${config.llmPath.isNotBlank()}\n" +
                "  hasClipL: ${config.clipLPath.isNotBlank()}\n" +
                "  hasClipG: ${config.clipGPath.isNotBlank()}\n" +
                "  hasT5xxl: ${config.t5xxlPath.isNotBlank()}\n" +
                "  offloadToCpu: ${config.offloadToCpu}\n" +
                "  keepClipOnCpu: ${config.keepClipOnCpu}\n" +
                "  keepVaeOnCpu: ${config.keepVaeOnCpu}\n" +
                "  diffusionFlashAttn: ${config.diffusionFlashAttn}\n" +
                "  freeParamsImmediately: ${config.freeParamsImmediately}\n" +
                "  flowShift: ${config.flowShift}\n" +
                "  prediction: ${config.prediction}\n" +
                "  hasTaesd: ${config.taesdPath.isNotBlank()}\n" +
                "  vaeTiling: ${config.vaeTiling}"
            }
        }
    }

    /**
     * Auto-resolves a TAESD (tiny autoencoder) path if the "madebyollin/taesd" model has been
     * downloaded. Returns empty string when not available — the field is optional in the runner.
     */
    private suspend fun resolveOptionalTaesdPath(): String {
        val repositoryId = "madebyollin/taesd"
        val aggregate = downloadManager.validatedBundle(repositoryId) ?: return ""
        val entry = aggregate.entries.singleOrNull {
            it.logicalRole == "model" && it.identity.repositoryId == repositoryId
        } ?: return ""
        val root = storagePathProvider.getModelsStorageDirectory(repositoryId).trimEnd('/', '\\')
        return "$root/${entry.localRelativePath}"
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
    private fun shouldFreeParamsImmediately(aggregate: ArtifactManifest): Boolean {
        val totalBytes = aggregate.entries.fold(0L) { total, entry ->
            if (total > Long.MAX_VALUE - entry.byteCount) Long.MAX_VALUE else total + entry.byteCount
        }
        val budgetBytes = deviceCapabilities.getDeviceHints().memoryBudgetMB * 1024L * 1024L
        if (totalBytes <= 0L || budgetBytes <= 0L) return false
        return totalBytes.toDouble() / budgetBytes.toDouble() >= 0.65
    }

}
