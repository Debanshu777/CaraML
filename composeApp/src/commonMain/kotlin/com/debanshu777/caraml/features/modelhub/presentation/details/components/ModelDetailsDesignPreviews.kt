package com.debanshu777.caraml.features.modelhub.presentation.details.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.debanshu777.caraml.core.theme.CaraMLTheme
import com.debanshu777.caraml.core.theme.ThemePreferences
import com.debanshu777.caraml.core.ui.components.AuroraBackdrop
import com.debanshu777.caraml.core.ui.components.CaraMLTopBar
import com.debanshu777.caraml.core.ui.components.TopBarNavigation
import com.debanshu777.caraml.core.ui.layout.AppContentKind
import com.debanshu777.caraml.core.ui.layout.ResponsiveContentPane
import com.debanshu777.caraml.features.modelhub.presentation.search.GgufFileUiState
import com.debanshu777.huggingfacemanager.model.ModelDetailResponse
import androidx.compose.ui.tooling.preview.Preview

@Preview(name = "Artifact compact", widthDp = 412, heightDp = 915)
@Composable
private fun ModelDetailsCompactPreview() {
    ModelDetailsDevicePreview()
}

@Preview(
    name = "Artifact compact - 200%",
    widthDp = 360,
    heightDp = 800,
    fontScale = 2f,
)
@Composable
private fun ModelDetailsCompactLargeTextPreview() {
    ModelDetailsDevicePreview()
}

@Composable
private fun ModelDetailsDevicePreview() {
    CaraMLTheme(ThemePreferences()) {
        AuroraBackdrop {
            Scaffold(
                containerColor = Color.Transparent,
                topBar = {
                    CaraMLTopBar(
                        title = "Artifact",
                        navigation = TopBarNavigation.Back,
                        onNavigationClick = {},
                        contentKind = AppContentKind.Details,
                    )
                },
            ) { padding ->
                ResponsiveContentPane(
                    kind = AppContentKind.Details,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                ) {
                    ModelDetailContent(
                        model = previewDetailModel,
                        ggufFiles = listOf(
                            GgufFileUiState(
                                path = "Qwen3.8-27B-Uncensored-HauhauCS-Aggressive-FastMTP-32K.gguf",
                                filename = "Qwen3.8-27B-Uncensored-HauhauCS-Aggressive-FastMTP-32K.gguf",
                                sizeBytes = 861L * 1024 * 1024,
                                isDownloaded = false,
                                progress = null,
                            ),
                        ),
                        isDownloading = false,
                        onDownloadClick = { _, _, _ -> },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

private val previewDetailModel = ModelDetailResponse(
    modelId = "HauhauCS/Qwen3.8-27B-Uncensored-HauhauCS-Aggressive-MTP-GGUF",
    author = "HauhauCS",
    pipelineTag = "image-text-to-text",
    downloads = 2_192_290,
    likes = 1_324,
    createdAt = "2026-08-17T00:00:00.000Z",
    cardData = ModelDetailResponse.CardData(
        baseModel = listOf("Qwen/Qwen3.8-27B"),
        license = "apache-2.0",
        pipelineTag = "image-text-to-text",
    ),
)
