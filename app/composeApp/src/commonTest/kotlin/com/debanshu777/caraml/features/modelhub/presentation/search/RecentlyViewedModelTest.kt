package com.debanshu777.caraml.features.modelhub.presentation.search

import com.debanshu777.caraml.features.modelhub.presentation.search.components.effectiveModelFilterGroupCount
import com.debanshu777.caraml.sdk.discovery.huggingface.model.ModelDetailResponse
import com.debanshu777.caraml.core.data.settings.RecentlyViewedModel
import com.debanshu777.caraml.core.data.settings.prependRecentlyViewed
import kotlin.test.Test
import kotlin.test.assertEquals

class RecentlyViewedModelTest {
    @Test
    fun historyMovesRepeatVisitsToFrontAndDeduplicatesByModeAndModelId() {
        val first = ModelDetailResponse(id = "Org/Model").recentlyViewed(ModelHubBrowseMode.LanguageModels)
        val second = ModelDetailResponse(modelId = "Org/Model").recentlyViewed(ModelHubBrowseMode.DiffusionImage)
        val revisited = ModelDetailResponse(modelId = "org/model", author = "Org").recentlyViewed(ModelHubBrowseMode.LanguageModels)

        assertEquals(listOf(revisited, second), prependRecentlyViewed(listOf(first, second), revisited))
    }

    @Test
    fun historyIsBounded() {
        val visits = (1..30).map { id ->
            ModelDetailResponse(id = "org/$id").recentlyViewed(ModelHubBrowseMode.LanguageModels)
        }

        assertEquals(20, visits.drop(10).fold(emptyList<RecentlyViewedModel>()) { history, visit ->
            prependRecentlyViewed(history, visit)
        }.size)
    }

    @Test
    fun historyDisplayCapsAtFourMostRecentEntries() {
        val visits = (1..6).map { id ->
            ModelDetailResponse(id = "org/$id").recentlyViewed(ModelHubBrowseMode.LanguageModels)
        }

        assertEquals(visits.take(4), recentlyViewedModelsForDisplay(visits))
    }

    @Test
    fun effectiveFilterCountIncludesTypeFormatAndGgufSizeButExcludesSort() {
        assertEquals(3, effectiveModelFilterGroupCount(ModelHubBrowseMode.LanguageModels, "gguf"))
        assertEquals(2, effectiveModelFilterGroupCount(ModelHubBrowseMode.LanguageModels, "onnx"))
        assertEquals(1, effectiveModelFilterGroupCount(ModelHubBrowseMode.DiffusionImage, "gguf"))
        assertEquals(1, effectiveModelFilterGroupCount(ModelHubBrowseMode.DiffusionVideo, "onnx"))
    }
}
