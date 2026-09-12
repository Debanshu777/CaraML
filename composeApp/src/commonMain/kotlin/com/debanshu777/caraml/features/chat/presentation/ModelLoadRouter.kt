package com.debanshu777.caraml.features.chat.presentation

import com.debanshu777.caraml.core.data.inference.ModelLoadResult
import com.debanshu777.caraml.core.recommendation.LoadRequest
import com.debanshu777.caraml.core.recommendation.DiffusionMode
import com.debanshu777.caraml.core.recommendation.DiffusionRunPlan
import com.debanshu777.caraml.core.recommendation.LlmRunPlan
import com.debanshu777.caraml.core.recommendation.RecommendationRolloutMode
import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import com.debanshu777.caraml.features.chat.domain.GenerationMode

internal object ModelLoadRouter {
    suspend fun route(
        rolloutMode: RecommendationRolloutMode,
        model: LocalModelEntity,
        request: LoadRequest?,
        legacyLoad: suspend (LocalModelEntity) -> ModelLoadResult,
        exactLoad: suspend (LoadRequest) -> ModelLoadResult,
        expectedMode: GenerationMode? = null,
    ): ModelLoadResult = when (rolloutMode) {
        RecommendationRolloutMode.LEGACY -> legacyLoad(model)
        RecommendationRolloutMode.SHADOW,
        RecommendationRolloutMode.V2,
        -> if (request.matches(model, expectedMode)) {
            exactLoad(requireNotNull(request))
        } else {
            ModelLoadResult.Error(
                "This installed model needs to be reassessed before it can be loaded safely.",
            )
        }
    }

    private fun LoadRequest?.matches(model: LocalModelEntity, expectedMode: GenerationMode?): Boolean =
        this != null &&
            this.model.id == model.id &&
            this.model.modelId == model.modelId &&
            artifact?.identity == identity &&
            assessedPlans != null &&
            assessmentKey == assessedPlans.assessmentKey &&
            plan.stableKey in assessedPlans.values.map { it.plan.stableKey } &&
            plan.matches(expectedMode)

    private fun com.debanshu777.caraml.core.recommendation.RunPlan.matches(mode: GenerationMode?): Boolean =
        when (mode) {
            null -> true
            GenerationMode.Text -> this is LlmRunPlan
            GenerationMode.Image -> (this as? DiffusionRunPlan)?.mode == DiffusionMode.IMAGE
            GenerationMode.Video -> (this as? DiffusionRunPlan)?.mode == DiffusionMode.VIDEO
        }
}
