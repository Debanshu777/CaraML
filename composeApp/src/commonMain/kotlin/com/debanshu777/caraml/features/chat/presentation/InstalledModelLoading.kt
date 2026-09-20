package com.debanshu777.caraml.features.chat.presentation

import com.debanshu777.caraml.core.data.inference.ModelLoadResult
import com.debanshu777.caraml.core.recommendation.AssessmentReason
import com.debanshu777.caraml.core.recommendation.InstalledModelLoadResolution
import com.debanshu777.caraml.core.recommendation.LoadAdmissionReason
import com.debanshu777.caraml.core.recommendation.LoadRequest
import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import com.debanshu777.caraml.features.chat.domain.GenerationMode
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
    unloadText: suspend () -> Unit,
    releaseDiffusion: suspend () -> Unit,
    loadText: suspend (LoadRequest) -> ModelLoadResult,
    loadDiffusion: suspend (LoadRequest) -> ModelLoadResult,
): ModelLoadResult = when (mode) {
    GenerationMode.Text -> {
        releaseDiffusion()
        loadText(request)
    }
    GenerationMode.Image,
    GenerationMode.Video,
    -> {
        unloadText()
        loadDiffusion(request)
    }
}

internal suspend fun loadInstalledModel(
    model: LocalModelEntity,
    mode: GenerationMode,
    resolve: suspend (LocalModelEntity, GenerationMode) -> InstalledModelLoadResolution,
    loadText: suspend (LoadRequest) -> ModelLoadResult,
    loadDiffusion: suspend (LoadRequest) -> ModelLoadResult,
): ModelLoadResult = when (val resolution = resolve(model, mode)) {
    is InstalledModelLoadResolution.Ready -> when (mode) {
        GenerationMode.Text -> loadText(resolution.request)
        GenerationMode.Image,
        GenerationMode.Video,
        -> loadDiffusion(resolution.request)
    }
    InstalledModelLoadResolution.NeedsNetwork -> ModelLoadResult.Error(
        "Connect once to verify this installed model's metadata, then try again.",
    )
    is InstalledModelLoadResolution.NotAdmissible -> ModelLoadResult.Error(
        resolution.reason.safeInstalledModelMessage(),
    )
    is InstalledModelLoadResolution.Rejected -> ModelLoadResult.Error(
        "The installed model could not be verified.",
    )
    InstalledModelLoadResolution.Failed -> ModelLoadResult.Error(
        "The installed model could not be prepared right now. Try again.",
    )
}

internal fun AssessmentReason.safeInstalledModelMessage(): String = when (this) {
    AssessmentReason.MEMORY_NO_FIT ->
        "This model does not fit the current memory headroom. Try Auto KV cache or close other apps."
    AssessmentReason.INVALID_METADATA ->
        "This installed model's verified metadata is incomplete."
    AssessmentReason.UNKNOWN_ARCHITECTURE,
    AssessmentReason.GGUF_VERSION_UNKNOWN,
    AssessmentReason.ENGINE_SUPPORT_UNKNOWN,
    AssessmentReason.RECOMMENDATION_EVIDENCE_INCOMPLETE,
    -> "Compatibility evidence for this installed model is incomplete."
    AssessmentReason.UNSUPPORTED_ARCHITECTURE,
    AssessmentReason.UNSUPPORTED_FORMAT,
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
