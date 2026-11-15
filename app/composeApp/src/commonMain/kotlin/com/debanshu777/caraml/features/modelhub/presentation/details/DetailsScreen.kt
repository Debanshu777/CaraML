package com.debanshu777.caraml.features.modelhub.presentation.details

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import com.debanshu777.caraml.core.theme.AppTheme
import com.debanshu777.caraml.core.rating.ui.RecommendationDetailsSheet
import com.debanshu777.caraml.core.rating.ui.recommendationPresentation
import com.debanshu777.caraml.core.ui.components.FrostedPageScaffold
import com.debanshu777.caraml.core.ui.layout.AppContentKind
import com.debanshu777.caraml.core.ui.layout.LocalContentStartInset
import com.debanshu777.caraml.core.ui.layout.LocalContentEndInset
import com.debanshu777.caraml.core.ui.motion.LocalAuroraMotionPolicy
import com.debanshu777.caraml.features.modelhub.presentation.details.components.rememberModelDetailTabsState
import com.debanshu777.caraml.features.modelhub.presentation.details.components.ModelDetailTabsBar
import kotlinx.coroutines.launch
import com.debanshu777.caraml.features.modelhub.presentation.details.components.ModelDetailContent
import com.debanshu777.caraml.features.modelhub.presentation.details.components.ModelDetailsHeader
import com.debanshu777.caraml.features.modelhub.presentation.details.components.ModelDetailsDevicePreview
import com.debanshu777.caraml.features.modelhub.presentation.search.ModelHubBrowseMode
import com.debanshu777.caraml.features.modelhub.presentation.search.ModelViewModel
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelHubStateKind
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelHubStateView

/** Wide layouts keep the diffusion install action within its tab; compact layouts pin it below. */
internal fun modelDetailsUseInlineInstallAction(width: Dp): Boolean = width >= AppTheme.dimensions.size840

@Composable
@OptIn(ExperimentalFoundationApi::class)
fun DetailsScreen(
    viewModel: ModelViewModel,
    modelId: String,
    hubBrowseMode: ModelHubBrowseMode,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    preview: ModelDetailsPreview? = null,
) {
    val modelDetail by viewModel.modelDetail.collectAsState()
    val modelCardState by viewModel.modelCardState.collectAsState()
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
    var titleBottom by remember(modelId) { mutableFloatStateOf(Float.NaN) }
    var headerBottom by remember(modelId) { mutableFloatStateOf(Float.NaN) }
    val showHeaderTitle by remember(modelId) {
        derivedStateOf { titleBottom.isFinite() && headerBottom.isFinite() && titleBottom <= headerBottom }
    }
    val headerBottomPaddingPx = with(LocalDensity.current) { AppTheme.spacing.spacing12.toPx() }
    val tabsState = key(modelId, hubBrowseMode) { rememberModelDetailTabsState(sticky = true) }
    val scope = rememberCoroutineScope()
    val motion = LocalAuroraMotionPolicy.current
    val parentBringIntoView = LocalBringIntoViewSpec.current
    val bringIntoView = remember(tabsState, parentBringIntoView) {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
                if (tabsState.restoringFocus) 0f else parentBringIntoView.calculateScrollDistance(offset, size, containerSize)
        }
    }
    val pinned = tabsState.pinned
    // Capture focus before movable content detaches. Restore only a tab that owned
    // keyboard focus, so ordinary touch scrolling never steals it from the reader.
    val tabFocusToRestore = remember(tabsState, pinned) { tabsState.focusedTab }
    LaunchedEffect(tabsState, pinned) {
        if (tabFocusToRestore >= 0) {
            tabsState.restoringFocus = true
            try {
                tabsState.focusRequesters[tabFocusToRestore].requestFocus()
                // Let the relocation's focus event finish without bringing the inline
                // row back into view and undoing the user's vertical scroll.
                withFrameNanos { }
            } finally {
                tabsState.restoringFocus = false
            }
        }
    }
    val tabRow = remember(tabsState, motion) {
        movableContentOf {
            ModelDetailTabsBar(tabsState,
                onSelect = { index -> scope.launch { tabsState.select(index, motion.spatialTransitionsEnabled) } },
                startInset = LocalContentStartInset.current, endInset = LocalContentEndInset.current,
                modifier = Modifier.onSizeChanged { tabsState.height = it.height },
            )
        }
    }

    val isDiffusion = hubBrowseMode == ModelHubBrowseMode.DiffusionImage ||
        hubBrowseMode == ModelHubBrowseMode.DiffusionVideo

    var routeStarted by remember(modelId, hubBrowseMode) {
        mutableStateOf(viewModel.hasDetailRequest(modelId, hubBrowseMode))
    }
    LaunchedEffect(modelId, hubBrowseMode) {
        viewModel.ensureDetail(modelId, hubBrowseMode)
        routeStarted = true
    }

    val downloadControlError by viewModel.downloadControlError.collectAsState()
    LaunchedEffect(downloadControlError) {
        val message = downloadControlError ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.clearDownloadControlError()
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

    CompositionLocalProvider(LocalBringIntoViewSpec provides bringIntoView) {
        FrostedPageScaffold(
            kind = AppContentKind.Details,
            modifier = modifier,
            header = {
                ModelDetailsHeader(modelId, showHeaderTitle, onBack,
                    modifier = Modifier.onGloballyPositioned {
                        headerBottom = it.positionInRoot().y + it.size.height + headerBottomPaddingPx
                    },
                )
            },
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            stickyHeader = if (pinned) tabRow else null,
        ) { insets ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val detail = modelDetail?.takeIf { routeStarted && (it.modelId ?: it.id).equals(modelId, ignoreCase = true) }
                val displayPreview = preview?.takeIf { it.modelId.equals(modelId, ignoreCase = true) }
                val (weightHeading, weightEmpty) = when (hubBrowseMode) {
                    ModelHubBrowseMode.LanguageModels ->
                        "Variants" to "No supported GGUF or ONNX bundles found"
                    ModelHubBrowseMode.DiffusionImage,
                    ModelHubBrowseMode.DiffusionVideo ->
                        "Variants" to
                            "No weight files found (.gguf, .safetensors, .ckpt, .pth)"
                }
                ModelDetailContent(
                    model = detail ?: (displayPreview ?: ModelDetailsPreview(modelId)).asDisplayModel(),
                    modelCardState = if (detail != null) modelCardState else ModelCardUiState.Loading,
                    onRetryModelCard = viewModel::retryModelCard,
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
                    contentPadding = PaddingValues(
                        top = insets.calculateTopPadding() + AppTheme.spacing.spacing12,
                        bottom = insets.calculateBottomPadding() + AppTheme.spacing.spacing16,
                    ),
                    onPauseDownload = viewModel::pauseDownload,
                    onResumeDownload = viewModel::resumeDownload,
                    onCancelDownload = viewModel::cancelDownload,
                    onRetryDownload = viewModel::retryDownload,
                    avatarUrl = displayPreview?.avatarUrl,
                    transitionKey = displayPreview?.transitionKey,
                    isDetailLoading = !routeStarted || isDetailLoading || (detail == null && detailError == null),
                    detailError = detailError.takeIf { routeStarted },
                    onRetryDetail = { viewModel.loadDetail(modelId, hubBrowseMode) },
                    onTitleBottomChanged = { titleBottom = it },
                    tabsState = tabsState,
                    stickyTopInset = insets.calculateTopPadding(),
                    tabRow = tabRow,
                )
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
