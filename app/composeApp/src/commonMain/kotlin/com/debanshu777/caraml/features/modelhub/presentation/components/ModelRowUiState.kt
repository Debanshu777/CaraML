package com.debanshu777.caraml.features.modelhub.presentation.components

import androidx.compose.ui.graphics.vector.ImageVector

/** Presentation-only values: no persistence entities, network DTOs or callbacks. */
internal data class ModelRowUiState(
    val id: String,
    val title: String,
    val task: String?,
    val metrics: List<ModelRowMetric>,
    val statuses: List<ModelRowStatus>,
    val avatar: ModelRowAvatar? = null,
    val selectionMode: Boolean = false,
    val isSelected: Boolean = false,
    val canOpen: Boolean = true,
    val canRepair: Boolean = false,
    val recommendationAction: Boolean = false,
    val testTag: String? = null,
    val description: String = "Open model $id",
)
internal data class ModelRowAvatar(val url: String?, val author: String)
internal data class ModelRowMetric(val icon: ImageVector, val value: String, val description: String)
internal enum class ModelRowTone { Positive, Caution, Negative, Neutral }
internal data class ModelRowStatus(val label: String, val description: String, val icon: ImageVector, val tone: ModelRowTone)
