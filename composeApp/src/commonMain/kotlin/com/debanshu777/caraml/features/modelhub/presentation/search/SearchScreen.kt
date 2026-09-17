package com.debanshu777.caraml.features.modelhub.presentation.search

import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
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
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelHubToolbar
import com.debanshu777.caraml.features.modelhub.presentation.search.components.RecommendationProfileDialog
import com.debanshu777.caraml.features.modelhub.presentation.search.components.QuickCalibrationDialog
import com.debanshu777.caraml.features.modelhub.presentation.search.components.SearchBar
import com.debanshu777.caraml.features.modelhub.presentation.search.components.SearchModelListItem
import com.debanshu777.caraml.features.modelhub.domain.RecommendedModelUiState
import com.debanshu777.caraml.features.settings.presentation.RecommendationProfileSection
import com.debanshu777.caraml.features.settings.presentation.SettingsViewModel
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
) {
    var selectedTabIndex by remember { mutableStateOf(0) }

    val storageInfo by modelViewModel.storageInfo.collectAsState()
    val settings by settingsViewModel.settings.collectAsState()
    val settingsLoaded by settingsViewModel.settingsLoaded.collectAsState()
    val effectiveProfile by settingsViewModel.effectiveRecommendationProfile.collectAsState()
    val profileSaving by settingsViewModel.isRecommendationProfileSaving.collectAsState()
    val profileError by settingsViewModel.recommendationProfileError.collectAsState()
    val quickCalibration by modelViewModel.quickCalibration.collectAsState()
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
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState? = null,
    discoverContent: @Composable () -> Unit,
    libraryContent: @Composable () -> Unit,
) {
    val tabs = listOf("Discover", "Library")
    Scaffold(
        modifier = modifier,
        containerColor = Color.Transparent,
        snackbarHost = {
            if (snackbarHostState != null) SnackbarHost(snackbarHostState)
        },
        topBar = { CaraMLPrimaryTopBar(title = "Models") },
    ) { paddingValues ->
        ResponsiveContentPane(
            kind = AppContentKind.ModelHub,
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                ModelHubTabRow(
                    tabs = tabs,
                    selectedTabIndex = selectedTabIndex,
                    onTabSelected = onTabSelected,
                )
                Box(modifier = Modifier.weight(1f)) {
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
) {
    BoxWithConstraints(modifier = modifier) {
        val supportingWidth = 296.dp
        val paneGap = 16.dp
        val effectiveWindowWidth = windowWidth ?: maxWidth
        val useSupportingContext = effectiveWindowWidth >= 840.dp &&
            maxWidth - supportingWidth - paneGap >= 480.dp

        val primaryContent: @Composable (Modifier, Boolean) -> Unit =
            { primaryModifier, includeContext ->
                LazyColumn(
                    modifier = primaryModifier.testTag("model-primary-results"),
                    contentPadding = PaddingValues(bottom = LocalSpacing.current.xxl),
                ) {
                    command?.let { commandContent ->
                        item(key = "model-command") {
                            Box(Modifier.padding(bottom = 8.dp)) { commandContent() }
                        }
                    }
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
    val searchQuery by viewModel.searchQuery.collectAsState()
    val searchResponse by viewModel.searchResponse.collectAsState()
    val isSearchLoading by viewModel.isSearchLoading.collectAsState()
    val searchError by viewModel.searchError.collectAsState()

    val listParams by viewModel.listParams.collectAsState()
    val listResponse by viewModel.listResponse.collectAsState()
    val isListLoading by viewModel.isListLoading.collectAsState()
    val listError by viewModel.listError.collectAsState()
    val modelOrdering by viewModel.modelOrdering.collectAsState()
    val recommendedModels by viewModel.recommendedModels.collectAsState()

    var imageQuery by rememberSaveable { mutableStateOf("") }
    var videoQuery by rememberSaveable { mutableStateOf("") }

    val isLlmHub = browseMode == ModelHubBrowseMode.LanguageModels
    val isSearchMode = isLlmHub && (searchQuery.isNotEmpty() || searchResponse != null)

    val motion = LocalAuroraMotionPolicy.current
    val browseModels = if (modelOrdering is ModelOrdering.Personalized) {
        recommendedModels.map { it.sourceModel }
    } else {
        listResponse?.models?.filterNotNull() ?: emptyList()
    }
    val curatedQuery = when (browseMode) {
        ModelHubBrowseMode.LanguageModels -> ""
        ModelHubBrowseMode.DiffusionImage -> imageQuery
        ModelHubBrowseMode.DiffusionVideo -> videoQuery
    }
    val normalizedCuratedQuery = curatedQuery.trim()
    val visibleBrowseModels = if (isLlmHub || normalizedCuratedQuery.isEmpty()) {
        browseModels
    } else {
        browseModels.filter { model ->
            model.id.orEmpty().contains(normalizedCuratedQuery, ignoreCase = true)
        }
    }
    val activeFilterCount = listOf(
        listParams.sort != com.debanshu777.huggingfacemanager.model.ModelSort.TRENDING,
        listParams.minParams != com.debanshu777.huggingfacemanager.model.ParameterRange.ZERO,
        listParams.maxParams != com.debanshu777.huggingfacemanager.model.ParameterRange.SIX_B,
    ).count { it }
    val visibleActiveFilterCount = if (isLlmHub && !isSearchMode) activeFilterCount else 0
    val visibleResultCount = if (isSearchMode) {
        searchResponse?.modelsCount ?: searchResponse?.models?.filterNotNull()?.size ?: 0
    } else if (!isLlmHub) {
        visibleBrowseModels.size
    } else if (modelOrdering is ModelOrdering.Personalized) {
        visibleBrowseModels.size
    } else {
        listResponse?.numTotalItems ?: visibleBrowseModels.size
    }
    val resetFilters = {
        viewModel.updateParams(
            sort = com.debanshu777.huggingfacemanager.model.ModelSort.TRENDING,
            minParams = com.debanshu777.huggingfacemanager.model.ParameterRange.ZERO,
            maxParams = com.debanshu777.huggingfacemanager.model.ParameterRange.SIX_B,
        )
        viewModel.setModelOrdering(
            ModelOrdering.Server(com.debanshu777.huggingfacemanager.model.ModelSort.TRENDING),
        )
        viewModel.loadModels()
    }

    ModelHubTabLayout(
        modifier = modifier,
        windowWidth = LocalAppWindowWidth.current,
        command = {
            when (browseMode) {
                ModelHubBrowseMode.LanguageModels -> SearchBar(
                    query = searchQuery,
                    onQueryChange = viewModel::updateSearchQuery,
                    onSearch = {
                        when {
                            searchQuery.isNotEmpty() -> viewModel.performSearch()
                            searchResponse != null || searchError != null -> viewModel.clearSearch()
                            else -> viewModel.loadModels()
                        }
                    },
                    onClear = viewModel::clearSearch,
                    modifier = Modifier.fillMaxWidth(),
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
        },
        context = {
            ModelHubContextStrip(
                storageInfo = storageInfo,
                profile = if (recommendationProfileState.isAvailable) {
                    recommendationProfileState.profile
                } else {
                    null
                },
                onOpenProfile = if (recommendationProfileState.isAvailable) {
                    onOpenProfileEditor
                } else {
                    null
                },
            )
        },
        toolbar = {
            ModelHubToolbar(
                browseMode = browseMode,
                onBrowseModeChange = viewModel::setBrowseMode,
                showSortFilters = isLlmHub && !(
                    isSearchMode && (searchResponse != null || searchError != null)
                ),
                ordering = modelOrdering,
                sort = listParams.sort,
                minParams = listParams.minParams,
                maxParams = listParams.maxParams,
                onSortChange = {
                    viewModel.updateParams(sort = it)
                    viewModel.setModelOrdering(ModelOrdering.Server(it))
                },
                onOrderingChange = viewModel::setModelOrdering,
                onMinParamsChange = { viewModel.updateParams(minParams = it) },
                onMaxParamsChange = { viewModel.updateParams(maxParams = it) },
            )
        },
        summary = {
            ModelHubResultSummary(
                resultCount = visibleResultCount,
                resultNoun = if (isSearchMode) "results" else "models",
                query = if (isSearchMode) {
                    searchQuery
                } else {
                    curatedQuery.takeIf(String::isNotBlank)
                },
                activeFilterCount = visibleActiveFilterCount,
                onClearQuery = when (browseMode) {
                    ModelHubBrowseMode.LanguageModels -> viewModel::clearSearch
                    ModelHubBrowseMode.DiffusionImage -> ({ imageQuery = "" })
                    ModelHubBrowseMode.DiffusionVideo -> ({ videoQuery = "" })
                },
                onResetFilters = if (visibleActiveFilterCount > 0) resetFilters else null,
            )
        },
        results = {
            if (isSearchMode) {
                modelHubResultItems(
                    isLoading = isSearchLoading,
                    hasResponse = searchResponse != null,
                    errorMessage = searchError,
                    models = searchResponse?.models?.filterNotNull() ?: emptyList(),
                    itemKey = { "search-${it.id ?: it.hashCode()}" },
                    blockingLoadingKey = "search-loading",
                    refreshLoadingKey = "search-refreshing",
                    errorKey = "search-error",
                    emptyKey = "search-empty",
                    blockingLoadingDescription = "Loading search results",
                    refreshLoadingDescription = "Refreshing search results",
                    emptyMessage = "No models match “$searchQuery”.",
                    errorActionLabel = "Retry",
                    onErrorAction = viewModel::performSearch,
                    motion = motion,
                ) { model, itemModifier ->
                    val recommendationState = recommendedModels.firstOrNull {
                        it.repositoryId == model.id
                    }
                    SearchModelListItem(
                        model = model,
                        modifier = itemModifier,
                        recommendationState = recommendationState,
                        onRecommendationInfoClick = recommendationState
                            ?.takeIf { it.personalizedResult != null }
                            ?.let { state -> { onRecommendationInfoClick(state) } },
                        onClick = {
                            model.id?.let { id -> onNavigateToDetails(id, browseMode) }
                        },
                    )
                }
            } else {
                modelHubResultItems(
                    isLoading = isListLoading,
                    hasResponse = listResponse != null,
                    errorMessage = listError,
                    models = visibleBrowseModels,
                    itemKey = { "browse-${it.id ?: it.hashCode()}" },
                    blockingLoadingKey = "list-loading",
                    refreshLoadingKey = "list-refreshing",
                    errorKey = "list-error",
                    emptyKey = "list-empty",
                    blockingLoadingDescription = "Loading models",
                    refreshLoadingDescription = "Refreshing models",
                    emptyMessage = if (!isLlmHub && normalizedCuratedQuery.isNotEmpty()) {
                        "No curated models match “$curatedQuery”."
                    } else if (visibleActiveFilterCount > 0) {
                        "No models match the active filters."
                    } else if (isLlmHub) {
                        "No models are available yet."
                    } else {
                        "No curated models are available."
                    },
                    errorActionLabel = "Retry",
                    onErrorAction = {
                        if (isLlmHub) viewModel.loadModels() else viewModel.setBrowseMode(browseMode)
                    },
                    motion = motion,
                ) { model, itemModifier ->
                    val recommendationState = recommendedModels.firstOrNull {
                        it.repositoryId == model.id
                    }
                    ModelListItem(
                        model = model,
                        modifier = itemModifier,
                        onClick = {
                            model.id?.let { id -> onNavigateToDetails(id, browseMode) }
                        },
                        recommendationState = recommendationState,
                        onRecommendationInfoClick = recommendationState
                            ?.takeIf { it.personalizedResult != null }
                            ?.let { state -> { onRecommendationInfoClick(state) } },
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
        title = "$resultCount $noun",
        summary = summary,
        actionLabel = actionLabel,
        onAction = action,
        modifier = modifier,
    )
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
                    .animateItem(
                        fadeInSpec = tween(motion.opacityDurationMillis),
                        placementSpec = if (motion.spatialTransitionsEnabled) {
                            tween(motion.peerTransitionMillis)
                        } else {
                            null
                        },
                        fadeOutSpec = tween(motion.opacityDurationMillis),
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
        shape = MaterialTheme.shapes.extraLarge,
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
    val readinessFilter by viewModel.readinessFilter.collectAsState()

    val scope = rememberCoroutineScope()
    val motion = LocalAuroraMotionPolicy.current
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var libraryQuery by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(deleteResultMessage) {
        val message = deleteResultMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.acknowledgeDeleteResult()
    }

    val readinessFilterCount = if (readinessFilter == ReadinessFilter.ALL) 0 else 1
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
            ModelHubContextStrip(
                storageInfo = storageInfo,
                profile = null,
                onOpenProfile = null,
            )
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
            } else {
                LibraryReadinessToolbar(
                    selected = readinessFilter,
                    onSelected = viewModel::setReadinessFilter,
                )
            }
        },
        summary = {
            ModelHubResultSummary(
                resultCount = visibleDownloadedModels.size,
                resultNoun = "downloaded models",
                query = libraryQuery.takeIf(String::isNotBlank),
                activeFilterCount = readinessFilterCount,
                onClearQuery = { libraryQuery = "" },
                onResetFilters = { viewModel.setReadinessFilter(ReadinessFilter.ALL) },
            )
        },
        results = {
            if (visibleDownloadedModels.isEmpty()) {
                item(key = "downloaded-empty") {
                    ModelHubStateView(
                        kind = ModelHubStateKind.Empty,
                        message = if (normalizedLibraryQuery.isNotEmpty()) {
                            "No downloaded models match “$libraryQuery”."
                        } else if (readinessFilterCount > 0) {
                            "No downloaded models match the active filter."
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
                                fadeInSpec = tween(motion.opacityDurationMillis),
                                placementSpec = if (motion.spatialTransitionsEnabled) {
                                    tween(motion.peerTransitionMillis)
                                } else {
                                    null
                                },
                                fadeOutSpec = tween(motion.opacityDurationMillis),
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
            shape = MaterialTheme.shapes.extraLarge,
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

@Composable
private fun LibraryReadinessToolbar(
    selected: ReadinessFilter,
    onSelected: (ReadinessFilter) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .testTag("model-toolbar"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ReadinessFilter.entries.forEach { filter ->
            val label = when (filter) {
                ReadinessFilter.ALL -> "All"
                ReadinessFilter.READY -> "Ready"
                ReadinessFilter.PARTIAL -> "Needs setup"
            }
            FilterChip(
                selected = selected == filter,
                onClick = { onSelected(filter) },
                label = { Text(label) },
                modifier = Modifier.heightIn(min = 48.dp),
                shape = MaterialTheme.shapes.extraSmall,
            )
        }
    }
}

private const val MAX_LOCAL_MODEL_QUERY_LENGTH = 200
