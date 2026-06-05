package com.debanshu777.caraml.core.navigation

import androidx.navigation3.runtime.NavKey
import com.debanshu777.caraml.features.modelhub.presentation.search.ModelHubBrowseMode
import kotlinx.serialization.Serializable

sealed interface AppScreen : NavKey {
    @Serializable
    data object Home : AppScreen

    @Serializable
    data object Search : AppScreen

    @Serializable
    data class Details(
        val modelId: String,
        val hubBrowseMode: ModelHubBrowseMode = ModelHubBrowseMode.LanguageModels,
    ) : AppScreen

    @Serializable
    data class Chat(val modelPath: String, val modelId: String) : AppScreen

    @Serializable
    data object Settings : AppScreen
}
