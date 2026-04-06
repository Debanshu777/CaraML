package com.debanshu777.caraml.features.modelhub.presentation.search

import kotlinx.serialization.Serializable

@Serializable
enum class ModelHubBrowseMode {
    LanguageModels,
    DiffusionImage,
    DiffusionVideo,
}
