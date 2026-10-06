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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.theme.AppTheme
import com.debanshu777.caraml.core.ui.components.BrandButton
import com.debanshu777.caraml.core.ui.components.BrandButtonStyle
import com.debanshu777.caraml.core.rating.ui.RecommendationDetailsSheet
import com.debanshu777.caraml.core.rating.ui.recommendationPresentation
import com.debanshu777.caraml.core.recommendation.OptimizationPriority
import com.debanshu777.caraml.core.recommendation.RecommendationProfile
import com.debanshu777.caraml.core.recommendation.RecommendationRolloutModeSource
import com.debanshu777.caraml.core.recommendation.RiskTolerance
import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import com.debanshu777.caraml.core.storage.localmodel.ModelType
import com.debanshu777.caraml.core.theme.AuroraSurfaceLevel
import com.debanshu777.caraml.core.ui.components.FrostedPageScaffold
import com.debanshu777.caraml.core.ui.components.BrandPageHeader
import com.debanshu777.caraml.core.ui.layout.AppContentKind
import com.debanshu777.caraml.core.ui.layout.ResponsiveContentPane
import com.debanshu777.caraml.core.ui.motion.AuroraMotionPolicy
import com.debanshu777.caraml.core.ui.motion.LocalAuroraMotionPolicy
import com.debanshu777.caraml.features.modelhub.domain.RecommendedModelUiState
import com.debanshu777.caraml.features.modelhub.presentation.downloaded.DownloadedModelsViewModel
import com.debanshu777.caraml.features.modelhub.presentation.downloaded.ReadinessFilter
import com.debanshu777.caraml.features.modelhub.presentation.downloaded.components.DownloadedListItem
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelDownloadQueueEntry
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelDownloadQueueSheet
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelHubContextStrip
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelHubDeviceInfo
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelHubFilterButton
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelHubHeader
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelHubStateKind
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelHubStateView
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelHubLoadingMark
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelHubInlineFilters
import com.debanshu777.caraml.features.modelhub.presentation.search.components.SearchListItem
import com.debanshu777.caraml.features.modelhub.presentation.search.components.QuickCalibrationDialog
import com.debanshu777.caraml.features.modelhub.presentation.search.components.RecommendationProfileDialog
import com.debanshu777.caraml.features.modelhub.presentation.search.components.SearchBar
import com.debanshu777.caraml.features.settings.presentation.RecommendationProfileSection
import com.debanshu777.caraml.features.settings.presentation.SettingsViewModel
import com.debanshu777.huggingfacemanager.model.ParameterRange
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    modelViewModel: ModelViewModel,
    downloadedModelsViewModel: DownloadedModelsViewModel,
    onNavigateToDetails: (modelId: String, hubBrowseMode: ModelHubBrowseMode) -> Unit,
    onSelectModelAndGoBack: (LocalModelEntity) -> Unit,
    modifier: Modifier = Modifier,
    settingsViewModel: SettingsViewModel = koinViewModel(),
    rolloutModeSource: RecommendationRolloutModeSource = koinInject(),
    initialTabIndex: Int = 0,
) {
    var selectedTabIndex by rememberSaveable { mutableStateOf(initialTabIndex.coerceIn(0, 1)) }
    val modelsStateHolder = rememberSaveableStateHolder()
    var deviceInfoVisible by rememberSaveable { mutableStateOf(false) }

    val storageInfo by modelViewModel.storageInfo.collectAsState()
    val settings by settingsViewModel.settings.collectAsState()
    val settingsLoaded by settingsViewModel.settingsLoaded.collectAsState()
    val effectiveProfile by settingsViewModel.effectiveRecommendationProfile.collectAsState()
    val profileSaving by settingsViewModel.isRecommendationProfileSaving.collectAsState()
    val profileError by settingsViewModel.recommendationProfileError.collectAsState()
    val quickCalibration by modelViewModel.quickCalibration.collectAsState()
    val downloadQueue by modelViewModel.downloadQueue.collectAsState()
    var queueVisible by remember { mutableStateOf(false) }
    val persistedProfileState = profileUiState(
        settings = settings,
        rolloutMode = rolloutModeSource.current(),
        settingsLoaded = settingsLoaded,
    )
    val recommendationProfileState = persistedProfileState.copy(profile = effectiveProfile)
    var profileEditorVisible by remember { mutableStateOf(false) }

    var recommendationSheetState by remember { mutableStateOf<RecommendedModelUiState?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }

    if (deviceInfoVisible) ModelHubDeviceInfo(
        storageInfo = storageInfo,
        profile = recommendationProfileState.profile.takeIf { recommendationProfileState.isAvailable },
        onBack = { deviceInfoVisible = false },
        onRefresh = modelViewModel::refreshDeviceInfo,
        onOpenProfile = if (recommendationProfileState.isAvailable) ({ profileEditorVisible = true }) else null,
        modifier = modifier,
    ) else modelsStateHolder.SaveableStateProvider("models") {
      ModelHubScreenLayout(
        selectedTabIndex = selectedTabIndex,
        onTabSelected = { selectedTabIndex = it },
        sharedContext = {
            ModelHubContextStrip(
                storageInfo = storageInfo,
                profile = if (recommendationProfileState.isAvailable) recommendationProfileState.profile else null,
                onOpenProfile = if (recommendationProfileState.isAvailable) ({
                    profileEditorVisible = true
                }) else null,
                onOpenDevice = {
                    modelViewModel.refreshDeviceInfo()
                    deviceInfoVisible = true
                },
                modifier = Modifier.padding(bottom = AppTheme.spacing.spacing16),
            )
        },
        modifier = modifier,
        snackbarHostState = snackbarHostState,
        discoverContent = {
            SearchTabContent(
                viewModel = modelViewModel,
                onNavigateToDetails = onNavigateToDetails,
                onRecommendationInfoClick = { recommendationSheetState = it },
                onOpenLibrary = { selectedTabIndex = 1 },
                downloads = {
                    ModelDownloadQueueEntry(downloadQueue, onClick = { queueVisible = true },
                        onPause = modelViewModel::pauseDownload, onResume = modelViewModel::resumeDownload,
                        onRetry = modelViewModel::retryDownload, onCancel = modelViewModel::cancelDownload)
                },
                modifier = Modifier.fillMaxSize(),
            )
        },
        libraryContent = {
            DownloadedTabContent(
                viewModel = downloadedModelsViewModel,
                onSelectModelAndGoBack = onSelectModelAndGoBack,
                onNavigateToDetails = onNavigateToDetails,
                snackbarHostState = snackbarHostState,
                onExploreModels = { selectedTabIndex = 0 },
                modifier = Modifier.fillMaxSize(),
            )
        },
      )
    }

    if (queueVisible) {
        ModelDownloadQueueSheet(
            batches = downloadQueue,
            onDismiss = { queueVisible = false },
            onPause = modelViewModel::pauseDownload,
            onResume = modelViewModel::resumeDownload,
            onRetry = modelViewModel::retryDownload,
            onCancel = modelViewModel::cancelDownload,
        )
    }

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

    if (profileEditorVisible && recommendationProfileState.isAvailable) {
        RecommendationProfileEditorSheet(
            profile = recommendationProfileState.profile,
            saving = profileSaving,
            errorMessage = profileError,
            onDismiss = { profileEditorVisible = false },
            onRiskToleranceChange = settingsViewModel::updateRiskTolerance,
            onOptimizationPriorityChange = settingsViewModel::updateOptimizationPriority,
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

/** Route chrome stays fixed; secondary context travels with the results. */
private val LocalModelHubListHeader = staticCompositionLocalOf<(@Composable () -> Unit)?> { null }

private val LocalModelHubContext = staticCompositionLocalOf<(@Composable () -> Unit)?> { null }
private val LocalModelHubSnackbar = staticCompositionLocalOf<SnackbarHostState?> { null }

@Composable
internal fun ModelHubScreenLayout(
    selectedTabIndex: Int,
    onTabSelected: (Int) -> Unit,
    sharedContext: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState? = null,
    discoverContent: @Composable () -> Unit,
    libraryContent: @Composable () -> Unit,
    downloadQueueEntry: (@Composable () -> Unit)? = null,
) {
    val tabs = listOf("Discover", "Library")
    val tabStateHolder = rememberSaveableStateHolder()
    CompositionLocalProvider(
        LocalModelHubListHeader provides {
            BrandPageHeader(title = "Models", modifier = Modifier.padding(bottom = AppTheme.spacing.spacing12))
            ModelHubTabRow(tabs = tabs, selectedTabIndex = selectedTabIndex, onTabSelected = onTabSelected)
        },
        LocalModelHubContext provides {
            sharedContext?.invoke()
            downloadQueueEntry?.invoke()
        },
        LocalModelHubSnackbar provides snackbarHostState,
    ) {
        Box(modifier.fillMaxSize()) {
            tabStateHolder.SaveableStateProvider(selectedTabIndex) {
                when (selectedTabIndex) {
                    0 -> discoverContent()
                    1 -> libraryContent()
                }
            }
        }
    }
}

@Composable
private fun ModelHubTabRow(
    tabs: List<String>,
    selectedTabIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = AppTheme.spacing.spacing12)
            .testTag("model-tabs")
            .clip(AppTheme.shapes.medium)
            .background(AppTheme.colors.surfaceContainerLow.copy(
                alpha = if (AppTheme.softEffects) AppTheme.effects.contextStripSurface else 1f,
            ))
            .padding(5.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        tabs.forEachIndexed { index, title ->
            val selected = selectedTabIndex == index
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = AppTheme.spacing.spacing48)
                    .clip(AppTheme.shapes.small)
                    .background(if (selected) AppTheme.colors.surfaceContainerLowest else Color.Transparent)
                    .selectable(
                        selected = selected,
                        onClick = { onTabSelected(index) },
                        role = Role.Tab,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = title,
                    style = AppTheme.typography.labelLarge,
                    color = if (selected) {
                        AppTheme.colors.onSurface
                    } else {
                        AppTheme.colors.onSurfaceVariant
                    },
                )
            }
        }
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
    val pageHeader = LocalModelHubListHeader.current
    val sharedContext = LocalModelHubContext.current
    val snackbar = LocalModelHubSnackbar.current
    BoxWithConstraints(modifier) {
        // A fixed title, tabs, and search field exceed the usable height in landscape.
        // Keep them together in the result scroller instead of clipping the search field.
        val scrollHeader = pageHeader != null && maxHeight < 480.dp
        val stickyHeader: (@Composable () -> Unit)? = if (scrollHeader) null else {
            { pageHeader?.invoke(); command?.invoke() }
        }
        FrostedPageScaffold(
            kind = AppContentKind.ModelHub,
            modifier = Modifier.fillMaxSize(),
            header = stickyHeader,
            snackbarHost = { snackbar?.let { SnackbarHost(it) } },
        ) { insets ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().testTag("model-primary-results"),
                state = listState,
                contentPadding = PaddingValues(
                    top = insets.calculateTopPadding() + AppTheme.spacing.spacing16,
                    bottom = insets.calculateBottomPadding() + AppTheme.spacing.spacing24,
                ),
            ) {
                if (scrollHeader) {
                    item(key = "model-page-header") {
                        Column(Modifier.fillMaxWidth().padding(bottom = AppTheme.spacing.spacing24)) {
                            pageHeader()
                            command?.invoke()
                        }
                    }
                }
                item(key = "model-context") { sharedContext?.invoke(); context() }
                item(key = "model-toolbar") { toolbar() }
                item(key = "model-summary") { summary() }
                results()
            }
        }
    }
}

@Composable
internal fun SearchTabContent(
    viewModel: ModelViewModel,
    onNavigateToDetails: (modelId: String, hubBrowseMode: ModelHubBrowseMode) -> Unit,
    onRecommendationInfoClick: (RecommendedModelUiState) -> Unit,
    modifier: Modifier = Modifier,
    onOpenLibrary: (() -> Unit)? = null,
    downloads: (@Composable () -> Unit)? = null,
) {
    val browseMode by viewModel.browseMode.collectAsState()
    LaunchedEffect(viewModel) { viewModel.ensureDiscoverLoaded() }
    val searchQuery by viewModel.searchQuery.collectAsState()
    val results by viewModel.results.collectAsState()
    val listParams by viewModel.listParams.collectAsState()
    val modelOrdering by viewModel.modelOrdering.collectAsState()

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
    val activeFilterCount = listOf(
        listParams.minParams != ParameterRange.ZERO,
        listParams.maxParams != ParameterRange.SIX_B,
    ).count { it }
    val resetFilters = {
        viewModel.setParameterFilters(
            ParameterRange.ZERO,
            ParameterRange.SIX_B,
        )
    }
    val motion = LocalAuroraMotionPolicy.current

    ModelHubTabLayout(
        modifier = modifier,
        scrollResetKey = results.key,
        autoLoadKeys = if (isLlmHub) models.takeLast(5).mapNotNull { it.id }
            .toSet() else emptySet(),
        autoLoadEnabled = isLlmHub && results.canLoadMore && results.moreError == null,
        onNearEnd = viewModel::autoLoadNextPage,
        command = {
            Box(modifier = Modifier.fillMaxWidth()) {
                Box(Modifier.fillMaxWidth()) {
                    when (browseMode) {
                        ModelHubBrowseMode.LanguageModels -> SearchBar(
                            query = searchQuery,
                            onQueryChange = viewModel::updateSearchQuery,
                            onSearch = viewModel::performSearch,
                            onClear = viewModel::clearSearch,
                            modifier = Modifier.fillMaxWidth(),
                            errorMessage = results.inputError,
                        )

                        ModelHubBrowseMode.DiffusionImage -> SearchBar(
                            query = imageQuery,
                            onQueryChange = { imageQuery = it.take(MAX_LOCAL_MODEL_QUERY_LENGTH) },
                            onSearch = {},
                            onClear = { imageQuery = "" },
                            modifier = Modifier.fillMaxWidth(),
                        )

                        ModelHubBrowseMode.DiffusionVideo -> SearchBar(
                            query = videoQuery,
                            onQueryChange = { videoQuery = it.take(MAX_LOCAL_MODEL_QUERY_LENGTH) },
                            onSearch = {},
                            onClear = { videoQuery = "" },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
        context = {
            // The device profile is shared above the Discover and Library tabs.
        },
        toolbar = {},
        summary = {
                Column {
                    ModelHubInlineFilters(
                        resultLabel = when {
                            results.initialLoading && models.isEmpty() -> "Finding little brains…"
                            results.initialError != null -> "Discover unavailable"
                            else -> "${models.size} ${if (models.size == 1) "model" else "models"} loaded"
                        },
                        mode = browseMode, ordering = modelOrdering,
                        minParams = listParams.minParams, maxParams = listParams.maxParams,
                        onApply = viewModel::applyDiscoverFilters,
                    )
                    if (isLlmHub && modelOrdering is ModelOrdering.Personalized) {
                        Text(
                            text = "Best fit among loaded models · ${results.assessedCount} of ${
                                results.models.size.coerceAtMost(
                                    96
                                )
                            } assessed (96 per session maximum)",
                            style = AppTheme.typography.bodySmall,
                            color = AppTheme.colors.onSurface,
                            modifier = Modifier.padding(horizontal = AppTheme.spacing.spacing12),
                        )
                        if (results.canAssessMore) {
                            BrandButton(style = BrandButtonStyle.Secondary, onClick = viewModel::loadMoreRecommendations) {
                                Text("Assess more loaded models")
                            }
                        }
                    }
                    downloads?.invoke()
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
                    activeFilterCount > 0 -> "No models match the active filters."
                    else -> "No models are available yet."
                },
                errorActionLabel = "Retry",
                onErrorAction = viewModel::loadModels,
                errorSecondaryActionLabel = if (onOpenLibrary != null) "Open library" else null,
                onErrorSecondaryAction = onOpenLibrary,
                emptyActionLabel = if (committedQuery.isNotBlank() || curatedQuery.isNotBlank()) "Clear search" else if (activeFilterCount > 0) "Reset filters" else null,
                onEmptyAction = when {
                    committedQuery.isNotBlank() -> viewModel::clearSearch
                    curatedQuery.isNotBlank() -> ({ imageQuery = ""; videoQuery = "" })
                    activeFilterCount > 0 -> resetFilters
                    else -> null
                },
                motion = motion,
            ) { model, itemModifier ->
                val recommendation = model.id?.let(results.recommendationById::get)
                SearchListItem(
                    model = model,
                    modifier = itemModifier,
                    onClick = { model.id?.let { onNavigateToDetails(it, browseMode) } },
                    recommendationState = recommendation,
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
internal fun ModelHubResultSummary(
    resultCount: Int,
    resultNoun: String = "models",
    query: String? = null,
    activeFilterCount: Int = 0,
    onClearQuery: (() -> Unit)? = null,
    onResetFilters: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val noun = if (resultCount == 1) resultNoun.removeSuffix("s") else resultNoun
    val summary = buildList {
        query?.takeIf(String::isNotBlank)?.let { add("Query: “$it”") }
        if (activeFilterCount > 0) {
            add("$activeFilterCount ${if (activeFilterCount == 1) "filter" else "filters"} active")
        }
    }.joinToString(" · ").ifBlank { null }
    val actionLabel: String?
    val action: (() -> Unit)?
    if (activeFilterCount > 0 && onResetFilters != null) {
        actionLabel = "Reset filters"
        action = onResetFilters
    } else if (!query.isNullOrBlank() && onClearQuery != null) {
        actionLabel = "Clear query"
        action = onClearQuery
    } else {
        actionLabel = null
        action = null
    }
    ModelHubHeader(
        title = if (resultNoun == "models loaded") {
            "$resultCount ${if (resultCount == 1) "model" else "models"} loaded"
        } else {
            "$resultCount $noun"
        },
        summary = summary,
        actionLabel = actionLabel,
        onAction = action,
        modifier = modifier,
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

@Composable
internal fun SearchResultsSummary(
    query: String,
    resultCount: Int,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = AppTheme.spacing.spacing4),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Results for “$query” · $resultCount",
            style = AppTheme.typography.headingSmall,
            color = AppTheme.colors.onSurface,
            modifier = Modifier.weight(1f),
        )
        BrandButton(style = BrandButtonStyle.Secondary, onClick = onClear) {
            Text("Clear")
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

@Composable
internal fun DownloadedTabContent(
    viewModel: DownloadedModelsViewModel,
    onSelectModelAndGoBack: (LocalModelEntity) -> Unit,
    onNavigateToDetails: (modelId: String, hubBrowseMode: ModelHubBrowseMode) -> Unit,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
    onExploreModels: (() -> Unit)? = null,
) {
    val downloadedModels by viewModel.downloadedModels.collectAsState()
    val selectionMode by viewModel.selectionMode.collectAsState()
    val selectedIds by viewModel.selectedIds.collectAsState()
    val isDeleting by viewModel.isDeleting.collectAsState()
    val deleteResultMessage by viewModel.deleteResultMessage.collectAsState()
    LaunchedEffect(viewModel) { viewModel.setReadinessFilter(ReadinessFilter.ALL) }

    val scope = rememberCoroutineScope()
    val motion = LocalAuroraMotionPolicy.current
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var libraryQuery by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(deleteResultMessage) {
        val message = deleteResultMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.acknowledgeDeleteResult()
    }

    val normalizedLibraryQuery = libraryQuery.trim()
    val visibleDownloadedModels = if (normalizedLibraryQuery.isEmpty()) {
        downloadedModels
    } else {
        downloadedModels.filter { model ->
            model.id in selectedIds ||
                    model.modelId.contains(normalizedLibraryQuery, ignoreCase = true) ||
                    model.filename.contains(normalizedLibraryQuery, ignoreCase = true) ||
                    model.author?.contains(normalizedLibraryQuery, ignoreCase = true) == true
        }
    }
    ModelHubTabLayout(
        modifier = modifier,
        command = {
            SearchBar(
                query = libraryQuery,
                onQueryChange = { libraryQuery = it.take(MAX_LOCAL_MODEL_QUERY_LENGTH) },
                onSearch = {},
                onClear = { libraryQuery = "" },
                modifier = Modifier.fillMaxWidth(),
                placeholder = "Search your library",
            )
        },
        context = {
            // The device profile is shared above the Discover and Library tabs.
        },
        toolbar = {
            if (selectionMode && downloadedModels.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("model-toolbar"),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BrandButton(
                        style = BrandButtonStyle.Secondary,
                        onClick = viewModel::clearSelection,
                        enabled = !isDeleting,
                    ) {
                        Text("Cancel")
                    }
                    BrandButton(
                        style = BrandButtonStyle.Destructive,
                        onClick = { showDeleteConfirm = true },
                        enabled = selectedIds.isNotEmpty() && !isDeleting,
                    ) {
                        Text("Delete (${selectedIds.size})")
                    }
                }
            }
        },
        summary = {
            Text(
                text = "${visibleDownloadedModels.size} downloaded ${if (visibleDownloadedModels.size == 1) "model" else "models"}",
                style = AppTheme.typography.labelSmall,
                color = AppTheme.colors.onSurface,
                modifier = Modifier.fillMaxWidth().padding(vertical = 13.dp).testTag("model-summary"),
            )
        },
        results = {
            if (visibleDownloadedModels.isEmpty()) {
                item(key = "downloaded-empty") {
                    ModelHubStateView(
                        kind = ModelHubStateKind.Empty,
                        title = if (normalizedLibraryQuery.isEmpty()) "Your library is empty" else "No matching models",
                        message = if (normalizedLibraryQuery.isNotEmpty()) {
                            "No downloaded models match “$libraryQuery”."
                        } else {
                            "Download a model to start creating. Your saved models will appear here."
                        },
                        actionLabel = if (normalizedLibraryQuery.isNotEmpty()) "Clear search" else onExploreModels?.let { "Explore models" },
                        onAction = if (normalizedLibraryQuery.isNotEmpty()) ({ libraryQuery = "" }) else onExploreModels,
                    )
                }
            } else {
                itemsIndexed(
                    items = visibleDownloadedModels,
                    key = { _, model -> model.id },
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
                        DownloadedListItem(
                            model = model,
                            selectionMode = selectionMode,
                            isSelected = model.id in selectedIds,
                            onOpenModel = {
                                scope.launch { viewModel.trackModelUsage(model) }
                                onSelectModelAndGoBack(model)
                            },
                            onToggleSelect = { viewModel.toggleSelection(model) },
                            onLongPress = {
                                if (selectionMode) {
                                    viewModel.toggleSelection(model)
                                } else {
                                    viewModel.beginSelection(model)
                                }
                            },
                            onFixComponents = if (
                                model.componentStatus ==
                                LocalModelEntity.STATUS_PARTIAL
                            ) {
                                {
                                    val mode = when (model.modelType) {
                                        ModelType.VIDEO ->
                                            ModelHubBrowseMode.DiffusionVideo

                                        ModelType.IMAGE ->
                                            ModelHubBrowseMode.DiffusionImage

                                        else -> ModelHubBrowseMode.DiffusionImage
                                    }
                                    onNavigateToDetails(model.modelId, mode)
                                }
                            } else {
                                null
                            },
                        )
                    }
                }
            }
        },
    )

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { if (!isDeleting) showDeleteConfirm = false },
            shape = AppTheme.shapes.extraLarge,
            containerColor = AuroraSurfaceLevel.Floating.containerColor(AppTheme.colors),
            title = { Text("Remove downloads?") },
            text = { Text("Remove selected downloads from this device?") },
            confirmButton = {
                BrandButton(
                    style = BrandButtonStyle.Destructive,
                    onClick = {
                        showDeleteConfirm = false
                        viewModel.deleteSelected()
                    },
                    enabled = !isDeleting
                ) {
                    Text("Remove")
                }
            },
            dismissButton = {
                BrandButton(
                    style = BrandButtonStyle.Secondary,
                    onClick = { showDeleteConfirm = false },
                    enabled = !isDeleting
                ) {
                    Text("Cancel")
                }
            }
        )
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
