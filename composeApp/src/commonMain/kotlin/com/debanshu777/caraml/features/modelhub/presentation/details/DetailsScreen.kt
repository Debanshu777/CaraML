package com.debanshu777.caraml.features.modelhub.presentation.details

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.rating.ui.RecommendationDetailsSheet
import com.debanshu777.caraml.core.rating.ui.recommendationPresentation
import com.debanshu777.caraml.core.ui.components.CaraMLTopBar
import com.debanshu777.caraml.core.ui.components.TopBarNavigation
import com.debanshu777.caraml.core.ui.layout.AppContentKind
import com.debanshu777.caraml.core.ui.layout.ResponsiveContentPane
import com.debanshu777.caraml.features.modelhub.presentation.details.components.ModelDetailContent
import com.debanshu777.caraml.features.modelhub.presentation.search.ModelHubBrowseMode
import com.debanshu777.caraml.features.modelhub.presentation.search.ModelViewModel

internal enum class ModelDetailLayout {
    Compact,
    SupportingPane,
}

internal fun modelDetailLayout(width: Dp): ModelDetailLayout = if (width >= 840.dp) {
    ModelDetailLayout.SupportingPane
} else {
    ModelDetailLayout.Compact
}

internal fun modelDetailsUseSupportingPane(width: Dp): Boolean =
    modelDetailLayout(width) == ModelDetailLayout.SupportingPane

@Composable
fun DetailsScreen(
    viewModel: ModelViewModel,
    modelId: String,
    hubBrowseMode: ModelHubBrowseMode,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val modelDetail by viewModel.modelDetail.collectAsState()
    val isDetailLoading by viewModel.isDetailLoading.collectAsState()
    val detailError by viewModel.detailError.collectAsState()
    val ggufFiles by viewModel.ggufFiles.collectAsState()
    val isDownloading by viewModel.isDownloading.collectAsState()
    val downloadError by viewModel.downloadError.collectAsState()
    val showDownloadForLaterConfirmation by viewModel.showDownloadForLaterConfirmation.collectAsState()
    val installBundleState by viewModel.installBundleState.collectAsState()
    val recommendations by viewModel.recommendedModels.collectAsState()
    val recommendationState = recommendations.firstOrNull { it.repositoryId == modelId }
    val snackbarHostState = remember { SnackbarHostState() }
    var recommendationSheetVisible by remember { mutableStateOf(false) }

    val isDiffusion = hubBrowseMode == ModelHubBrowseMode.DiffusionImage ||
        hubBrowseMode == ModelHubBrowseMode.DiffusionVideo

    LaunchedEffect(modelId, hubBrowseMode) {
        viewModel.loadDetail(modelId, hubBrowseMode)
    }

    LaunchedEffect(downloadError) {
        val error = downloadError ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(error)
        viewModel.clearDownloadError()
    }

    LaunchedEffect(installBundleState.installError) {
        val error = installBundleState.installError ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(error)
        viewModel.clearDownloadError()
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Color.Transparent,
        topBar = {
            CaraMLTopBar(
                title = "Model details",
                navigation = TopBarNavigation.Back,
                onNavigationClick = onBack,
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            val detailsWindowWidth = maxWidth
            ResponsiveContentPane(
                kind = AppContentKind.Details,
                modifier = Modifier.fillMaxSize(),
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    val detail = modelDetail
                    when {
                        isDetailLoading -> CircularProgressIndicator()
                        detailError != null -> Text(
                            text = detailError ?: "Could not load model details. Please try again.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        detail != null -> {
                            val (weightHeading, weightEmpty) = when (hubBrowseMode) {
                                ModelHubBrowseMode.LanguageModels ->
                                    "GGUF files" to "No GGUF files found"
                                ModelHubBrowseMode.DiffusionImage,
                                ModelHubBrowseMode.DiffusionVideo ->
                                    "Weight files" to
                                        "No weight files found (.gguf, .safetensors, .ckpt, .pth)"
                            }
                            ModelDetailContent(
                                model = detail,
                                ggufFiles = ggufFiles,
                                isDownloading = isDownloading,
                                onDownloadClick = { id, path, metadata ->
                                    viewModel.startDownload(id, path, metadata)
                                },
                                weightFilesHeading = weightHeading,
                                weightFilesEmptyLabel = weightEmpty,
                                installBundleState = installBundleState,
                                onVariantSelected = { path -> viewModel.selectVariant(path) },
                                onSmartInstall = { viewModel.smartInstall(modelId) },
                                showInstallBundle = isDiffusion,
                                recommendationState = recommendationState,
                                onRecommendationInfoClick = { recommendationSheetVisible = true },
                                modifier = Modifier.fillMaxSize(),
                                windowWidth = detailsWindowWidth,
                            )
                        }
                    }
                }
            }
        }
    }

    val personalized = recommendationState?.personalizedResult
    if (recommendationSheetVisible && personalized != null) {
        RecommendationDetailsSheet(
            modelId = modelId,
            recommendation = personalized,
            presentation = recommendationPresentation(
                personalized,
                recommendationState.selectedVariantName,
                recommendationState.workload,
            ),
            onDismiss = { recommendationSheetVisible = false },
        )
    }

    if (showDownloadForLaterConfirmation) {
        DownloadForLaterConfirmationDialog(
            onConfirm = viewModel::confirmDownloadForLater,
            onDismiss = viewModel::dismissDownloadForLater,
        )
    }
}
