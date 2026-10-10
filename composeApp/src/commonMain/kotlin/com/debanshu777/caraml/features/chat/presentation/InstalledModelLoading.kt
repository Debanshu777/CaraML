package com.debanshu777.caraml.features.chat.presentation

import com.debanshu777.caraml.core.data.inference.ModelLoadResult
import com.debanshu777.caraml.core.platform.AppLogger
import kotlin.time.TimeSource
import com.debanshu777.caraml.core.recommendation.AssessmentReason
import com.debanshu777.caraml.core.recommendation.InstalledModelLoadPreparation
import com.debanshu777.caraml.core.recommendation.InstalledModelLoadResolution
import com.debanshu777.caraml.core.recommendation.LoadAdmission
import com.debanshu777.caraml.core.recommendation.LoadAdmissionReason
import com.debanshu777.caraml.core.recommendation.LoadRequest
import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import com.debanshu777.caraml.features.chat.domain.GenerationMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal suspend fun awaitPreviousModelLoad(previousJob: Job?) {
    if (previousJob != null) {
        withContext(NonCancellable) {
            previousJob.join()
        }
    }
    currentCoroutineContext().ensureActive()
}

internal suspend fun loadExactModelForMode(
    mode: GenerationMode,
    request: LoadRequest,
    loadText: suspend (LoadRequest) -> ModelLoadResult,
    loadDiffusion: suspend (LoadRequest) -> ModelLoadResult,
): ModelLoadResult = when (mode) {
    GenerationMode.Text -> loadText(request)
    GenerationMode.Image,
    GenerationMode.Video,
    -> loadDiffusion(request)
}

internal suspend fun loadInstalledModel(
    model: LocalModelEntity,
    mode: GenerationMode,
    prepare: suspend (LocalModelEntity, GenerationMode) -> InstalledModelLoadPreparation,
    releaseRunners: suspend () -> Unit,
    assess: suspend (InstalledModelLoadPreparation.Ready) -> InstalledModelLoadResolution,
    loadText: suspend (LoadRequest) -> ModelLoadResult,
    loadDiffusion: suspend (LoadRequest) -> ModelLoadResult,
): ModelLoadResult {
    val started = TimeSource.Monotonic.markNow()
    AppLogger.i("ModelLoad") { "stage=prepare mode=$mode sizeBytes=${model.sizeBytes ?: -1}" }
    val preparation = prepare(model, mode)
    AppLogger.i("ModelLoad") {
        "stage=prepared outcome=${preparation::class.simpleName} elapsedMs=${started.elapsedNow().inWholeMilliseconds}"
    }
    val resolution = when (preparation) {
        is InstalledModelLoadPreparation.Terminal -> preparation.resolution
        is InstalledModelLoadPreparation.Ready -> {
            try {
                releaseRunners()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                return ModelLoadResult.Error("The previous model could not be released safely.")
            }
            assess(preparation)
        }
    }
    AppLogger.i("ModelLoad") {
        "stage=resolved outcome=${resolution::class.simpleName} " +
            "reason=${(resolution as? InstalledModelLoadResolution.NotAdmissible)?.reason ?: "none"} " +
            "elapsedMs=${started.elapsedNow().inWholeMilliseconds}"
    }
    return when (resolution) {
        is InstalledModelLoadResolution.Ready -> when (mode) {
            GenerationMode.Text -> loadText(resolution.request)
            GenerationMode.Image,
            GenerationMode.Video,
            -> loadDiffusion(resolution.request)
        }
        is InstalledModelLoadResolution.SafeAlternative -> ModelLoadResult.AdmissionRequired(
            LoadAdmission.SafeAlternativeAvailable(
                saferRequest = resolution.saferRequest,
            ),
        )
        InstalledModelLoadResolution.NeedsNetwork -> ModelLoadResult.Error(
            "Connect once to verify this installed model's metadata, then try again.",
        )
        is InstalledModelLoadResolution.NotAdmissible -> ModelLoadResult.Error(
            resolution.reason.safeInstalledModelMessage(mode),
        )
        is InstalledModelLoadResolution.Rejected -> ModelLoadResult.Error(
            "The installed model could not be verified.",
        )
        InstalledModelLoadResolution.Failed -> ModelLoadResult.Error(
            "The installed model could not be prepared right now. Try again.",
        )
    }.also { result ->
        AppLogger.i("ModelLoad") {
            "stage=complete outcome=${result::class.simpleName} elapsedMs=${started.elapsedNow().inWholeMilliseconds}"
        }
    }
}

internal fun AssessmentReason.safeInstalledModelMessage(mode: GenerationMode? = null): String = when (this) {
    AssessmentReason.MEMORY_NO_FIT ->
        "This model does not fit the current memory headroom. Try Auto KV cache or close other apps."
    AssessmentReason.INVALID_METADATA ->
        "This installed model's verified metadata is incomplete."
    AssessmentReason.UNKNOWN_ARCHITECTURE,
    AssessmentReason.GGUF_VERSION_UNKNOWN,
    AssessmentReason.ENGINE_SUPPORT_UNKNOWN,
    AssessmentReason.RECOMMENDATION_EVIDENCE_INCOMPLETE,
    -> "Compatibility evidence for this installed model is incomplete."
    AssessmentReason.UNSUPPORTED_FORMAT -> if (mode == GenerationMode.Text) {
        "This text model's file format is not supported by this engine. Choose a GGUF text model."
    } else {
        "This model is not supported by the installed inference engine."
    }
    AssessmentReason.UNSUPPORTED_ARCHITECTURE,
    AssessmentReason.UNSUPPORTED_GGUF_VERSION,
    AssessmentReason.UNSUPPORTED_QUANTIZATION,
    AssessmentReason.UNSUPPORTED_ENGINE_FEATURE,
    -> "This model is not supported by the installed inference engine."
    AssessmentReason.REQUIRED_BACKEND_UNAVAILABLE ->
        "The required inference backend is unavailable on this device."
    AssessmentReason.INVALID_WORKLOAD,
    AssessmentReason.PARAMETER_LIMIT_EXCEEDED,
    AssessmentReason.CONTEXT_LIMIT_EXCEEDED,
    -> "The current runtime settings cannot produce a safe workload for this model."
    AssessmentReason.INVALID_CORE_COUNT ->
        "The device CPU capability reading is invalid. Try again."
    AssessmentReason.INVALID_OS_MEMORY_READING ->
        "The device memory reading is invalid. Try again."
    AssessmentReason.RESOURCE_READING_UNAVAILABLE ->
        "Current device resource readings are unavailable. Try again."
    AssessmentReason.RESOURCE_SNAPSHOT_STALE ->
        "Device resource readings changed before loading. Try again."
    AssessmentReason.BACKEND_CAPABILITY_UNKNOWN ->
        "The selected backend's memory capability is unavailable."
    AssessmentReason.STORAGE_NO_FIT,
    AssessmentReason.INSUFFICIENT_STORAGE,
    -> "This model does not fit the current available storage."
    AssessmentReason.MEMORY_BOUNDS_UNKNOWN ->
        "Current memory headroom could not be verified. Try again."
    AssessmentReason.NO_RUN_PLAN ->
        "No safe runtime plan is available for this installed model."
    else -> "This installed model is not admissible on this device."
}

internal fun LoadAdmissionReason.safeBlockedLoadMessage(): String = when (this) {
    LoadAdmissionReason.CURRENT_MEMORY_PRESSURE ->
        "The device is under memory pressure. Close other apps and try again."
    LoadAdmissionReason.CURRENT_THERMAL_PRESSURE ->
        "The device is too hot to load a model. Let it cool down and try again."
    LoadAdmissionReason.RESOURCE_SNAPSHOT_UNAVAILABLE ->
        "Current device resource readings are unavailable. Try again."
    LoadAdmissionReason.NATIVE_PREFLIGHT_UNAVAILABLE ->
        "The inference engine could not check this model. Try again or choose another model."
    LoadAdmissionReason.NATIVE_TARGET_MODEL_REQUIRED ->
        "This assistant model needs its main model and cannot be used alone. Choose a standalone model."

    LoadAdmissionReason.INCOMPATIBLE_MODEL ->
        "This model is incompatible with the current device capabilities."
    LoadAdmissionReason.INSUFFICIENT_INFORMATION ->
        "Current device evidence is insufficient for a safe load. Try again."
    LoadAdmissionReason.NO_SAFE_CONFIGURATION ->
        "No safe runtime configuration is available for this model."
    LoadAdmissionReason.INVALID_MODEL ->
        "The installed model changed or could not be verified."
    LoadAdmissionReason.NATIVE_PREFLIGHT_INVALID ->
        "The native engine rejected this model before loading."
    LoadAdmissionReason.NATIVE_BACKEND_INCOMPATIBLE ->
        "The selected accelerator is incompatible with this model."
    else -> "This model cannot be loaded safely with the available information."
}
