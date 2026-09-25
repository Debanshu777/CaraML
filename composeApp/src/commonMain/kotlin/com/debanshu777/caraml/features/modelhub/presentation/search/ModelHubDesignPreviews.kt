package com.debanshu777.caraml.features.modelhub.presentation.search

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import com.debanshu777.caraml.core.drawer.LocalNavigationMenuAction
import com.debanshu777.caraml.core.platform.DeviceHints
import com.debanshu777.caraml.core.rating.ui.RecommendationStatusChip
import com.debanshu777.caraml.core.recommendation.RecommendationProfile
import com.debanshu777.caraml.core.theme.CaraMLTheme
import com.debanshu777.caraml.core.theme.ThemePreferences
import com.debanshu777.caraml.core.ui.components.AuroraBackdrop
import com.debanshu777.caraml.features.modelhub.domain.DescriptorState
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelHubContextStrip
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelHubHeader
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelHubStateKind
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelHubStateView
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelHubToolbar
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelResultCard
import com.debanshu777.caraml.features.modelhub.presentation.search.components.SearchBar
import com.debanshu777.huggingfacemanager.model.ModelSort
import com.debanshu777.huggingfacemanager.model.ParameterRange
import androidx.compose.ui.tooling.preview.Preview

@Preview(name = "Models compact - populated", widthDp = 412, heightDp = 915)
@Composable
private fun ModelHubPopulatedPreview() {
    ModelHubDevicePreview(populated = true)
}

@Preview(name = "Models compact - empty", widthDp = 412, heightDp = 915)
@Composable
private fun ModelHubEmptyPreview() {
    ModelHubDevicePreview(populated = false)
}

@Preview(
    name = "Models compact - populated 200%",
    widthDp = 360,
    heightDp = 800,
    fontScale = 2f,
)
@Composable
private fun ModelHubPopulatedLargeTextPreview() {
    ModelHubDevicePreview(populated = true)
}

/** Device-state preview used to review the same dense and empty cases exercised by UI tests. */
@Composable
private fun ModelHubDevicePreview(populated: Boolean) {
    CaraMLTheme(ThemePreferences()) {
        CompositionLocalProvider(LocalNavigationMenuAction provides {}) {
            AuroraBackdrop {
                ModelHubScreenLayout(
                    selectedTabIndex = 0,
                    onTabSelected = {},
                    modifier = Modifier.fillMaxSize(),
                    discoverContent = {
                        ModelHubTabLayout(
                            modifier = Modifier.fillMaxSize(),
                            command = {
                                SearchBar(
                                    query = "",
                                    onQueryChange = {},
                                    onSearch = {},
                                )
                            },
                            context = {
                                ModelHubContextStrip(
                                    storageInfo = previewStorageInfo(),
                                    profile = RecommendationProfile(),
                                    onOpenProfile = {},
                                )
                            },
                            toolbar = {
                                ModelHubToolbar(
                                    browseMode = ModelHubBrowseMode.LanguageModels,
                                    onBrowseModeChange = {},
                                    showSortFilters = true,
                                    ordering = ModelOrdering.Server(ModelSort.TRENDING),
                                    sort = ModelSort.TRENDING,
                                    minParams = ParameterRange.ZERO,
                                    maxParams = ParameterRange.THREE_B,
                                    onSortChange = {},
                                    onOrderingChange = {},
                                    onMinParamsChange = {},
                                    onMaxParamsChange = {},
                                )
                            },
                            summary = {
                                ModelHubHeader(
                                    title = if (populated) "44,359 models" else "0 models",
                                    summary = if (populated) "1 filter active" else null,
                                    actionLabel = if (populated) "Reset filters" else null,
                                    onAction = if (populated) ({}) else null,
                                )
                            },
                            results = {
                                if (populated) {
                                    previewModels.forEach { (owner, title, metadata) ->
                                        item(key = title) {
                                            ModelResultCard(
                                                title = "$owner/$title",
                                                author = owner,
                                                metadata = metadata,
                                                status = {
                                                    RecommendationStatusChip(
                                                        state = DescriptorState.NEEDS_INFORMATION,
                                                        recommendation = null,
                                                    )
                                                },
                                                onClick = {},
                                            )
                                        }
                                    }
                                } else {
                                    item(key = "empty") {
                                        ModelHubStateView(
                                            kind = ModelHubStateKind.Empty,
                                            message = "No models are available yet.",
                                        )
                                    }
                                }
                            },
                        )
                    },
                    libraryContent = {},
                )
            }
        }
    }
}

private fun previewStorageInfo() = StorageInfoUiState(
    totalDeviceBytes = 228L * 1024 * 1024 * 1024,
    availableDeviceBytes = 197L * 1024 * 1024 * 1024,
    usedByModelsBytes = 0L,
    deviceHints = DeviceHints(
        performanceCoreCount = 4,
        totalCoreCount = 8,
        memoryBudgetMB = 1_920,
        gpuBackendAvailable = true,
    ),
)

private val previewModels = listOf(
    Triple(
        "HauhauCS",
        "Qwen3.8-27B-Uncensored-HauhauCS-Aggressive-MTP-GGUF",
        "image-text-to-text · 2.2M downloads",
    ),
    Triple(
        "openbmb",
        "MiniCPM5-2B-GGUF",
        "text-generation · 180.6K downloads",
    ),
    Triple(
        "tencent",
        "Hy-MT2-1.8B-GGUF",
        "text-generation · 375.4K downloads",
    ),
)
