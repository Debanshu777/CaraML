package com.debanshu777.caraml.features.modelhub.presentation.search

import com.debanshu777.caraml.core.recommendation.LoadRequest
import com.debanshu777.caraml.core.recommendation.LoadRequestResolution
import com.debanshu777.caraml.core.recommendation.LocalArtifactIdentityResolver
import com.debanshu777.caraml.core.recommendation.ModelAssessment
import com.debanshu777.caraml.core.recommendation.ModelDescriptor
import com.debanshu777.caraml.core.recommendation.PersonalizedRecommendation
import com.debanshu777.caraml.core.recommendation.RecommendationRolloutMode
import com.debanshu777.caraml.core.storage.component.ComponentRepository
import com.debanshu777.caraml.core.storage.component.DownloadedComponentEntity
import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import com.debanshu777.caraml.features.modelhub.domain.DescriptorState
import com.debanshu777.caraml.features.modelhub.domain.RecommendedModelUiState

/** Carries the exact assessed artifact and selected plan into native load admission. */
class RecommendedModelLoadRequestResolver(
    private val componentRepository: ComponentRepository,
    private val artifactIdentityResolver: LocalArtifactIdentityResolver,
) {
    suspend fun resolve(
        model: LocalModelEntity,
        states: List<RecommendedModelUiState>,
    ): LoadRequest? = resolveSelectedModelLoadRequest(
        model = model,
        states = states,
        componentsForModel = componentRepository::getComponentsForModel,
        createRequest = artifactIdentityResolver::createLoadRequest,
    )
}

internal inline fun routeRecommendedModelSelection(
    mode: RecommendationRolloutMode,
    selectLegacy: () -> Unit,
    selectAssessed: () -> Unit,
) {
    when (mode) {
        RecommendationRolloutMode.LEGACY -> selectLegacy()
        RecommendationRolloutMode.SHADOW,
        RecommendationRolloutMode.V2,
        -> selectAssessed()
    }
}

internal suspend fun resolveSelectedModelLoadRequest(
    model: LocalModelEntity,
    states: List<RecommendedModelUiState>,
    componentsForModel: suspend (String) -> List<DownloadedComponentEntity>,
    createRequest: suspend (
        LocalModelEntity,
        List<DownloadedComponentEntity>,
        ModelDescriptor,
        ModelAssessment,
        PersonalizedRecommendation,
    ) -> LoadRequestResolution,
): LoadRequest? {
    val state = states.singleOrNull { candidate ->
        candidate.repositoryId == model.modelId && candidate.sourceModel.id == model.modelId
    } ?: return null
    if (state.descriptorState != DescriptorState.ASSESSED) return null
    val descriptor = state.selectedDescriptor ?: return null
    val assessment = state.objectiveAssessment ?: return null
    val recommendation = state.personalizedResult ?: return null
    if (descriptor.repositoryId != model.modelId ||
        assessment.assessmentKey.isBlank() ||
        recommendation.assessmentKey != assessment.assessmentKey
    ) {
        return null
    }
    val components = componentsForModel(model.modelId)
    return when (
        val resolution = createRequest(model, components, descriptor, assessment, recommendation)
    ) {
        is LoadRequestResolution.Ready -> resolution.request
        is LoadRequestResolution.Rejected -> null
    }
}
