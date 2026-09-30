package com.debanshu777.caraml.features.modelhub.presentation.search

import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.drawer.LocalAppWindowWidth
import com.debanshu777.caraml.core.recommendation.RecommendationProfile
import com.debanshu777.caraml.core.recommendation.RecommendationRolloutModeSource
import com.debanshu777.caraml.core.recommendation.RiskTolerance
import com.debanshu777.caraml.core.recommendation.OptimizationPriority
import com.debanshu777.caraml.core.rating.ui.RecommendationDetailsSheet
import com.debanshu777.caraml.core.rating.ui.recommendationPresentation
import com.debanshu777.caraml.core.theme.AuroraSurfaceLevel
import com.debanshu777.caraml.core.theme.LocalSpacing
import com.debanshu777.caraml.core.theme.auroraColors
import com.debanshu777.caraml.core.theme.prismShapes
import com.debanshu777.caraml.core.ui.components.CaraMLPrimaryTopBar
import com.debanshu777.caraml.core.ui.layout.AppContentKind
import com.debanshu777.caraml.core.ui.layout.ResponsiveContentPane
import com.debanshu777.caraml.core.ui.motion.LocalAuroraMotionPolicy
import com.debanshu777.caraml.core.ui.motion.AuroraMotionPolicy
import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import com.debanshu777.caraml.features.modelhub.presentation.downloaded.DownloadedModelsViewModel
import com.debanshu777.caraml.features.modelhub.presentation.downloaded.ReadinessFilter
import com.debanshu777.caraml.features.modelhub.presentation.downloaded.components.LocalModelListItem
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelListItem
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelHubContextStrip
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelHubHeader
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelHubStateKind
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelHubStateView
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelHubFilterButton
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelDownloadQueueEntry
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelDownloadQueueSheet
import com.debanshu777.caraml.features.modelhub.presentation.search.components.RecommendationProfileDialog
import com.debanshu777.caraml.features.modelhub.presentation.search.components.QuickCalibrationDialog
import com.debanshu777.caraml.features.modelhub.presentation.search.components.SearchBar
import com.debanshu777.caraml.features.modelhub.presentation.search.components.SearchModelListItem
import com.debanshu777.caraml.features.modelhub.domain.RecommendedModelUiState
import com.debanshu777.caraml.features.settings.presentation.RecommendationProfileSection
import com.debanshu777.caraml.features.settings.presentation.SettingsViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
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
) {
    var selectedTabIndex by remember { mutableStateOf(0) }

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
                modifier = Modifier.padding(bottom = LocalSpacing.current.s),
            )
        },
        downloadQueueEntry = if (downloadQueue.isNotEmpty()) {
            { ModelDownloadQueueEntry(downloadQueue, onClick = { queueVisible = true }) }
        } else null,
        modifier = modifier,
        snackbarHostState = snackbarHostState,
        discoverContent = {
            SearchTabContent(
                viewModel = modelViewModel,
                storageInfo = storageInfo,
                onNavigateToDetails = onNavigateToDetails,
                onRecommendationInfoClick = { recommendationSheetState = it },
                recommendationProfileState = recommendationProfileState,
                onOpenProfileEditor = { profileEditorVisible = true },
                modifier = Modifier.fillMaxSize(),
            )
        },
        libraryContent = {
            DownloadedTabContent(
                viewModel = downloadedModelsViewModel,
                storageInfo = storageInfo,
                onSelectModelAndGoBack = onSelectModelAndGoBack,
                onNavigateToDetails = onNavigateToDetails,
                snackbarHostState = snackbarHostState,
                modifier = Modifier.fillMaxSize(),
            )
        },
    )

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
    Scaffold(
        modifier = modifier,
        containerColor = Color.Transparent,
        snackbarHost = {
            if (snackbarHostState != null) SnackbarHost(snackbarHostState)
        },
        topBar = {
            CaraMLPrimaryTopBar(
                title = "Models",
                contentKind = AppContentKind.ModelHub,
            )
        },
    ) { paddingValues ->
        ResponsiveContentPane(
            kind = AppContentKind.ModelHub,
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                sharedContext?.invoke()
                downloadQueueEntry?.invoke()
                ModelHubTabRow(
                    tabs = tabs,
                    selectedTabIndex = selectedTabIndex,
                    onTabSelected = onTabSelected,
                )
                Box(modifier = Modifier.weight(1f).padding(top = LocalSpacing.current.s)) {
                    when (selectedTabIndex) {
                        0 -> discoverContent()
                        1 -> libraryContent()
                    }
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
            .testTag("model-tabs"),
    ) {
        tabs.forEachIndexed { index, title ->
            val selected = selectedTabIndex == index
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .selectable(
                        selected = selected,
                        onClick = { onTabSelected(index) },
                        role = Role.Tab,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .heightIn(min = 2.dp, max = 2.dp)
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                        ),
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
    windowWidth: Dp? = null,
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
    BoxWithConstraints(modifier = modifier) {
        val supportingWidth = 296.dp
        val paneGap = 16.dp
        val effectiveWindowWidth = windowWidth ?: maxWidth
        val useSupportingContext = false // Device context is shared above the tabs at every width.

        val primaryContent: @Composable (Modifier, Boolean) -> Unit =
            { primaryModifier, includeContext ->
                Column(primaryModifier) {
                    command?.let { commandContent ->
                        Box(Modifier.padding(bottom = 8.dp)) { commandContent() }
                    }
                    LazyColumn(
                        modifier = Modifier.weight(1f).testTag("model-primary-results"),
                        state = listState,
                        contentPadding = PaddingValues(bottom = LocalSpacing.current.xxl),
                    ) {
                        if (includeContext) {
                            item(key = "model-context") {
                                Box(Modifier.padding(bottom = 8.dp)) { context() }
                            }
                        }
                        item(key = "model-toolbar") {
                            Box(Modifier.padding(bottom = 4.dp)) { toolbar() }
                        }
                        item(key = "model-summary") {
                            summary()
                        }
                        results()
                    }
                }
            }

        if (useSupportingContext) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(paneGap),
            ) {
                primaryContent(Modifier.weight(1f), false)
                Column(
                    modifier = Modifier
                        .width(supportingWidth)
                        .testTag("model-supporting-context"),
                ) {
                    context()
                }
            }
        } else {
            primaryContent(Modifier.fillMaxSize(), true)
        }
    }
}

@Composable
internal fun SearchTabContent(
    viewModel: ModelViewModel,
    storageInfo: StorageInfoUiState,
    onNavigateToDetails: (modelId: String, hubBrowseMode: ModelHubBrowseMode) -> Unit,
    onRecommendationInfoClick: (RecommendedModelUiState) -> Unit,
    recommendationProfileState: RecommendationProfileUiState,
    onOpenProfileEditor: () -> Unit,
    modifier: Modifier = Modifier,
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
        listParams.minParams != com.debanshu777.huggingfacemanager.model.ParameterRange.ZERO,
        listParams.maxParams != com.debanshu777.huggingfacemanager.model.ParameterRange.SIX_B,
    ).count { it }
    val resetFilters = {
        viewModel.setParameterFilters(
            com.debanshu777.huggingfacemanager.model.ParameterRange.ZERO,
            com.debanshu777.huggingfacemanager.model.ParameterRange.SIX_B,
        )
    }
    val motion = LocalAuroraMotionPolicy.current

    ModelHubTabLayout(
        modifier = modifier,
        windowWidth = LocalAppWindowWidth.current,
        scrollResetKey = results.key,
        autoLoadKeys = if (isLlmHub) models.takeLast(5).mapNotNull { it.id }
            .toSet() else emptySet(),
        autoLoadEnabled = isLlmHub && results.canLoadMore && results.moreError == null,
        onNearEnd = viewModel::autoLoadNextPage,
        command = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(LocalSpacing.current.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
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
                ModelHubFilterButton(
                    mode = browseMode,
                    ordering = modelOrdering,
                    minParams = listParams.minParams,
                    maxParams = listParams.maxParams,
                    onApply = viewModel::applyDiscoverFilters,
                )
            }
        },
        context = {
            // The device profile is shared above the Discover and Library tabs.
        },
        toolbar = {},
        summary = {
            if (!results.initialLoading || models.isNotEmpty()) {
                Column {
                    ModelHubResultSummary(
                        resultCount = models.size,
                        resultNoun = "models loaded",
                        query = committedQuery.takeIf { isLlmHub && it.isNotBlank() }
                            ?: curatedQuery.takeIf(String::isNotBlank),
                        activeFilterCount = if (isLlmHub) activeFilterCount else 0,
                        onClearQuery = when (browseMode) {
                            ModelHubBrowseMode.LanguageModels -> viewModel::clearSearch
                            ModelHubBrowseMode.DiffusionImage -> ({ imageQuery = "" })
                            ModelHubBrowseMode.DiffusionVideo -> ({ videoQuery = "" })
                        },
                        onResetFilters = if (isLlmHub && activeFilterCount > 0) resetFilters else null,
                        modifier = Modifier.padding(horizontal = LocalSpacing.current.l),
                    )
                    if (isLlmHub && modelOrdering is ModelOrdering.Personalized) {
                        Text(
                            text = "Best fit among loaded models · ${results.assessedCount} of ${
                                results.models.size.coerceAtMost(
                                    96
                                )
                            } assessed (96 per session maximum)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = LocalSpacing.current.m),
                        )
                        if (results.canAssessMore) {
                            TextButton(onClick = viewModel::loadMoreRecommendations) {
                                Text("Assess more loaded models")
                            }
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
                    activeFilterCount > 0 -> "No models match the active filters."
                    else -> "No models are available yet."
                },
                errorActionLabel = "Retry",
                onErrorAction = viewModel::loadModels,
                emptyActionLabel = if (committedQuery.isNotBlank()) "Clear query" else if (activeFilterCount > 0) "Reset filters" else null,
                onEmptyAction = if (committedQuery.isNotBlank()) viewModel::clearSearch else if (activeFilterCount > 0) resetFilters else null,
                motion = motion,
            ) { model, itemModifier ->
                val recommendation = model.id?.let(results.recommendationById::get)
                ModelListItem(
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
        modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("model-page-footer"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when {
            error != null -> {
                Text(error, color = MaterialTheme.colorScheme.error)
                if (hasMore) {
                    FilledTonalButton(
                        onClick = onLoadMore,
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Text("Retry load more")
                    }
                } else {
                    Text("Refine your search above to explore more models.")
                    FilledTonalButton(
                        onClick = onStartOver,
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Text("Start over")
                    }
                }
            }

            loading -> {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                Text("Loading more models")
            }

            sessionLimitReached -> Text("$loadedCount models loaded · Session limit reached. Refine your search to explore more.")
            hasMore -> Unit // The visible last five model rows trigger the next page.
            else -> Text("All results loaded")
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
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Results for “$query” · $resultCount",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onClear) {
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
                    .padding(bottom = LocalSpacing.current.m)
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
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 24.dp, vertical = 8.dp)
                    .semantics { contentDescription = refreshLoadingDescription },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
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
    val spacing = LocalSpacing.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = MaterialTheme.prismShapes.modal,
        containerColor = AuroraSurfaceLevel.Floating.containerColor(MaterialTheme.colorScheme),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.l)
                .padding(bottom = spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(spacing.s),
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
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
internal fun DownloadedTabContent(
    viewModel: DownloadedModelsViewModel,
    storageInfo: StorageInfoUiState,
    onSelectModelAndGoBack: (LocalModelEntity) -> Unit,
    onNavigateToDetails: (modelId: String, hubBrowseMode: ModelHubBrowseMode) -> Unit,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier
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
        windowWidth = LocalAppWindowWidth.current,
        command = {
            SearchBar(
                query = libraryQuery,
                onQueryChange = { libraryQuery = it.take(MAX_LOCAL_MODEL_QUERY_LENGTH) },
                onSearch = {},
                onClear = { libraryQuery = "" },
                modifier = Modifier.fillMaxWidth(),
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
                    TextButton(
                        onClick = viewModel::clearSelection,
                        enabled = !isDeleting,
                    ) {
                        Text("Cancel")
                    }
                    FilledTonalButton(
                        onClick = { showDeleteConfirm = true },
                        enabled = selectedIds.isNotEmpty() && !isDeleting,
                    ) {
                        Text("Delete (${selectedIds.size})")
                    }
                }
            }
        },
        summary = {
            ModelHubResultSummary(
                resultCount = visibleDownloadedModels.size,
                resultNoun = "downloaded models",
                query = libraryQuery.takeIf(String::isNotBlank),
                activeFilterCount = 0,
                onClearQuery = { libraryQuery = "" },
                onResetFilters = null,
            )
        },
        results = {
            if (visibleDownloadedModels.isEmpty()) {
                item(key = "downloaded-empty") {
                    ModelHubStateView(
                        kind = ModelHubStateKind.Empty,
                        message = if (normalizedLibraryQuery.isNotEmpty()) {
                            "No downloaded models match “$libraryQuery”."
                        } else {
                            "No downloaded models yet. Browse and download models to see them here."
                        },
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
                        LocalModelListItem(
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
                                com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity.STATUS_PARTIAL
                            ) {
                                {
                                    val mode = when (model.modelType) {
                                        com.debanshu777.caraml.core.storage.localmodel.ModelType.VIDEO ->
                                            ModelHubBrowseMode.DiffusionVideo

                                        com.debanshu777.caraml.core.storage.localmodel.ModelType.IMAGE ->
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
            shape = MaterialTheme.prismShapes.modal,
            containerColor = AuroraSurfaceLevel.Floating.containerColor(MaterialTheme.colorScheme),
            title = { Text("Remove downloads?") },
            text = { Text("Remove selected downloads from this device?") },
            confirmButton = {
                TextButton(
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
                TextButton(
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
