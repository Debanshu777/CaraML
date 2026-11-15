package com.debanshu777.caraml.features.modelhub.presentation.components

import com.debanshu777.caraml.core.download.diffusion.DIFFUSERS_BUNDLE_DB_FILENAME
import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import com.debanshu777.caraml.core.ui.icons.AppIcons
import com.debanshu777.caraml.core.recommendation.BrowseResourceFit
import com.debanshu777.caraml.core.recommendation.RecommendationCategory
import com.debanshu777.caraml.features.modelhub.domain.DescriptorState
import com.debanshu777.caraml.features.modelhub.domain.RecommendedModelUiState
import com.debanshu777.caraml.features.modelhub.presentation.search.components.*
import com.debanshu777.caraml.sdk.discovery.huggingface.model.ListModelsResponse
import com.debanshu777.caraml.sdk.discovery.huggingface.model.PipelineTag
import kotlin.math.roundToInt

internal fun discoveryModelRow(model: ListModelsResponse.Model?, recommendationState: RecommendedModelUiState? = null, downloadedVariantCount: Int = 0): ModelRowUiState? {
    model ?: return null
    val id = model.id ?: "Unknown"
    val state = recommendationState?.descriptorState ?: DescriptorState.NEEDS_INFORMATION
    val recommendation = recommendationState?.personalizedResult
    val presentation = recommendationStatusPresentation(state, recommendation)
    val fit = recommendationState?.browseFit
    val browse = fit?.let(::browseVerdict)
    val tone = if (fit != null) when (fit.memoryFit) {
        BrowseResourceFit.LIKELY_FIT -> ModelRowTone.Positive
        BrowseResourceFit.TIGHT_FIT -> ModelRowTone.Caution
        BrowseResourceFit.TOO_LARGE -> ModelRowTone.Negative
        BrowseResourceFit.UNKNOWN -> ModelRowTone.Neutral
    } else if (state != DescriptorState.ASSESSED) ModelRowTone.Neutral else when (recommendation?.category) {
        RecommendationCategory.RECOMMENDED, RecommendationCategory.USABLE -> ModelRowTone.Positive
        RecommendationCategory.RISKY -> ModelRowTone.Caution
        RecommendationCategory.NOT_SUITABLE, RecommendationCategory.INCOMPATIBLE -> ModelRowTone.Negative
        else -> ModelRowTone.Neutral
    }
    return ModelRowUiState(
        id = id, title = id, task = model.pipelineTag,
        avatar = ModelRowAvatar(model.authorData?.avatarUrl, model.author?.takeIf(String::isNotBlank) ?: id.substringBefore('/', "")),
        metrics = buildList {
            model.numParameters?.let { add(ModelRowMetric(AppIcons.Compute, formatCompactMetric(it), "$it parameters")) }
            model.downloads?.let { add(ModelRowMetric(AppIcons.Download, formatCompactMetric(it.toLong()), "$it downloads")) }
            model.likes?.let { add(ModelRowMetric(AppIcons.Favorite, formatCompactMetric(it.toLong()), "$it likes")) }
        },
        statuses = buildList {
            if (downloadedVariantCount > 0) {
                val label = if (downloadedVariantCount == 1) "1 variant downloaded" else "$downloadedVariantCount variants downloaded"
                add(ModelRowStatus(label, label, AppIcons.Check, ModelRowTone.Neutral))
            }
            add(ModelRowStatus(browse?.label ?: presentation.label, browse?.description ?: presentation.stateDescription, browse?.icon ?: presentation.icon, tone))
        },
        recommendationAction = true, testTag = "model-row:$id",
    )
}

internal fun downloadedModelRow(model: LocalModelEntity, selectionMode: Boolean = false, isSelected: Boolean = false): ModelRowUiState {
    val supported = PipelineTag.isSupported(model.pipelineTag) || model.filename == DIFFUSERS_BUNDLE_DB_FILENAME ||
        listOf(".gguf", ".safetensors", ".ckpt", ".pth").any { model.filename.endsWith(it, ignoreCase = true) }
    val partial = model.componentStatus == LocalModelEntity.STATUS_PARTIAL
    return ModelRowUiState(
        id = model.modelId, title = model.modelId, task = model.pipelineTag,
        metrics = model.sizeBytes?.let { listOf(ModelRowMetric(AppIcons.Storage, formatSize(it), "${formatSize(it)} on disk")) } ?: emptyList(),
        statuses = buildList {
            if (partial) add(ModelRowStatus("Needs setup", "Partial download. Missing components need setup.", AppIcons.Warning, ModelRowTone.Caution))
            if (model.componentStatus == LocalModelEntity.STATUS_READY && supported) add(ModelRowStatus("Ready", "Ready for chat.", AppIcons.CheckCircle, ModelRowTone.Positive))
            if (!supported) add(ModelRowStatus("Unsupported", "Unsupported. Chat is not available for this model type.", AppIcons.Block, ModelRowTone.Neutral))
        },
        selectionMode = selectionMode, isSelected = isSelected, canOpen = supported, canRepair = partial && !selectionMode,
        description = if (!selectionMode) "Open model ${model.modelId}" else if (isSelected) "Selected ${model.modelId}, tap to deselect" else "Not selected ${model.modelId}, tap to select",
    )
}

private fun formatSize(bytes: Long): String {
    fun formatDecimal(value: Double): String {
        val intPart = value.toLong()
        val fracPart = ((value - intPart) * 100).roundToInt().coerceIn(0, 99)
        return "$intPart.${fracPart.toString().padStart(2, '0')}"
    }
    return when {
        bytes >= 1_073_741_824 -> "${formatDecimal(bytes / 1_073_741_824.0)} GB"
        bytes >= 1_048_576 -> "${formatDecimal(bytes / 1_048_576.0)} MB"
        bytes >= 1_024 -> "${formatDecimal(bytes / 1_024.0)} KB"
        else -> "$bytes B"
    }
}
