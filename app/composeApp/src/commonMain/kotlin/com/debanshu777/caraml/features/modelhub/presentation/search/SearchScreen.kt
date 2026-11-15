package com.debanshu777.caraml.features.modelhub.presentation.search

import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import com.debanshu777.caraml.features.modelhub.presentation.search.components.pendingArtifacts
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelHubFilterButton
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelSearchAction
import com.debanshu777.caraml.features.modelhub.presentation.search.components.SearchBar
import com.debanshu777.caraml.core.ui.icons.AppIcons
import com.debanshu777.caraml.features.modelhub.presentation.search.components.RecentlyViewedModelsSection
import com.debanshu777.caraml.features.modelhub.presentation.search.components.pullToSearch
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.features.modelhub.presentation.details.ModelDetailsPreview
import com.debanshu777.caraml.features.modelhub.presentation.details.detailsPreview
import com.debanshu777.caraml.core.theme.AppTheme
import com.debanshu777.caraml.core.ui.components.BrandButton
import com.debanshu777.caraml.core.ui.components.BrandButtonStyle
import com.debanshu777.caraml.core.rating.ui.RecommendationDetailsSheet
import com.debanshu777.caraml.core.rating.ui.recommendationPresentation
import com.debanshu777.caraml.core.recommendation.OptimizationPriority
import com.debanshu777.caraml.core.recommendation.RecommendationProfile
import com.debanshu777.caraml.core.recommendation.RecommendationRolloutModeSource
import com.debanshu777.caraml.core.recommendation.RiskTolerance
import com.debanshu777.caraml.core.theme.AuroraSurfaceLevel
import com.debanshu777.caraml.core.ui.components.FrostedPageScaffold
import com.debanshu777.caraml.core.ui.components.BrandPageHeader
import com.debanshu777.caraml.core.ui.layout.AppContentKind
import com.debanshu777.caraml.core.ui.motion.AuroraMotionPolicy
import com.debanshu777.caraml.core.ui.motion.LocalAuroraMotionPolicy
import com.debanshu777.caraml.features.modelhub.domain.RecommendedModelUiState
import com.debanshu777.caraml.features.modelhub.presentation.downloaded.DownloadedModelsViewModel
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelHubDeviceInfo
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelHubStateKind
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelHubStateView
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelHubLoadingMark
import com.debanshu777.caraml.features.modelhub.presentation.components.ModelRow
import com.debanshu777.caraml.features.modelhub.presentation.components.discoveryModelRow
import com.debanshu777.caraml.core.ui.layout.LocalContentEndInset
import com.debanshu777.caraml.features.modelhub.presentation.search.components.QuickCalibrationDialog
import com.debanshu777.caraml.features.modelhub.presentation.search.components.RecommendationProfileDialog
import com.debanshu777.caraml.features.settings.presentation.RecommendationProfileSection
import com.debanshu777.caraml.features.settings.presentation.SettingsViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    modelViewModel: ModelViewModel,
    downloadedModelsViewModel: DownloadedModelsViewModel,
    onNavigateToDetails: (modelId: String, hubBrowseMode: ModelHubBrowseMode, preview: ModelDetailsPreview) -> Unit,
    onOpenDownloads: () -> Unit,
    modifier: Modifier = Modifier,
    settingsViewModel: SettingsViewModel = koinViewModel(),
    rolloutModeSource: RecommendationRolloutModeSource = koinInject(),
) {
    val settings by settingsViewModel.settings.collectAsState()
    val settingsLoaded by settingsViewModel.settingsLoaded.collectAsState()
    val effectiveProfile by settingsViewModel.effectiveRecommendationProfile.collectAsState()
    val profileSaving by settingsViewModel.isRecommendationProfileSaving.collectAsState()
    val profileError by settingsViewModel.recommendationProfileError.collectAsState()
    val quickCalibration by modelViewModel.quickCalibration.collectAsState()
    val downloadQueue by modelViewModel.downloadQueue.collectAsState()
    val downloadedVariants by downloadedModelsViewModel.downloadedVariantCounts.collectAsState()
    val persistedProfileState = profileUiState(
        settings = settings,
        rolloutMode = rolloutModeSource.current(),
        settingsLoaded = settingsLoaded,
    )
    val recommendationProfileState = persistedProfileState.copy(profile = effectiveProfile)

    var recommendationSheetState by remember { mutableStateOf<RecommendedModelUiState?>(null) }

    SearchTabContent(
        viewModel = modelViewModel,
        onNavigateToDetails = onNavigateToDetails,
        onRecommendationInfoClick = { recommendationSheetState = it },
        onOpenDownloads = onOpenDownloads,
        pendingDownloads = downloadQueue.sumOf { it.pendingArtifacts.size },
        downloadedVariantCounts = downloadedVariants,
        modifier = modifier.fillMaxSize(),
    )

    val sheetState = recommendationSheetState
    val sheetRecommendation = sheetState?.personalizedResult
    if (sheetState != null && sheetRecommendation != null) {
        RecommendationDetailsSheet(
            modelId = sheetState.repositoryId ?: sheetState.stableModelId,
            recommendation = sheetRecommendation,
            presentation = recommendationPresentation(
                sheetRecommendation,
                sheetState.selectedVariantName,
                sheetState.workload,
            ),
            onDismiss = { recommendationSheetState = null },
        )
    }

    if (recommendationProfileState.showDialog) {
        RecommendationProfileDialog(
            initial = recommendationProfileState.profile,
            onContinue = settingsViewModel::completeModelProfileOnboarding,
            onDismissWithBalanced = {
                settingsViewModel.completeModelProfileOnboarding(RecommendationProfile())
            },
            submitting = profileSaving,
            errorMessage = profileError,
        )
    }

    if (recommendationProfileState.isAvailable &&
        settings.modelProfileOnboardingComplete &&
        !settings.recommendationCalibrationOfferComplete
    ) {
        QuickCalibrationDialog(
            state = quickCalibration,
            onRun = { modelViewModel.runQuickCalibration() },
            onRunWithUnknownPower = { modelViewModel.runQuickCalibration(allowUnknownPower = true) },
            onCancel = modelViewModel::cancelQuickCalibration,
            onSkip = modelViewModel::skipQuickCalibration,
        )
    }
}

@Composable
internal fun ModelDeviceScreen(
    modelViewModel: ModelViewModel,
    settingsViewModel: SettingsViewModel,
    onBack: () -> Unit,
    rolloutModeSource: RecommendationRolloutModeSource = koinInject(),
) {
    val settings by settingsViewModel.settings.collectAsState()
    val settingsLoaded by settingsViewModel.settingsLoaded.collectAsState()
    val effectiveProfile by settingsViewModel.effectiveRecommendationProfile.collectAsState()
    val profileSaving by settingsViewModel.isRecommendationProfileSaving.collectAsState()
    val profileError by settingsViewModel.recommendationProfileError.collectAsState()
    val storageInfo by modelViewModel.storageInfo.collectAsState()
    var profileEditorVisible by rememberSaveable { mutableStateOf(false) }
    val profileAvailable = profileUiState(settings, rolloutModeSource.current(), settingsLoaded).isAvailable
    LaunchedEffect(modelViewModel) { modelViewModel.refreshDeviceInfo() }
    ModelHubDeviceInfo(
        storageInfo = storageInfo,
        profile = effectiveProfile.takeIf { profileAvailable },
        onBack = onBack,
        onRefresh = modelViewModel::refreshDeviceInfo,
        onOpenProfile = if (profileAvailable) ({ profileEditorVisible = true }) else null,
        backContentDescription = "Back",
    )
    if (profileEditorVisible && profileAvailable) {
        RecommendationProfileEditorSheet(
            profile = effectiveProfile,
            saving = profileSaving,
            errorMessage = profileError,
            onDismiss = { profileEditorVisible = false },
            onRiskToleranceChange = settingsViewModel::updateRiskTolerance,
            onOptimizationPriorityChange = settingsViewModel::updateOptimizationPriority,
        )
    }
}

@Composable
internal fun ModelHubTabLayout(
    context: @Composable () -> Unit,
    toolbar: @Composable () -> Unit,
    summary: @Composable () -> Unit,
    results: LazyListScope.() -> Unit,
    modifier: Modifier = Modifier,
    command: (@Composable () -> Unit)? = null,
    scrollResetKey: Any? = null,
    autoLoadKeys: Set<Any> = emptySet(),
    autoLoadEnabled: Boolean = false,
    onNearEnd: (() -> Unit)? = null,
    header: (@Composable () -> Unit)? = null,
    snackbarHostState: SnackbarHostState? = null,
    onPullToSearch: (() -> Unit)? = null,
) {
    val listState = key(scrollResetKey) { rememberLazyListState() }
    val currentOnNearEnd by rememberUpdatedState(onNearEnd)
    LaunchedEffect(listState, autoLoadKeys, autoLoadEnabled) {
        if (autoLoadEnabled && autoLoadKeys.isNotEmpty()) {
            snapshotFlow {
                listState.layoutInfo.visibleItemsInfo.any { item -> item.key in autoLoadKeys }
            }.distinctUntilChanged().collect { nearEnd ->
                if (nearEnd) currentOnNearEnd?.invoke()
            }
        }
    }
    BoxWithConstraints(modifier) {
        // In short viewports, scroll the page heading with the results. Keep the
        // search controls in the same composition so resizing preserves focus and selection.
        val scrollHeader = header != null && maxHeight < 480.dp
        val stickyHeader: (@Composable () -> Unit)? = if (scrollHeader && command == null) null else {
            {
                if (!scrollHeader) header?.invoke()
                key("model-command") { command?.invoke() }
            }
        }
        FrostedPageScaffold(
            kind = AppContentKind.ModelHub,
            modifier = Modifier.fillMaxSize(),
            header = stickyHeader,
            snackbarHost = { snackbarHostState?.let { SnackbarHost(it) } },
        ) { insets ->
            val endInset = LocalContentEndInset.current
            LazyColumn(
                modifier = Modifier.fillMaxSize()
                    .testTag("model-primary-results")
                    .pullToSearch(listState, onPullToSearch),
                state = listState,
                contentPadding = PaddingValues(
                    top = insets.calculateTopPadding() + AppTheme.spacing.spacing8,
                    bottom = insets.calculateBottomPadding() + AppTheme.spacing.spacing24,
                ),
            ) {
                if (scrollHeader) {
                    item(key = "model-page-header") {
                        Column(Modifier.fillMaxWidth().padding(end = endInset, bottom = AppTheme.spacing.spacing24)) {
                            header?.invoke()
                        }
                    }
                }
                item(key = "model-context") { Column(Modifier.padding(end = endInset)) { context() } }
                item(key = "model-toolbar") { Column(Modifier.padding(end = endInset)) { toolbar() } }
                item(key = "model-summary") { Column(Modifier.padding(end = endInset)) { summary() } }
                results()
            }
        }
    }
}

@Composable
internal fun SearchTabContent(
    viewModel: ModelViewModel,
    onNavigateToDetails: (modelId: String, hubBrowseMode: ModelHubBrowseMode, preview: ModelDetailsPreview) -> Unit,
    onRecommendationInfoClick: (RecommendedModelUiState) -> Unit,
    modifier: Modifier = Modifier,
    onOpenDownloads: () -> Unit = {},
    pendingDownloads: Int = 0,
    downloadedVariantCounts: Map<String, Int> = emptyMap(),
) {
    val browseMode by viewModel.browseMode.collectAsState()
    val languageFormat by viewModel.languageModelFormat.collectAsState()
    LaunchedEffect(viewModel) { viewModel.ensureDiscoverLoaded() }
    val searchQuery by viewModel.searchQuery.collectAsState()
    val results by viewModel.results.collectAsState()
    val listParams by viewModel.listParams.collectAsState()
    val modelOrdering by viewModel.modelOrdering.collectAsState()

    var hasEngagedSearch by rememberSaveable { mutableStateOf(searchQuery.isNotBlank()) }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val recentModels by viewModel.recentlyViewedModels.collectAsState()

    var imageQuery by rememberSaveable { mutableStateOf("") }
    var videoQuery by rememberSaveable { mutableStateOf("") }

    val isLlmHub = browseMode == ModelHubBrowseMode.LanguageModels
    val committedQuery = results.key?.committedSearch.orEmpty()
    val curatedQuery = when (browseMode) {
        ModelHubBrowseMode.LanguageModels -> ""
        ModelHubBrowseMode.DiffusionImage -> imageQuery
        ModelHubBrowseMode.DiffusionVideo -> videoQuery
    }
    val models = results.orderedModels.filter { model ->
        isLlmHub || curatedQuery.isBlank() ||
                model.id.orEmpty().contains(curatedQuery.trim(), ignoreCase = true)
    }
    val resetFilters = {
        hasEngagedSearch = true
        viewModel.resetDiscoverFilters()
    }
    val motion = LocalAuroraMotionPolicy.current

    ModelHubTabLayout(
        modifier = modifier,
        scrollResetKey = results.key,
        autoLoadKeys = if (isLlmHub) models.takeLast(5).mapNotNull { it.id }
            .toSet() else emptySet(),
        autoLoadEnabled = isLlmHub && results.canLoadMore && results.moreError == null,
        onNearEnd = viewModel::autoLoadNextPage,
        header = {
            BrandPageHeader(title = "Models", modifier = Modifier.padding(bottom = AppTheme.spacing.spacing8)) {
                ModelSearchAction(
                    icon = AppIcons.Download,
                    label = "Downloads",
                    badge = pendingDownloads.takeIf { it > 0 },
                    badgeDescription = "$pendingDownloads pending downloads",
                    onClick = {
                        focusManager.clearFocus()
                        onOpenDownloads()
                    },
                )
            }
        },
        onPullToSearch = {
            if (focusRequester.requestFocus()) keyboardController?.show()
        },
        command = {
            SearchBar(
                query = if (isLlmHub) searchQuery else curatedQuery,
                onQueryChange = { query ->
                    if (query.isNotBlank()) hasEngagedSearch = true
                    when (browseMode) {
                        ModelHubBrowseMode.LanguageModels -> viewModel.updateSearchQuery(query)
                        ModelHubBrowseMode.DiffusionImage -> imageQuery = query.take(MAX_LOCAL_MODEL_QUERY_LENGTH)
                        ModelHubBrowseMode.DiffusionVideo -> videoQuery = query.take(MAX_LOCAL_MODEL_QUERY_LENGTH)
                    }
                },
                onSearch = {
                    if (isLlmHub) viewModel.performSearch()
                    keyboardController?.hide()
                },
                onClear = {
                    when (browseMode) {
                        ModelHubBrowseMode.LanguageModels -> viewModel.clearSearch()
                        ModelHubBrowseMode.DiffusionImage -> imageQuery = ""
                        ModelHubBrowseMode.DiffusionVideo -> videoQuery = ""
                    }
                },
                focusRequester = focusRequester,
                errorMessage = results.inputError.takeIf { isLlmHub },
                placeholder = "Search models",
                compact = true,
                trailingAction = {
                    ModelHubFilterButton(
                        onOpen = { focusManager.clearFocus() },
                        mode = browseMode, ordering = modelOrdering,
                        minParams = listParams.minParams, maxParams = listParams.maxParams,
                        languageModelFormat = languageFormat,
                        onApply = viewModel::applyDiscoverFilters,
                        onApplyWithFormat = { mode, ordering, min, max, format ->
                            hasEngagedSearch = true
                            viewModel.applyDiscoverFilters(mode, ordering, min, max, format)
                        },
                    )
                },
            )
        },
        context = {
            if (!hasEngagedSearch && searchQuery.isBlank() && recentModels.isNotEmpty()) {
                RecentlyViewedModelsSection(models = recentModels, onOpen = { onNavigateToDetails(it.modelId, it.browseMode(), it.detailsPreview()) })
            }
        },
        toolbar = {},
        summary = {
            Column(
                Modifier.fillMaxWidth().padding(bottom = AppTheme.spacing.spacing8),
                verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.spacing4),
            ) {
                Text(
                    text = if (hasEngagedSearch || searchQuery.isNotBlank()) "Search results" else "Explore models",
                    style = AppTheme.typography.headingSmall,
                    color = AppTheme.colors.onSurface,
                    modifier = Modifier.semantics { heading() },
                )
                if (!results.initialLoading && results.initialError == null && models.isNotEmpty()) {
                    val total = results.totalCount
                    val count = if (isLlmHub && total != null) {
                        "$total matching ${if (total == 1) "model" else "models"} · ${models.size} loaded"
                    } else {
                        "${models.size} ${if (models.size == 1) "model" else "models"} loaded"
                    }
                    Text(
                        if (isLlmHub) "${languageFormat.uppercase()} · $count" else count,
                        style = AppTheme.typography.labelSmall,
                        color = AppTheme.colors.onSurfaceVariant,
                    )
                }
                if (isLlmHub && modelOrdering is ModelOrdering.Personalized && results.models.isNotEmpty()) {
                    Text(
                        text = "Best fit among loaded models · ${results.assessedCount} of ${results.models.size.coerceAtMost(96)} assessed (96 per session maximum)",
                        style = AppTheme.typography.bodySmall,
                        color = AppTheme.colors.onSurfaceVariant,
                    )
                    if (results.canAssessMore) {
                        BrandButton(style = BrandButtonStyle.Secondary, onClick = viewModel::loadMoreRecommendations) {
                            Text("Assess more loaded models")
                        }
                    }
                }
            }
        },
        results = {
            modelHubResultItems(
                isLoading = results.initialLoading,
                hasResponse = results.models.isNotEmpty() ||
                        (results.key != null && !results.initialLoading && results.initialError == null),
                errorMessage = results.initialError,
                models = models,
                itemKey = { it.id ?: it.hashCode() },
                blockingLoadingKey = "models-loading",
                refreshLoadingKey = "models-refreshing",
                errorKey = "models-error",
                emptyKey = "models-empty",
                blockingLoadingDescription = "Loading models",
                refreshLoadingDescription = "Refreshing models",
                emptyMessage = when {
                    committedQuery.isNotBlank() -> "No models match “$committedQuery”."
                    !isLlmHub && curatedQuery.isNotBlank() -> "No curated models match “$curatedQuery”."
                    else -> "No models are available yet."
                },
                errorActionLabel = "Retry",
                onErrorAction = viewModel::loadModels,
                errorSecondaryActionLabel = "Open downloads",
                onErrorSecondaryAction = onOpenDownloads,
                emptyActionLabel = if (committedQuery.isNotBlank() || curatedQuery.isNotBlank()) "Clear search" else "Reset filters",
                onEmptyAction = when {
                    committedQuery.isNotBlank() -> viewModel::clearSearch
                    curatedQuery.isNotBlank() -> ({ imageQuery = ""; videoQuery = "" })
                    else -> resetFilters
                },
                motion = motion,
            ) { model, itemModifier ->
                val recommendation = model.id?.let(results.recommendationById::get)
                ModelRow(
                    state = discoveryModelRow(model, recommendation, downloadedVariantCounts[model.id] ?: 0),
                    modifier = itemModifier,
                    sharedTransitionKey = "catalog:${model.id}",
                    onOpenModel = { model.id?.let { onNavigateToDetails(it, browseMode, model.detailsPreview()) } },
                    onRecommendationInfoClick = recommendation
                        ?.takeIf { it.personalizedResult != null }
                        ?.let { state -> { onRecommendationInfoClick(state) } },
                )
            }
            if (isLlmHub && results.models.isNotEmpty() && results.initialError == null &&
                !results.initialLoading && (results.moreLoading || results.moreError != null ||
                        results.sessionLimitReached || !results.hasMore)
            ) {
                item(key = "model-page-footer") {
                    ModelPageFooter(
                        loadedCount = results.models.size,
                        loading = results.moreLoading,
                        error = results.moreError,
                        hasMore = results.hasMore,
                        sessionLimitReached = results.sessionLimitReached,
                        onLoadMore = viewModel::loadNextPage,
                        onStartOver = viewModel::restartCurrentQuery,
                    )
                }
            }
        },
    )
}

@Composable
internal fun ModelPageFooter(
    loadedCount: Int,
    loading: Boolean,
    error: String?,
    hasMore: Boolean,
    sessionLimitReached: Boolean,
    onLoadMore: () -> Unit,
    onStartOver: () -> Unit = {},
) {
    Column(
        modifier = Modifier.fillMaxWidth().then(if (error != null) Modifier
            .clip(AppTheme.shapes.medium).background(AppTheme.colors.surfaceContainerLowest)
            .border(1.dp, AppTheme.colors.outlineVariant, AppTheme.shapes.medium) else Modifier)
            .padding(AppTheme.spacing.spacing16).testTag("model-page-footer"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.spacing8),
    ) {
        when {
            error != null -> {
                Text("Couldn't load more", style = AppTheme.typography.labelLarge, color = AppTheme.colors.onSurface)
                Text("Your loaded models are still here. $error", color = AppTheme.colors.onSurfaceVariant, style = AppTheme.typography.bodySmall)
                if (hasMore) {
                    BrandButton(
                        style = BrandButtonStyle.Secondary,
                        onClick = onLoadMore,
                        modifier = Modifier.heightIn(min = AppTheme.spacing.spacing48)
                    ) {
                        Text("Retry load more")
                    }
                } else {
                    Text("Refine your search above to explore more models.", color = AppTheme.colors.onSurface)
                    BrandButton(
                        style = BrandButtonStyle.Secondary,
                        onClick = onStartOver,
                        modifier = Modifier.heightIn(min = AppTheme.spacing.spacing48)
                    ) {
                        Text("Start over")
                    }
                }
            }

            loading -> {
                ModelHubLoadingMark(modifier = Modifier.size(AppTheme.spacing.spacing24))
                Text("Loading more models", color = AppTheme.colors.onSurface)
            }

            sessionLimitReached -> Text("$loadedCount models loaded · Session limit reached. Refine your search to explore more.", color = AppTheme.colors.onSurface)
            hasMore -> Unit // The visible last five model rows trigger the next page.
            else -> Text("All results loaded", color = AppTheme.colors.onSurface)
        }
    }
}

internal fun <T> LazyListScope.modelHubResultItems(
    isLoading: Boolean,
    hasResponse: Boolean,
    errorMessage: String?,
    models: List<T>,
    itemKey: (T) -> Any,
    blockingLoadingKey: String,
    refreshLoadingKey: String,
    errorKey: String,
    emptyKey: String,
    blockingLoadingDescription: String,
    refreshLoadingDescription: String,
    emptyMessage: String,
    errorActionLabel: String? = null,
    onErrorAction: (() -> Unit)? = null,
    errorSecondaryActionLabel: String? = null,
    onErrorSecondaryAction: (() -> Unit)? = null,
    emptyActionLabel: String? = null,
    onEmptyAction: (() -> Unit)? = null,
    motion: AuroraMotionPolicy,
    itemContent: @Composable LazyItemScope.(T, Modifier) -> Unit,
) {
    if (isLoading && !hasResponse) {
        item(key = blockingLoadingKey) {
            ModelHubStateView(
                kind = ModelHubStateKind.Loading,
                message = blockingLoadingDescription,
                modifier = Modifier.semantics {
                    contentDescription = blockingLoadingDescription
                },
            )
        }
        return
    }

    if (errorMessage != null) {
        item(key = errorKey) {
            ModelHubStateView(
                kind = ModelHubStateKind.Error,
                message = errorMessage,
                actionLabel = errorActionLabel,
                onAction = onErrorAction,
                secondaryActionLabel = errorSecondaryActionLabel,
                onSecondaryAction = onErrorSecondaryAction,
            )
        }
        return
    }

    if (models.isEmpty()) {
        item(key = emptyKey) {
            ModelHubStateView(
                kind = ModelHubStateKind.Empty,
                message = emptyMessage,
                actionLabel = emptyActionLabel,
                onAction = onEmptyAction,
            )
        }
    } else {
        itemsIndexed(
            items = models,
            key = { _, model -> itemKey(model) },
        ) { index, model ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .animateItem(
                        fadeInSpec = null,
                        placementSpec = if (motion.spatialTransitionsEnabled) {
                            tween(motion.peerTransitionMillis)
                        } else {
                            null
                        },
                        fadeOutSpec = null,
                    )
                    .then(
                        if (index == 0) {
                            Modifier
                                .testTag("model-results")
                                .semantics { stateDescription = "Model results loaded" }
                        } else {
                            Modifier
                        },
                    ),
            ) {
                itemContent(model, Modifier.fillMaxWidth())
            }
        }
    }

    if (isLoading) {
        item(key = refreshLoadingKey) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = AppTheme.spacing.spacing48)
                    .padding(horizontal = AppTheme.spacing.spacing24, vertical = AppTheme.spacing.spacing8)
                    .semantics { contentDescription = refreshLoadingDescription },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ModelHubLoadingMark(modifier = Modifier.size(AppTheme.spacing.spacing24))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecommendationProfileEditorSheet(
    profile: RecommendationProfile,
    saving: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onRiskToleranceChange: (RiskTolerance) -> Unit,
    onOptimizationPriorityChange: (OptimizationPriority) -> Unit,
) {
    val spacing = AppTheme.spacing
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = AppTheme.shapes.extraLarge,
        containerColor = AuroraSurfaceLevel.Floating.containerColor(AppTheme.colors),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.spacing16)
                .padding(bottom = spacing.spacing32),
            verticalArrangement = Arrangement.spacedBy(spacing.spacing8),
        ) {
            RecommendationProfileSection(
                profile = profile,
                onRiskToleranceChange = onRiskToleranceChange,
                onOptimizationPriorityChange = onOptimizationPriorityChange,
                enabled = !saving,
            )
            if (errorMessage != null) {
                Text(
                    text = errorMessage,
                    style = AppTheme.typography.bodySmall,
                    color = AppTheme.colors.error,
                )
            }
        }
    }
}

private const val MAX_LOCAL_MODEL_QUERY_LENGTH = 200

@Preview(name = "Models compact - populated", widthDp = 412, heightDp = 915)
@Composable
private fun SearchScreenPopulatedPreview() {
    ModelHubDevicePreview(populated = true)
}

@Preview(name = "Models compact - empty", widthDp = 412, heightDp = 915)
@Composable
private fun SearchScreenEmptyPreview() {
    ModelHubDevicePreview(populated = false)
}

@Preview(
    name = "Models compact - populated 200%",
    widthDp = 360,
    heightDp = 800,
    fontScale = 2f,
)
@Composable
private fun SearchScreenLargeTextPreview() {
    ModelHubDevicePreview(populated = true)
}

@Preview(name = "Models desktop - populated", widthDp = 1180, heightDp = 780)
@Composable
private fun SearchScreenDesktopPreview() {
    ModelHubDevicePreview(populated = true)
}
