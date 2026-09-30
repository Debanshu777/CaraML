package com.debanshu777.caraml.features.modelhub.presentation.search

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import com.debanshu777.caraml.core.drawer.LocalNavigationMenuAction
import com.debanshu777.caraml.core.platform.DeviceHints
import com.debanshu777.caraml.core.recommendation.RecommendationProfile
import com.debanshu777.caraml.core.theme.CaraMLTheme
import com.debanshu777.caraml.core.theme.LocalSpacing
import com.debanshu777.caraml.core.theme.ThemePreferences
import com.debanshu777.caraml.core.ui.components.AuroraBackdrop
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelHubContextStrip
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelHubHeader
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelHubStateKind
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelHubStateView
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelHubToolbar
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelListItem
import com.debanshu777.caraml.features.modelhub.presentation.search.components.SearchBar
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelDownloadQueueRow
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelDownloadBatchControls
import com.debanshu777.caraml.core.download.DownloadArtifactRequest
import com.debanshu777.caraml.core.download.DownloadArtifactSnapshot
import com.debanshu777.caraml.core.download.DownloadArtifactState
import com.debanshu777.caraml.core.download.DownloadBatchSnapshot
import com.debanshu777.caraml.core.download.DownloadBatchState
import com.debanshu777.caraml.core.download.DownloadUserIntent
import com.debanshu777.caraml.core.recommendation.storage.EncodedModelEvidence
import com.debanshu777.caraml.core.recommendation.storage.InstalledEvidenceState
import com.debanshu777.huggingfacemanager.download.DownloadArtifactIdentity
import com.debanshu777.huggingfacemanager.download.DownloadMetadataDTO
import com.debanshu777.huggingfacemanager.model.ModelSort
import com.debanshu777.huggingfacemanager.model.ParameterRange
import com.debanshu777.huggingfacemanager.model.ListModelsResponse
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

@Preview(name = "Models desktop - populated", widthDp = 1180, heightDp = 780)
@Composable
private fun ModelHubDesktopPreview() {
    ModelHubDevicePreview(populated = true)
}

@Preview(name = "Download queue - unknown progress 200%", widthDp = 360, heightDp = 480, fontScale = 2f)
@Composable
internal fun ModelDownloadQueueLargeTextPreview() {
    val artifact = requireNotNull(DownloadArtifactIdentity.create(
        repositoryId = "sample/Long-Model-Name-GGUF",
        immutableRevision = "a".repeat(40),
        relativePath = "Long-Model-Name-Q4_K_M.gguf",
        remoteObjectId = null,
        expectedBytes = 4_000_000_000L,
    ))
    val request = DownloadArtifactRequest(
        metadata = DownloadMetadataDTO(artifact, "model", artifact.expectedBytes, null, null, null),
        primary = true,
    )
    val snapshot = DownloadArtifactSnapshot(
        artifactId = "preview-artifact",
        batchId = "preview-batch",
        request = request,
        state = DownloadArtifactState.RUNNING,
        userIntent = DownloadUserIntent.RUN,
        bytesReceived = 24_000_000L,
        expectedBytes = 0L,
    )
    val batch = DownloadBatchSnapshot(
        batchId = "preview-batch",
        ownerModelId = artifact.repositoryId,
        modelType = "LLM",
        displayName = "Long Model Name GGUF",
        state = DownloadBatchState.RUNNING,
        userIntent = DownloadUserIntent.RUN,
        artifacts = listOf(snapshot),
        evidence = EncodedModelEvidence(InstalledEvidenceState.REQUIRES_ENRICHMENT, 1, "", ""),
    )
    CaraMLTheme(ThemePreferences()) {
        androidx.compose.material3.Surface {
            androidx.compose.foundation.layout.Column {
                ModelDownloadQueueRow(batch, snapshot)
                ModelDownloadBatchControls(batch, {}, {}, {}, {})
            }
        }
    }
}

/** Device-state preview used to review the same dense and empty cases exercised by UI tests. */
@Composable
internal fun ModelHubDevicePreview(populated: Boolean) {
    CaraMLTheme(ThemePreferences()) {
        CompositionLocalProvider(LocalNavigationMenuAction provides {}) {
            AuroraBackdrop {
                ModelHubScreenLayout(
                    selectedTabIndex = 0,
                    onTabSelected = {},
                    sharedContext = {
                        ModelHubContextStrip(
                            storageInfo = previewStorageInfo(),
                            profile = RecommendationProfile(),
                            onOpenProfile = {},
                        )
                    },
                    modifier = Modifier.fillMaxSize(),
                    discoverContent = {
                        ModelHubTabLayout(
                            modifier = Modifier.fillMaxSize(),
                            command = {
                                androidx.compose.foundation.layout.Row(
                                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                ) {
                                    SearchBar(
                                        query = "",
                                        onQueryChange = {},
                                        onSearch = {},
                                        modifier = Modifier.weight(1f),
                                    )
                                    com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelHubFilterButton(
                                        mode = ModelHubBrowseMode.LanguageModels,
                                        ordering = ModelOrdering.Server(ModelSort.TRENDING),
                                        minParams = ParameterRange.ZERO,
                                        maxParams = ParameterRange.THREE_B,
                                        onApply = { _, _, _, _ -> },
                                    )
                                }
                            },
                            context = {},
                            toolbar = {},
                            summary = {
                                ModelHubHeader(
                                    title = if (populated) "3 models loaded" else "0 models loaded",
                                    summary = if (populated) "1 filter active" else null,
                                    actionLabel = if (populated) "Reset filters" else null,
                                    onAction = if (populated) ({}) else null,
                                    modifier = Modifier.padding(horizontal = LocalSpacing.current.l),
                                )
                            },
                            results = {
                                if (populated) {
                                    previewModels.forEach { model ->
                                        item(key = requireNotNull(model.id)) {
                                            ModelListItem(model = model, onClick = {})
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
    ListModelsResponse.Model(
        author = "HauhauCS",
        id = "HauhauCS/Qwen3.8-27B-Uncensored-HauhauCS-Aggressive-MTP-GGUF",
        pipelineTag = "image-text-to-text",
        downloads = 2_200_000,
    ),
    ListModelsResponse.Model(
        author = "openbmb",
        id = "openbmb/MiniCPM5-2B-GGUF",
        pipelineTag = "text-generation",
        downloads = 180_600,
    ),
    ListModelsResponse.Model(
        author = "tencent",
        id = "tencent/Hy-MT2-1.8B-GGUF",
        pipelineTag = "text-generation",
        downloads = 375_400,
    ),
)
