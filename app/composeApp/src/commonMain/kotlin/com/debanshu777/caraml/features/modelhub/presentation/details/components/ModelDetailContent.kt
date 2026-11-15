package com.debanshu777.caraml.features.modelhub.presentation.details.components

import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import com.debanshu777.caraml.core.ui.layout.LocalContentStartInset
import com.debanshu777.caraml.core.ui.motion.LocalAuroraMotionPolicy
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelHubStateView
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelHubStateKind
import com.debanshu777.caraml.core.ui.layout.LocalContentEndInset
import com.debanshu777.caraml.core.theme.AppTheme
import com.debanshu777.caraml.core.download.DownloadArtifactState
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelRecommendationStatus
import com.debanshu777.caraml.core.recommendation.DiffusionModelDescriptor
import com.debanshu777.caraml.core.recommendation.LlmModelDescriptor
import com.debanshu777.caraml.core.recommendation.ModelDescriptor
import com.debanshu777.caraml.core.recommendation.ModelFileIdentity
import com.debanshu777.caraml.core.recommendation.RecommendationCategory
import com.debanshu777.caraml.core.theme.CaraMLTheme
import com.debanshu777.caraml.core.theme.ThemePreferences
import com.debanshu777.caraml.features.modelhub.domain.DescriptorState
import com.debanshu777.caraml.features.modelhub.domain.ModelArtifactClassifier
import com.debanshu777.caraml.features.modelhub.domain.ModelArtifactRole
import com.debanshu777.caraml.features.modelhub.domain.RecommendedModelUiState
import com.debanshu777.caraml.features.modelhub.domain.matchesExactBrowseGroup
import com.debanshu777.caraml.features.modelhub.domain.projectBrowseSelection
import com.debanshu777.caraml.features.modelhub.presentation.details.ModelCardUiState
import com.debanshu777.caraml.features.modelhub.presentation.details.modelDetailsUseInlineInstallAction
import com.debanshu777.caraml.features.modelhub.presentation.search.GgufFileUiState
import com.debanshu777.caraml.features.modelhub.presentation.search.InstallBundleUiState
import com.debanshu777.caraml.core.download.transfer.DownloadArtifactIdentity
import com.debanshu777.caraml.core.download.transfer.DownloadMetadataDTO
import com.debanshu777.caraml.sdk.discovery.huggingface.model.ModelDetailResponse
import com.debanshu777.caraml.core.download.diffusion.getModelSetup

private val durableArtifactControlStates = setOf(
    DownloadArtifactState.QUEUED,
    DownloadArtifactState.RUNNING,
    DownloadArtifactState.PAUSED,
    DownloadArtifactState.WAITING_FOR_NETWORK,
    DownloadArtifactState.FAILED_RETRYABLE,
)

@Composable
fun ModelDetailContent(
    model: ModelDetailResponse?,
    ggufFiles: List<GgufFileUiState>,
    isDownloading: Boolean,
    onDownloadClick: (String, String, DownloadMetadataDTO) -> Unit,
    onDownloadGroupClick: (String, List<DownloadMetadataDTO>) -> Unit = { _, _ -> },
    activeDownloadArtifact: DownloadArtifactIdentity? = null,
    weightFilesHeading: String = "Variants",
    weightFilesEmptyLabel: String = "No GGUF files found",
    // Diffusion-specific
    installBundleState: InstallBundleUiState = InstallBundleUiState(),
    onVariantSelected: (String) -> Unit = {},
    onSmartInstall: () -> Unit = {},
    showInstallBundle: Boolean = false,
    recommendationState: RecommendedModelUiState? = null,
    onRecommendationInfoClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    windowWidth: Dp? = null,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    modelCardExcerpt: String? = null,
    modelCardState: ModelCardUiState = ModelCardUiState.Missing,
    onRetryModelCard: () -> Unit = {},
    onPauseDownload: (String, String) -> Unit = { _, _ -> },
    onResumeDownload: (String, String) -> Unit = { _, _ -> },
    onCancelDownload: (String, String) -> Unit = { _, _ -> },
    onRetryDownload: (String, String) -> Unit = { _, _ -> },
    avatarUrl: String? = null,
    transitionKey: String? = null,
    isDetailLoading: Boolean = false,
    detailError: String? = null,
    onRetryDetail: () -> Unit = {},
    onTitleBottomChanged: (Float) -> Unit = {},
    tabsState: ModelDetailTabsState? = null,
    stickyTopInset: Dp = 0.dp,
    tabRow: (@Composable () -> Unit)? = null,
) {
    if (model == null) return
    val scheme = AppTheme.colors
    // The branded wash needs more secondary-text contrast than a flat light canvas.
    MaterialTheme(
        colorScheme = if (scheme.background.luminance() < .5f) scheme else scheme.copy(
            onSurfaceVariant = lerp(scheme.onSurfaceVariant, scheme.onSurface, .16f),
        )
    ) {
        val modelId = model.modelId ?: model.id ?: ""
        val modelSetup = if (modelId.isNotBlank()) getModelSetup(modelId) else null
        val recommendedVariant = recommendedVariantPath(
            recommendationState?.selectedDescriptor,
            installBundleState.variants,
        )
        val browseSelection = recommendationState?.browseVariants?.let {
            projectBrowseSelection(it, installBundleState.selectedVariantPath)
        }
        val installEnabled = recommendedVariant != null &&
                installBundleState.selectedVariantPath == recommendedVariant
        // Submit every immutable member of the selected group. Filename-only local state can
        // refer to an older revision; the download coordinator performs exact-object reuse.
        val durableControl = installBundleState.durableControl
        val spacing = AppTheme.spacing
        val pending = isDetailLoading || detailError != null
        val overview: @Composable () -> Unit = {
            ModelOverviewSection(
                model = model,
                description = (modelCardState as? ModelCardUiState.Ready)?.document?.excerpt ?: modelCardExcerpt ?: modelSetup?.description,
                onTitleBottomChanged = onTitleBottomChanged,
                avatarUrl = avatarUrl,
                transitionKey = transitionKey,
                fitStatus = if (pending) null else ({
                    ModelRecommendationStatus(
                        state = recommendationState?.descriptorState
                            ?: DescriptorState.NEEDS_INFORMATION,
                        recommendation = recommendationState?.personalizedResult,
                        onInfoClick = onRecommendationInfoClick.takeIf { recommendationState?.personalizedResult != null },
                        browseFit = browseSelection?.estimate ?: recommendationState?.browseFit,
                        plain = true,
                        modifier = Modifier.testTag("detail-fit"),
                    )
                }),

            )
        }

        val pendingContent: @Composable () -> Unit = {
            ModelHubStateView(
                kind = if (detailError == null) ModelHubStateKind.Loading else ModelHubStateKind.Error,
                message = detailError ?: "Loading model details",
                modifier = Modifier.fillMaxWidth(),
                actionLabel = if (detailError == null) null else "Retry",
                onAction = if (detailError == null) null else onRetryDetail,
            )
        }
        BoxWithConstraints(modifier = modifier.fillMaxSize()) {
            val inlineInstallAction = modelDetailsUseInlineInstallAction(windowWidth ?: maxWidth)
            val direction = LocalLayoutDirection.current
            val startInset = LocalContentStartInset.current + contentPadding.calculateStartPadding(direction)
            val endInset = LocalContentEndInset.current + contentPadding.calculateEndPadding(direction)
            val motion = LocalAuroraMotionPolicy.current
            key(modelId) {
                val state = tabsState ?: rememberModelDetailTabsState()
                val pagerState = state.pager
                val scope = rememberCoroutineScope()
                val density = LocalDensity.current
                var overviewHeight by remember { mutableIntStateOf(0) }
                var viewportHeight by remember { mutableIntStateOf(0) }
                val topBeforeTabs = contentPadding.calculateTopPadding() + spacing.spacing12 - stickyTopInset
                SideEffect {
                    state.pinOffset = with(density) { topBeforeTabs.roundToPx() } + overviewHeight
                    state.available = !pending && overviewHeight > 0
                }
                var previousPage by remember { mutableIntStateOf(pagerState.settledPage) }
                // A drag can target another page and then be cancelled. Preserve the reading
                // position until a different page has actually settled.
                LaunchedEffect(pagerState.settledPage) {
                    if (pagerState.settledPage != previousPage) {
                        previousPage = pagerState.settledPage
                        if (state.pinned) state.scroll.scrollTo(state.pinOffset)
                    }
                }
                val pageMinHeight = if (tabsState == null) 120.dp else with(density) {
                    (viewportHeight - state.height).toDp() - stickyTopInset - contentPadding.calculateBottomPadding()
                }.coerceAtLeast(120.dp)
                Column(Modifier.fillMaxSize()) {
                    // One vertical scroller keeps long descriptions and either tab reachable at large text sizes.
                    Column(
                        Modifier.weight(1f).fillMaxWidth()
                            .onSizeChanged { viewportHeight = it.height }
                            .verticalScroll(state.scroll).testTag("detail-scroll")
                            .padding(top = contentPadding.calculateTopPadding(), bottom = contentPadding.calculateBottomPadding()),
                        verticalArrangement = Arrangement.spacedBy(spacing.spacing12),
                    ) {
                        Box(Modifier.padding(start = startInset, end = endInset)
                            .onSizeChanged { overviewHeight = it.height }) { overview() }
                        if (pending) {
                            Box(Modifier.padding(start = startInset, end = endInset)) { pendingContent() }
                        } else {
                            Column(Modifier.fillMaxWidth()) {
                                if (state.pinned) {
                                    Spacer(Modifier.fillMaxWidth().height(with(density) { state.height.toDp() }))
                                } else {
                                    Box(Modifier.onSizeChanged { state.height = it.height }) {
                                        if (tabRow != null) tabRow() else ModelDetailTabsBar(state,
                                            onSelect = { index -> scope.launch { state.select(index, motion.spatialTransitionsEnabled) } },
                                            startInset = startInset, endInset = endInset,
                                            variantsLabel = weightFilesHeading,
                                        )
                                    }
                                }
                                HorizontalPager(
                                    state = pagerState,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = pageMinHeight).testTag("detail-pager"),
                                    verticalAlignment = Alignment.Top,
                                ) { page ->
                                    if (page == 0) {
                                        if (showInstallBundle) {
                                            Column(Modifier.padding(start = startInset, top = spacing.spacing8)) {
                                                if (inlineInstallAction) {
                                                    InstallBundleCard(
                                                        modelId = modelId, state = installBundleState,
                                                        familyLabel = modelSetup?.familyLabel, modelDescription = null,
                                                        onVariantSelected = onVariantSelected, onInstall = onSmartInstall,
                                                        modifier = Modifier.testTag("detail-files"),
                                                        contentEndPadding = endInset, showVariantsHeading = false,
                                                        recommendedVariantPath = recommendedVariant, installEnabled = installEnabled,
                                                        durableControl = durableControl,
                                                        onPause = { durableControl?.let { onPauseDownload(it.batchId, it.artifactId) } },
                                                        onResume = { durableControl?.let { onResumeDownload(it.batchId, it.artifactId) } },
                                                        onCancel = { durableControl?.let { onCancelDownload(it.batchId, it.artifactId) } },
                                                        onRetry = { durableControl?.let { onRetryDownload(it.batchId, it.artifactId) } },
                                                    )
                                                } else {
                                                    InstallBundleSummaryCard(
                                                        state = installBundleState, familyLabel = modelSetup?.familyLabel,
                                                        modelDescription = null, onVariantSelected = onVariantSelected,
                                                        modifier = Modifier.testTag("detail-files"),
                                                        contentEndPadding = endInset, showVariantsHeading = false,
                                                        recommendedVariantPath = recommendedVariant,
                                                    )
                                                }
                                            }
                                        } else {
                                            CompositionLocalProvider(LocalContentEndInset provides endInset) {
                                                Box(Modifier.padding(start = startInset, top = spacing.spacing8)) {
                                                    ModelFileVariantsSection(
                                                        model = model, ggufFiles = ggufFiles,
                                                        isDownloading = isDownloading, activeDownloadArtifact = activeDownloadArtifact,
                                                        onDownloadClick = onDownloadClick, onDownloadGroupClick = onDownloadGroupClick,
                                                        emptyLabel = weightFilesEmptyLabel, recommendationState = recommendationState,
                                                        selectedVariantPath = installBundleState.selectedVariantPath, onVariantSelected = onVariantSelected,
                                                        onPauseDownload = onPauseDownload, onResumeDownload = onResumeDownload,
                                                        onCancelDownload = onCancelDownload, onRetryDownload = onRetryDownload,
                                                    )
                                                }
                                            }
                                        }
                                    } else if (page == 1) {
                                        ModelCardSection(
                                            state = modelCardState, onRetry = onRetryModelCard,
                                            sourceUrl = modelId.takeIf { Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$").matches(it) }?.let { "https://huggingface.co/$it" },
                                            modifier = Modifier.fillMaxWidth().padding(start = startInset, end = endInset, top = spacing.spacing8),
                                        )
                                    } else {
                                        Column(
                                            Modifier.fillMaxWidth().padding(start = startInset, end = endInset, top = spacing.spacing8)
                                                .testTag("detail-support"),
                                            verticalArrangement = Arrangement.spacedBy(spacing.spacing24),
                                        ) {
                                            ModelMetadataSection(model)
                                            ModelFitExplanation(recommendationState, browseSelection?.estimate ?: recommendationState?.browseFit)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (!pending && showInstallBundle && !inlineInstallAction && pagerState.currentPage == 0) {
                        InstallBundleActionFooter(
                            state = installBundleState,
                            modifier = Modifier.fillMaxWidth()
                                .padding(start = startInset, end = endInset, bottom = contentPadding.calculateBottomPadding())
                                .testTag("detail-action"),
                            onInstall = onSmartInstall, installEnabled = installEnabled, durableControl = durableControl,
                            onPause = { durableControl?.let { onPauseDownload(it.batchId, it.artifactId) } },
                            onResume = { durableControl?.let { onResumeDownload(it.batchId, it.artifactId) } },
                            onCancel = { durableControl?.let { onCancelDownload(it.batchId, it.artifactId) } },
                            onRetry = { durableControl?.let { onRetryDownload(it.batchId, it.artifactId) } },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelFileVariantsSection(
    model: ModelDetailResponse,
    ggufFiles: List<GgufFileUiState>,
    isDownloading: Boolean,
    activeDownloadArtifact: DownloadArtifactIdentity?,
    onDownloadClick: (String, String, DownloadMetadataDTO) -> Unit,
    onDownloadGroupClick: (String, List<DownloadMetadataDTO>) -> Unit,
    emptyLabel: String,
    recommendationState: RecommendedModelUiState?,
    selectedVariantPath: String?,
    onVariantSelected: (String) -> Unit,
    onPauseDownload: (String, String) -> Unit,
    onResumeDownload: (String, String) -> Unit,
    onCancelDownload: (String, String) -> Unit,
    onRetryDownload: (String, String) -> Unit,
) {
    val spacing = AppTheme.spacing
    val selectedDescriptor = recommendationState?.selectedDescriptor
    val primaryDescriptorFile = primaryDescriptorFile(selectedDescriptor)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .selectableGroup()
            .testTag("detail-files"),
    ) {
        val visibleFiles = ggufFiles.filterNot {
            com.debanshu777.caraml.sdk.discovery.huggingface.model.OnnxModelBundles.isBundlePath(it.path) && !it.path.endsWith(
                ".onnx"
            )
        }
        val labelCounts =
            visibleFiles.mapNotNull { compactVariantLabel(it.filename) }.groupingBy { it }
                .eachCount()
        if (visibleFiles.isEmpty()) {
            Text(
                text = emptyLabel,
                style = AppTheme.typography.bodyBase,
                color = AppTheme.colors.onSurfaceVariant,
            )
        } else {
            visibleFiles.forEach { item ->
                val role = ModelArtifactClassifier.classify(item.path).role
                val matchingBrowseVariant =
                    recommendationState?.browseVariants?.singleOrNull { item.path in it.filePaths }
                val groupMembers =
                    matchingBrowseVariant?.takeIf { it.filePaths.size > 1 }?.filePaths
                        ?.mapNotNull { path -> ggufFiles.singleOrNull { it.path == path } }
                        ?.takeIf { it.size == matchingBrowseVariant.filePaths.size }
                val groupMetadata = groupMembers?.mapNotNull { exactArtifactMetadata(model, it) }
                    ?.takeIf { metadata ->
                        metadata.size == groupMembers.size && matchingBrowseVariant?.let { variant ->
                            matchesExactBrowseGroup(variant, metadata.map { it.artifact })
                        } == true
                    }
                val isPrimary = role == ModelArtifactRole.PRIMARY_MODEL
                val durableTask = item.durableControl
                val hasExactArtifact = item.artifact != null
                val isDescriptorChoice = item.artifact?.let { artifact ->
                    primaryDescriptorFile?.matches(artifact) == true
                } == true
                val isRecommendedArtifact = isDescriptorChoice &&
                        recommendationState?.personalizedResult?.category == RecommendationCategory.RECOMMENDED
                val isEvaluatedArtifact = isDescriptorChoice && !isRecommendedArtifact
                val isProvisionalSuggestion =
                    recommendationState?.descriptorState == DescriptorState.NEEDS_INFORMATION &&
                            matchingBrowseVariant?.stableIdentity == recommendationState.stableModelId
                val isActiveDownload = isDownloading &&
                        activeDownloadArtifact != null &&
                        item.artifact == activeDownloadArtifact
                ArtifactFileRow(
                    item = item,
                    endInset = LocalContentEndInset.current,
                    variantLabel = compactVariantLabel(item.filename)?.takeIf { labelCounts[it] == 1 && isPrimary },
                    displaySizeBytes = if (item.path.endsWith(".onnx") && groupMetadata != null) groupMetadata.sumOf { it.artifact.expectedBytes } else item.sizeBytes,
                    recommended = isRecommendedArtifact,
                    evaluated = isEvaluatedArtifact,
                    provisional = isProvisionalSuggestion,
                    selected = isPrimary && matchingBrowseVariant != null && matchingBrowseVariant.stableIdentity ==
                            selectedVariantPath?.let { selectedPath ->
                                recommendationState?.browseVariants?.singleOrNull { selectedPath in it.filePaths }?.stableIdentity
                            },
                    roleLabel = matchingBrowseVariant?.takeIf { it.filePaths.size > 1 }
                        ?.let {
                            if (item.path.endsWith(".onnx")) "ONNX bundle · ${it.filePaths.size} files" else "Part ${
                                it.filePaths.indexOf(
                                    item.path
                                ) + 1
                            } of ${it.filePaths.size}"
                        }
                        ?: role.label().takeUnless { role == ModelArtifactRole.PRIMARY_MODEL },
                    onSelect = if (isPrimary && matchingBrowseVariant != null) ({
                        onVariantSelected(
                            item.path
                        )
                    }) else null,
                    isActiveDownload = isActiveDownload,
                    interactionLocked = isDownloading &&
                            durableTask?.artifactState !in durableArtifactControlStates,
                    downloadEnabled = if (matchingBrowseVariant?.filePaths?.size?.let { it > 1 } == true) {
                        groupMetadata != null
                    } else hasExactArtifact && !item.path.endsWith(".onnx"),
                    downloadActionDescription = groupMembers?.let {
                        if (item.path.endsWith(".onnx")) "Download complete ONNX model" else "Download all ${it.size} parts"
                    },
                    durableState = durableTask?.artifactState,
                    cancellationPending = durableTask?.cancellationPending == true,
                    onPause = { durableTask?.let { onPauseDownload(it.batchId, it.artifactId) } },
                    onResume = { durableTask?.let { onResumeDownload(it.batchId, it.artifactId) } },
                    onCancel = { durableTask?.let { onCancelDownload(it.batchId, it.artifactId) } },
                    onRetry = { durableTask?.let { onRetryDownload(it.batchId, it.artifactId) } },
                    onDownloadClick = {
                        if (groupMetadata != null) {
                            onDownloadGroupClick(model.modelId ?: model.id ?: "", groupMetadata)
                        } else if (matchingBrowseVariant?.filePaths?.size?.let { it > 1 } != true) {
                            dispatchExactArtifactDownload(model, item, onDownloadClick)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun ArtifactFileRow(
    item: GgufFileUiState,
    endInset: Dp,
    variantLabel: String?,
    displaySizeBytes: Long?,
    recommended: Boolean,
    evaluated: Boolean,
    provisional: Boolean,
    selected: Boolean,
    roleLabel: String?,
    onSelect: (() -> Unit)?,
    isActiveDownload: Boolean,
    interactionLocked: Boolean,
    downloadEnabled: Boolean,
    downloadActionDescription: String?,
    durableState: DownloadArtifactState?,
    cancellationPending: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDownloadClick: () -> Unit,
) {
    val highlightLabel = when {
        recommended -> "Recommended"
        provisional -> "Suggested · support unverified"
        evaluated -> "Evaluated variant"
        else -> null
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = AppTheme.shapes.extraSmall,
            color = if (selected) AppTheme.actionColor.copy(alpha = .08f) else Color.Transparent,
            contentColor = AppTheme.colors.onSurface,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        this.selected = selected
                        stateDescription = when {
                            recommended -> "Recommended variant${if (selected) ", selected" else ""}"
                            provisional -> "Suggested variant, compatibility unverified${if (selected) ", selected" else ""}"
                            evaluated -> "Evaluated variant${if (selected) ", selected" else ""}"
                            selected -> "Selected variant"
                            else -> "Available file"
                        }
                    }
                    .testTag("detail-artifact:${item.path}"),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                ) {
                    ModelDetailsDownloadableListItem(
                        filename = item.path.ifEmpty { item.filename },
                        variantLabel = variantLabel,
                        emphasizeDownload = selected,
                        sizeBytes = displaySizeBytes,
                        isDownloaded = item.isDownloaded,
                        progress = item.progress,
                        isDownloading = isActiveDownload,
                        onDownloadClick = onDownloadClick,
                        modifier = Modifier.weight(1f),
                        downloadEnabled = downloadEnabled,
                        downloadActionDescription = downloadActionDescription,
                        interactionLocked = interactionLocked,
                        durableState = durableState,
                        cancellationPending = cancellationPending,
                        onPause = onPause,
                        onResume = onResume,
                        onCancel = onCancel,
                        onRetry = onRetry,
                        supportingLabel = listOfNotNull(
                            highlightLabel,
                            roleLabel
                        ).joinToString(" · ").ifBlank { null },
                    )
                }
            }
        }
        HorizontalDivider(
            modifier = Modifier.testTag("detail-divider:${item.path}"),
            color = AppTheme.colors.outlineVariant,
        )
    }
}

private fun ModelArtifactRole.label(): String = when (this) {
    ModelArtifactRole.PRIMARY_MODEL -> "GGUF model"
    ModelArtifactRole.PROJECTOR -> "Supporting projector file"
    ModelArtifactRole.ADAPTER -> "Supporting adapter file"
    ModelArtifactRole.UNVERIFIED -> "Role unverified"
}


private fun dispatchExactArtifactDownload(
    model: ModelDetailResponse,
    item: GgufFileUiState,
    onDownloadClick: (String, String, DownloadMetadataDTO) -> Unit,
) {
    val metadata = exactArtifactMetadata(model, item) ?: return
    onDownloadClick(model.modelId ?: model.id ?: "", item.path, metadata)
}

private fun exactArtifactMetadata(
    model: ModelDetailResponse,
    item: GgufFileUiState,
): DownloadMetadataDTO? {
    val artifact = item.artifact ?: return null
    return DownloadMetadataDTO(
        artifact = artifact,
        logicalRole = "model",
        sizeBytes = artifact.expectedBytes,
        author = model.author,
        libraryName = model.libraryName,
        pipelineTag = model.pipelineTag,
        contextLength = model.gguf?.contextLength,
    )
}

private fun primaryDescriptorFile(descriptor: ModelDescriptor?): ModelFileIdentity? =
    when (descriptor) {
        is LlmModelDescriptor -> descriptor.file
        is DiffusionModelDescriptor -> descriptor.components.singleOrNull { it.isPrimary }?.file
        null -> null
    }

private fun ModelFileIdentity.matches(artifact: DownloadArtifactIdentity): Boolean {
    val remoteObjectId = lfsOid?.let { "sha256:$it" } ?: xetHash ?: gitOid
    return repositoryId == artifact.repositoryId &&
            revision.lowercase() == artifact.immutableRevision &&
            path == artifact.relativePath &&
            sizeBytes == artifact.expectedBytes &&
            remoteObjectId?.lowercase() == artifact.remoteObjectId
}

private fun recommendedVariantPath(
    descriptor: ModelDescriptor?,
    variants: List<GgufFileUiState>,
): String? {
    val primary = when (descriptor) {
        is LlmModelDescriptor -> descriptor.file
        is DiffusionModelDescriptor -> descriptor.components.singleOrNull { it.isPrimary }?.file
        null -> null
    } ?: return null
    return variants.singleOrNull { variant ->
        val artifact = variant.artifact ?: return@singleOrNull false
        primary.matches(artifact)
    }?.path
}

internal data class RepositoryHeading(
    val owner: String?,
    val name: String,
)

internal fun splitRepositoryId(id: String): RepositoryHeading {
    val normalized = id.trim()
    if (normalized.isEmpty()) return RepositoryHeading(owner = null, name = "Unknown")
    val separator = normalized.indexOf('/')
    if (separator <= 0 || separator == normalized.lastIndex) {
        return RepositoryHeading(owner = null, name = normalized)
    }
    return RepositoryHeading(
        owner = normalized.substring(0, separator),
        name = normalized.substring(separator + 1),
    )
}

private val hubTimestampDate = Regex(
    """^(\d{4})-(\d{2})-(\d{2})(?:T(?:[01]\d|2[0-3]):[0-5]\d:[0-5]\d(?:\.\d+)?(?:Z|[+-](?:[01]\d|2[0-3]):[0-5]\d))?$""",
)
private val monthLabels = listOf(
    "Jan", "Feb", "Mar", "Apr", "May", "Jun",
    "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
)

internal fun formatHubTimestamp(value: String?): String? {
    if (value == null) return null
    val match = hubTimestampDate.matchEntire(value) ?: return value
    val year = match.groupValues[1].toIntOrNull() ?: return value
    val month = match.groupValues[2].toIntOrNull() ?: return value
    val day = match.groupValues[3].toIntOrNull() ?: return value
    if (month !in 1..12 || day !in 1..daysInMonth(year, month)) return value
    return "$day ${monthLabels[month - 1]} $year"
}

private fun daysInMonth(year: Int, month: Int): Int = when (month) {
    2 -> if (year % 400 == 0 || (year % 4 == 0 && year % 100 != 0)) 29 else 28
    4, 6, 9, 11 -> 30
    else -> 31
}

internal fun visibleModelTags(
    tags: List<String?>,
    pipelineTag: String?,
    libraryName: String? = null,
    modelType: String? = null,
): List<String> {
    val representedFacts = listOfNotNull(pipelineTag, libraryName, modelType)
        .map { it.trim().lowercase() }
        .toSet()
    return tags.asSequence()
        .filterNotNull()
        .map { it.trim() }
        .filter { tag ->
            tag.isNotEmpty() && ':' !in tag && tag.lowercase() !in representedFacts
        }
        .distinctBy { it.lowercase() }
        .toList()
}

@Preview(name = "Model detail content - compact", widthDp = 412, heightDp = 915)
@Composable
private fun ModelDetailContentCompactPreview() {
    ModelDetailContentPreview()
}

@Preview(name = "Model detail content - large text", widthDp = 360, heightDp = 900, fontScale = 2f)
@Composable
private fun ModelDetailContentLargeTextPreview() {
    ModelDetailContentPreview()
}

@Preview(name = "Model detail content - desktop", widthDp = 1180, heightDp = 780)
@Composable
private fun ModelDetailContentDesktopPreview() {
    ModelDetailContentPreview()
}

@Preview(name = "Model detail content - no files", widthDp = 412, heightDp = 780)
@Composable
private fun ModelDetailContentEmptyPreview() {
    ModelDetailContentPreview(showFiles = false)
}

@Composable
private fun ModelDetailContentPreview(showFiles: Boolean = true) {
    CaraMLTheme(ThemePreferences()) {
        Surface {
            ModelDetailContent(
                model = modelDetailContentPreviewModel,
                ggufFiles = if (showFiles) modelDetailContentPreviewFiles else emptyList(),
                isDownloading = false,
                onDownloadClick = { _, _, _ -> },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private val modelDetailContentPreviewModel = ModelDetailResponse(
    modelId = "Qwen/Qwen2.5-7B-Instruct-GGUF",
    author = "Qwen",
    pipelineTag = "text-generation",
    downloads = 2_192_290,
    likes = 1_324,
    createdAt = "2026-08-17T00:00:00.000Z",
    cardData = ModelDetailResponse.CardData(
        baseModel = listOf("Qwen/Qwen2.5-7B-Instruct"),
        license = "apache-2.0",
        pipelineTag = "text-generation",
    ),
)

private val modelDetailContentPreviewFiles = listOf(
    GgufFileUiState(
        path = "weights/Qwen2.5-7B-Instruct-Q4_K_M.gguf",
        filename = "Qwen2.5-7B-Instruct-Q4_K_M.gguf",
        sizeBytes = 4_700_000_000L,
        isDownloaded = false,
        progress = null,
    ),
    GgufFileUiState(
        path = "weights/Qwen2.5-7B-Instruct-Q5_K_M.gguf",
        filename = "Qwen2.5-7B-Instruct-Q5_K_M.gguf",
        sizeBytes = 5_200_000_000L,
        isDownloaded = true,
        progress = null,
    ),
)
