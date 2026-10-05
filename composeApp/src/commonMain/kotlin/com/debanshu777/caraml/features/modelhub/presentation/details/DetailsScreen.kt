package com.debanshu777.caraml.features.modelhub.presentation.details

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.theme.AppTheme
import com.debanshu777.caraml.core.rating.ui.RecommendationDetailsSheet
import com.debanshu777.caraml.core.rating.ui.recommendationPresentation
import com.debanshu777.caraml.core.ui.components.BrandPageHeader
import com.debanshu777.caraml.core.ui.layout.AppContentKind
import com.debanshu777.caraml.core.ui.layout.ResponsiveContentPane
import com.debanshu777.caraml.features.modelhub.presentation.details.components.ModelDetailContent
import com.debanshu777.caraml.features.modelhub.presentation.details.components.ModelDetailsDevicePreview
import com.debanshu777.caraml.features.modelhub.presentation.search.ModelHubBrowseMode
import com.debanshu777.caraml.features.modelhub.presentation.search.ModelViewModel
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelHubStateKind
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelHubStateView
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelHubDeviceInfo
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelHubBackHeader

internal enum class ModelDetailLayout {
    Compact,
    SupportingPane,
}

internal fun modelDetailLayout(width: Dp): ModelDetailLayout = if (width >= AppTheme.dimensions.size840) {
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
    val activeDownloadArtifact by viewModel.activeDownloadArtifact.collectAsState()
    val downloadError by viewModel.downloadError.collectAsState()
    val showDownloadForLaterConfirmation by viewModel.showDownloadForLaterConfirmation.collectAsState()
    val installBundleState by viewModel.installBundleState.collectAsState()
    val recommendations by viewModel.recommendedModels.collectAsState()
    val recommendationState = recommendations.firstOrNull { it.repositoryId == modelId }
    val snackbarHostState = remember { SnackbarHostState() }
    var recommendationSheetVisible by remember { mutableStateOf(false) }
    var deviceInfoVisible by rememberSaveable(modelId) { mutableStateOf(false) }
    val storageInfo by viewModel.storageInfo.collectAsState()

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

    if (deviceInfoVisible) ModelHubDeviceInfo(
        storageInfo = storageInfo, profile = null,
        onBack = { deviceInfoVisible = false }, onRefresh = viewModel::refreshDeviceInfo,
        onOpenProfile = null, modifier = modifier,
        backLabel = "Model details", backContentDescription = "Back to model details",
    ) else Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            ResponsiveContentPane(
                kind = AppContentKind.Details,
                modifier = Modifier.fillMaxSize(),
            ) {
                Column(Modifier.fillMaxSize()) {
                    ModelHubBackHeader(onBack = onBack)
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        val detail = modelDetail
                        when {
                            isDetailLoading -> ModelDetailStatus(
                                kind = ModelHubStateKind.Loading,
                                message = "Loading model details",
                            )
                            detailError != null -> ModelDetailStatus(
                                kind = ModelHubStateKind.Error,
                                message = detailError ?: "Could not load model details. Please try again.",
                                onRetry = { viewModel.loadDetail(modelId, hubBrowseMode) },
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
                                    activeDownloadArtifact = activeDownloadArtifact,
                                    onDownloadClick = { id, path, metadata ->
                                        viewModel.startDownload(id, path, metadata)
                                    },
                                    onDownloadGroupClick = { id, metadata ->
                                        viewModel.startLanguageBundleDownload(id, metadata)
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
                                    onPauseDownload = viewModel::pauseDownload,
                                    onResumeDownload = viewModel::resumeDownload,
                                    onCancelDownload = viewModel::cancelDownload,
                                    onRetryDownload = viewModel::retryDownload,
                                    onOpenDeviceInfo = {
                                        viewModel.refreshDeviceInfo()
                                        deviceInfoVisible = true
                                    },
                                )
                            }
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

/** Recovery remains reachable in landscape and at large text sizes. */
@Composable
internal fun ModelDetailStatus(
    kind: ModelHubStateKind,
    message: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    ModelHubStateView(
        kind = kind,
        message = message,
        modifier = modifier.verticalScroll(rememberScrollState()),
        actionLabel = if (onRetry != null) "Retry" else null,
        onAction = onRetry,
    )
}

@Preview(name = "Artifact compact", widthDp = 412, heightDp = 915)
@Composable
private fun DetailsScreenCompactPreview() {
    ModelDetailsDevicePreview()
}

@Preview(name = "Artifact compact - 200%", widthDp = 360, heightDp = 800, fontScale = 2f)
@Composable
private fun DetailsScreenLargeTextPreview() {
    ModelDetailsDevicePreview()
}

@Preview(name = "Artifact desktop", widthDp = 1180, heightDp = 780)
@Composable
private fun DetailsScreenDesktopPreview() {
    ModelDetailsDevicePreview()
}
