package com.debanshu777.caraml.features.modelhub.presentation.search.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.debanshu777.caraml.core.recommendation.RecommendationProfile
import com.debanshu777.caraml.features.modelhub.presentation.search.StorageInfoUiState

/** Source-compatible bridge while [ModelHubContextStrip] replaces the old overview card. */
@Composable
fun ModelHubOverview(
    storageInfo: StorageInfoUiState,
    profile: RecommendationProfile?,
    onOpenProfile: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    ModelHubContextStrip(
        storageInfo = storageInfo,
        profile = profile,
        onOpenProfile = onOpenProfile,
        modifier = modifier,
    )
}
