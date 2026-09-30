package com.debanshu777.caraml.features.modelhub.presentation.search

import com.debanshu777.caraml.features.modelhub.domain.DescriptorState
import com.debanshu777.caraml.features.modelhub.domain.RecommendedModelUiState
import com.debanshu777.huggingfacemanager.api.ModelPageCursor
import com.debanshu777.huggingfacemanager.model.ListModelsResponse
import com.debanshu777.huggingfacemanager.model.ModelSort
import com.debanshu777.huggingfacemanager.model.ParameterRange

data class ModelQueryKey(
    val mode: ModelHubBrowseMode,
    val committedSearch: String?,
    val remoteSort: ModelSort,
    val minParams: ParameterRange,
    val maxParams: ParameterRange,
)

data class ModelHubResultsState(
    val key: ModelQueryKey? = null,
    val models: List<ListModelsResponse.Model> = emptyList(),
    val nextCursor: ModelPageCursor? = null,
    val totalCount: Int? = null,
    val initialLoading: Boolean = false,
    val moreLoading: Boolean = false,
    val initialError: String? = null,
    val inputError: String? = null,
    val moreError: String? = null,
    val sessionLimitReached: Boolean = false,
    val recommendations: List<RecommendedModelUiState> = emptyList(),
    val modelOrdering: ModelOrdering = ModelOrdering.Server(ModelSort.TRENDING),
) {
    val hasMore: Boolean get() = nextCursor != null && !sessionLimitReached
    val paginationStopped: Boolean get() = nextCursor == null && moreError != null && models.isNotEmpty()
    val canLoadMore: Boolean get() = hasMore && !initialLoading && !moreLoading
    val recommendationById: Map<String, RecommendedModelUiState> by lazy(LazyThreadSafetyMode.NONE) {
        recommendations.mapNotNull { recommendation ->
            recommendation.repositoryId?.let { it to recommendation }
        }.toMap()
    }
    val orderedModels: List<ListModelsResponse.Model> by lazy(LazyThreadSafetyMode.NONE) {
        if (modelOrdering != ModelOrdering.Personalized) models else {
            val byId = models.mapNotNull { model -> model.id?.let { it to model } }.toMap()
            val recommended = recommendations.mapNotNull { byId[it.repositoryId] }
            val recommendedIds = recommended.mapNotNullTo(mutableSetOf()) { it.id }
            recommended + models.filter { it.id !in recommendedIds }
        }
    }
    val assessedCount: Int
        get() = recommendations.count {
            it.descriptorState in setOf(
                DescriptorState.ASSESSED,
                DescriptorState.SELECT_VARIANT,
                DescriptorState.NEEDS_INFORMATION,
            )
        }.coerceAtMost(96)
    val canAssessMore: Boolean
        get() = assessedCount < 96 && recommendations.any {
            it.descriptorState == DescriptorState.PENDING
        }
}
