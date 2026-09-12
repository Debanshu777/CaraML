package com.debanshu777.caraml.features.modelhub.presentation.search

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.drawer.LocalDrawerController
import com.debanshu777.caraml.core.recommendation.RecommendationProfile
import com.debanshu777.caraml.core.recommendation.RecommendationRolloutModeSource
import com.debanshu777.caraml.core.recommendation.RiskTolerance
import com.debanshu777.caraml.core.recommendation.OptimizationPriority
import com.debanshu777.caraml.core.rating.ui.RecommendationDetailsSheet
import com.debanshu777.caraml.core.rating.ui.recommendationPresentation
import com.debanshu777.caraml.core.theme.AuroraSurfaceLevel
import com.debanshu777.caraml.core.theme.LocalSpacing
import com.debanshu777.caraml.core.ui.components.CaraMLTopBar
import com.debanshu777.caraml.core.ui.components.TopBarNavigation
import com.debanshu777.caraml.core.ui.layout.AppContentKind
import com.debanshu777.caraml.core.ui.layout.ResponsiveContentPane
import com.debanshu777.caraml.core.ui.motion.LocalAuroraMotionPolicy
import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import com.debanshu777.caraml.features.modelhub.presentation.downloaded.DownloadedModelsViewModel
import com.debanshu777.caraml.features.modelhub.presentation.downloaded.ReadinessFilter
import com.debanshu777.caraml.features.modelhub.presentation.downloaded.components.LocalModelListItem
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelListItem
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelHubOverview
import com.debanshu777.caraml.features.modelhub.presentation.search.components.RecommendationProfileDialog
import com.debanshu777.caraml.features.modelhub.presentation.search.components.QuickCalibrationDialog
import com.debanshu777.caraml.features.modelhub.presentation.search.components.SearchBar
import com.debanshu777.caraml.features.modelhub.presentation.search.components.SearchModelListItem
import com.debanshu777.caraml.features.modelhub.presentation.search.components.SortFilterChips
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
    val tabs = listOf("Search", "Downloaded")

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

    val drawerController = LocalDrawerController.current
    val snackbarHostState = remember { SnackbarHostState() }

    Scaffold(
        modifier = modifier,
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            CaraMLTopBar(
                title = "Models",
                navigation = TopBarNavigation.Menu,
                onNavigationClick = drawerController::toggle,
            )
        }
    ) { paddingValues ->
        ResponsiveContentPane(
            kind = AppContentKind.ModelHub,
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
            ) {
                ModelHubTabRow(
                    tabs = tabs,
                    selectedTabIndex = selectedTabIndex,
                    onTabSelected = { selectedTabIndex = it },
                )

                when (selectedTabIndex) {
                    0 -> SearchTabContent(
                        viewModel = modelViewModel,
                        storageInfo = storageInfo,
                        onNavigateToDetails = onNavigateToDetails,
                        onRecommendationInfoClick = { recommendationSheetState = it },
                        recommendationProfileState = recommendationProfileState,
                        onOpenProfileEditor = { profileEditorVisible = true },
                        modifier = Modifier.fillMaxSize(),
                    )

                    1 -> DownloadedTabContent(
                        viewModel = downloadedModelsViewModel,
                        storageInfo = storageInfo,
                        onSelectModelAndGoBack = onSelectModelAndGoBack,
                        onNavigateToDetails = onNavigateToDetails,
                        snackbarHostState = snackbarHostState,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
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
private fun ModelHubTabRow(
    tabs: List<String>,
    selectedTabIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val motion = LocalAuroraMotionPolicy.current
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = LocalSpacing.current.s),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(modifier = Modifier.padding(4.dp)) {
            tabs.forEachIndexed { index, title ->
                val selected = selectedTabIndex == index
                val containerColor by animateColorAsState(
                    targetValue = if (selected) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        Color.Transparent
                    },
                    animationSpec = tween(motion.opacityDurationMillis),
                )
                val contentColor by animateColorAsState(
                    targetValue = if (selected) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    animationSpec = tween(motion.opacityDurationMillis),
                )
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .selectable(
                            selected = selected,
                            onClick = { onTabSelected(index) },
                            role = Role.Tab,
                        ),
                    shape = MaterialTheme.shapes.large,
                    color = containerColor,
                    contentColor = contentColor,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(title, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchTabContent(
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

    val isLlmHub = browseMode == ModelHubBrowseMode.LanguageModels
    val isSearchMode = isLlmHub && (searchQuery.isNotEmpty() || searchResponse != null)

    val spacing = LocalSpacing.current
    val motion = LocalAuroraMotionPolicy.current
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(bottom = spacing.xxl),
    ) {
        item(key = "model-hub-overview") {
            ModelHubOverview(
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
        }

        item(key = "model-kind-filter") {
            ModelKindFilterRow(
                browseMode = browseMode,
                onBrowseModeChange = viewModel::setBrowseMode,
            )
        }

        if (!isLlmHub) {
            item(key = "curated-hint") {
                Text(
                    text = "Curated Hugging Face repositories supported by the on-device engine.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }

        if (isLlmHub) {
            item(key = "model-search") {
                SearchBar(
                    query = searchQuery,
                    onQueryChange = viewModel::updateSearchQuery,
                    onSearch = {
                        if (searchQuery.isNotEmpty()) {
                            viewModel.performSearch()
                        } else {
                            viewModel.loadModels()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }

        if (isSearchMode && (searchResponse != null || searchError != null)) {
            item(key = "search-summary") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Results for “$searchQuery” · ${searchResponse?.modelsCount ?: 0}",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = viewModel::clearSearch) {
                        Text("Clear")
                    }
                }
            }
        } else if (isLlmHub) {
            item(key = "sort-filters") {
                SortFilterChips(
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
            }
        }

        if (isSearchMode) {
            when {
                isSearchLoading -> item(key = "search-loading") {
                    ModelHubListMessage { CircularProgressIndicator() }
                }

                searchError != null -> item(key = "search-error") {
                    ModelHubListMessage {
                        Text(
                            text = searchError ?: "Something went wrong. Please try again.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                searchResponse?.models.isNullOrEmpty() -> item(key = "search-empty") {
                    ModelHubListMessage {
                        Text(
                            text = "No models found for “$searchQuery”.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                else -> items(
                    items = searchResponse?.models?.filterNotNull() ?: emptyList(),
                    key = { "search-${it.id ?: it.hashCode()}" },
                ) { model ->
                    val recommendationState = recommendedModels.firstOrNull {
                        it.repositoryId == model.id
                    }
                    SearchModelListItem(
                        model = model,
                        modifier = Modifier.animateItem(
                            fadeInSpec = tween(motion.opacityDurationMillis),
                            placementSpec = if (motion.spatialTransitionsEnabled) {
                                tween(motion.peerTransitionMillis)
                            } else {
                                null
                            },
                            fadeOutSpec = tween(motion.opacityDurationMillis),
                        ),
                        recommendationState = recommendationState,
                        onRecommendationInfoClick = recommendationState
                            ?.takeIf { it.personalizedResult != null }
                            ?.let { state -> { onRecommendationInfoClick(state) } },
                        onClick = {
                            model.id?.let { id -> onNavigateToDetails(id, browseMode) }
                        },
                    )
                }
            }
        } else {
            when {
                isListLoading -> item(key = "list-loading") {
                    ModelHubListMessage { CircularProgressIndicator() }
                }

                listError != null -> item(key = "list-error") {
                    ModelHubListMessage {
                        Text(
                            text = listError ?: "Something went wrong. Please try again.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                listResponse?.models.isNullOrEmpty() -> item(key = "list-empty") {
                    ModelHubListMessage {
                        Text(
                            text = if (isLlmHub) {
                                "No models found. Adjust the filters or search by name."
                            } else {
                                "No curated models are available."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                else -> items(
                    items = if (modelOrdering is ModelOrdering.Personalized) {
                        recommendedModels.map { it.sourceModel }
                    } else {
                        listResponse?.models?.filterNotNull() ?: emptyList()
                    },
                    key = { "browse-${it.id ?: it.hashCode()}" },
                ) { model ->
                    val recommendationState = recommendedModels.firstOrNull {
                        it.repositoryId == model.id
                    }
                    ModelListItem(
                        model = model,
                        modifier = Modifier.animateItem(
                            fadeInSpec = tween(motion.opacityDurationMillis),
                            placementSpec = if (motion.spatialTransitionsEnabled) {
                                tween(motion.peerTransitionMillis)
                            } else {
                                null
                            },
                            fadeOutSpec = tween(motion.opacityDurationMillis),
                        ),
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
        }
    }
}

@Composable
private fun ModelKindFilterRow(
    browseMode: ModelHubBrowseMode,
    onBrowseModeChange: (ModelHubBrowseMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val modes = listOf(
        ModelHubBrowseMode.LanguageModels to "LLM",
        ModelHubBrowseMode.DiffusionImage to "Image",
        ModelHubBrowseMode.DiffusionVideo to "Video",
    )
    LazyRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(modes, key = { it.first.name }) { (mode, label) ->
            FilterChip(
                selected = browseMode == mode,
                onClick = { onBrowseModeChange(mode) },
                label = { Text(label) },
                modifier = Modifier.heightIn(min = 48.dp),
                shape = MaterialTheme.shapes.small,
            )
        }
    }
}

@Composable
private fun ModelHubListMessage(
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 160.dp)
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        content()
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
private fun DownloadedTabContent(
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

    LaunchedEffect(deleteResultMessage) {
        val message = deleteResultMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.acknowledgeDeleteResult()
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = LocalSpacing.current.xxl),
    ) {
        item(key = "model-hub-overview") {
            ModelHubOverview(
                storageInfo = storageInfo,
                profile = null,
                onOpenProfile = null,
            )
        }

        if (selectionMode && downloadedModels.isNotEmpty()) {
            item(key = "selection-actions") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
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
        }

        if (!selectionMode) {
            item(key = "readiness-filters") {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(ReadinessFilter.entries, key = { it.name }) { filter ->
                        val label = when (filter) {
                            ReadinessFilter.ALL -> "All"
                            ReadinessFilter.READY -> "Ready"
                            ReadinessFilter.PARTIAL -> "Needs setup"
                        }
                        FilterChip(
                            selected = readinessFilter == filter,
                            onClick = { viewModel.setReadinessFilter(filter) },
                            label = { Text(label) },
                            modifier = Modifier.heightIn(min = 48.dp),
                            shape = MaterialTheme.shapes.small,
                        )
                    }
                }
            }
        }

        if (downloadedModels.isEmpty()) {
            item(key = "downloaded-empty") {
                ModelHubListMessage {
                    Text(
                        text = "No downloaded models yet.\nBrowse and download models to see them here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            items(
                items = downloadedModels,
                key = { it.id },
            ) { model ->
                LocalModelListItem(
                    model = model,
                    modifier = Modifier.animateItem(
                        fadeInSpec = tween(motion.opacityDurationMillis),
                        placementSpec = if (motion.spatialTransitionsEnabled) {
                            tween(motion.peerTransitionMillis)
                        } else {
                            null
                        },
                        fadeOutSpec = tween(motion.opacityDurationMillis),
                    ),
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
