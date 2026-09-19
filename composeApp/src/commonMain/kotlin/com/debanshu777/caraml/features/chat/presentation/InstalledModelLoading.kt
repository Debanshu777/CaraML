package com.debanshu777.caraml.features.chat.presentation

import com.debanshu777.caraml.core.data.inference.ModelLoadResult
import com.debanshu777.caraml.core.recommendation.InstalledModelLoadResolution
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
        "This installed model is not admissible on this device.",
    )
    is InstalledModelLoadResolution.Rejected -> ModelLoadResult.Error(
        "The installed model could not be verified.",
    )
    InstalledModelLoadResolution.Failed -> ModelLoadResult.Error(
        "The installed model could not be prepared right now. Try again.",
    )
}
