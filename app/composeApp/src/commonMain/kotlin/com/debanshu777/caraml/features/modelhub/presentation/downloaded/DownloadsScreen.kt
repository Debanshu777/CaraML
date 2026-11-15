package com.debanshu777.caraml.features.modelhub.presentation.downloaded

import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import com.debanshu777.caraml.core.storage.localmodel.ModelType
import com.debanshu777.caraml.core.theme.AppTheme
import com.debanshu777.caraml.core.theme.AuroraSurfaceLevel
import com.debanshu777.caraml.core.ui.components.BrandButton
import com.debanshu777.caraml.core.ui.components.BrandButtonStyle
import com.debanshu777.caraml.core.ui.components.BrandPageHeader
import com.debanshu777.caraml.core.ui.icons.AppIcons
import com.debanshu777.caraml.core.ui.motion.LocalAuroraMotionPolicy
import com.debanshu777.caraml.features.modelhub.presentation.components.ModelRow
import com.debanshu777.caraml.features.modelhub.presentation.components.downloadedModelRow
import com.debanshu777.caraml.features.modelhub.presentation.search.ModelHubBrowseMode
import com.debanshu777.caraml.features.modelhub.presentation.search.ModelHubTabLayout
import com.debanshu777.caraml.features.modelhub.presentation.search.ModelViewModel
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelDownloadBatch
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelHubStateKind
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelHubStateView
import com.debanshu777.caraml.features.modelhub.presentation.search.components.SearchBar
import com.debanshu777.caraml.features.modelhub.presentation.search.components.pendingArtifacts
import kotlinx.coroutines.launch

@Composable
fun DownloadsScreen(
    modelViewModel: ModelViewModel,
    onBack: () -> Unit,
    downloadedModelsViewModel: DownloadedModelsViewModel,
    onSelectModelAndGoBack: (LocalModelEntity) -> Unit,
    onNavigateToDetails: (modelId: String, hubBrowseMode: ModelHubBrowseMode) -> Unit,

    modifier: Modifier = Modifier,
    onExploreModels: (() -> Unit)? = null,
) {
    val viewModel = downloadedModelsViewModel
    val snackbarHostState = remember { SnackbarHostState() }
    val downloadControlError by modelViewModel.downloadControlError.collectAsState()
    val downloadQueue by modelViewModel.downloadQueue.collectAsState()
    val activeBatches = downloadQueue.filter { it.pendingArtifacts.isNotEmpty() }
    LaunchedEffect(downloadControlError) {
        val message = downloadControlError ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        modelViewModel.clearDownloadControlError()
    }
    val downloadedModels by viewModel.downloadedModels.collectAsState()
    val selectionMode by viewModel.selectionMode.collectAsState()
    val selectedIds by viewModel.selectedIds.collectAsState()
    val isDeleting by viewModel.isDeleting.collectAsState()
    val deleteResultMessage by viewModel.deleteResultMessage.collectAsState()
    LaunchedEffect(viewModel) { viewModel.setReadinessFilter(ReadinessFilter.ALL) }

    val scope = rememberCoroutineScope()
    val motion = LocalAuroraMotionPolicy.current
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var localQuery by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(deleteResultMessage) {
        val message = deleteResultMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.acknowledgeDeleteResult()
    }

    val normalizedQuery = localQuery.trim()
    val visibleDownloadedModels = if (normalizedQuery.isEmpty()) {
        downloadedModels
    } else {
        downloadedModels.filter { model ->
            model.id in selectedIds ||
                    model.modelId.contains(normalizedQuery, ignoreCase = true) ||
                    model.filename.contains(normalizedQuery, ignoreCase = true) ||
                    model.author?.contains(normalizedQuery, ignoreCase = true) == true
        }
    }
    ModelHubTabLayout(
        modifier = modifier,
        snackbarHostState = snackbarHostState,
        header = {
            BrandPageHeader(
                title = "Downloads",
                navigationAction = onBack,
                navigationIcon = AppIcons.Back,
                navigationContentDescription = "Back",
                modifier = Modifier.padding(bottom = AppTheme.spacing.spacing8),
            )
        },
        command = {
            SearchBar(
                query = localQuery,
                onQueryChange = { localQuery = it.take(MAX_LOCAL_MODEL_QUERY_LENGTH) },
                onSearch = {},
                onClear = { localQuery = "" },
                modifier = Modifier.fillMaxWidth(),
                placeholder = "Search downloads",
            )
        },
        context = {
            if (activeBatches.isNotEmpty()) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.spacing8),
                    modifier = Modifier.padding(bottom = AppTheme.spacing.spacing16),
                ) {
                    Text("Download queue", style = AppTheme.typography.headingSmall, color = AppTheme.colors.onSurface)
                    activeBatches.forEach { batch ->
                        val artifact = batch.pendingArtifacts.first()
                        ModelDownloadBatch(
                            batch = batch,
                            onPause = { modelViewModel.pauseDownload(batch.batchId, artifact.artifactId) },
                            onResume = { modelViewModel.resumeDownload(batch.batchId, artifact.artifactId) },
                            onRetry = { modelViewModel.retryDownload(batch.batchId, artifact.artifactId) },
                            onCancel = { modelViewModel.cancelDownload(batch.batchId, artifact.artifactId) },
                        )
                    }
                }
            }
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
                        title = if (normalizedQuery.isEmpty()) "No downloaded models" else "No matching models",
                        message = if (normalizedQuery.isNotEmpty()) {
                            "No downloaded models match “$localQuery”."
                        } else {
                            "Download a model to start creating. Your saved models will appear here."
                        },
                        actionLabel = if (normalizedQuery.isNotEmpty()) "Clear search" else onExploreModels?.let { "Explore models" },
                        onAction = if (normalizedQuery.isNotEmpty()) ({ localQuery = "" }) else onExploreModels,
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
                        ModelRow(
                            state = downloadedModelRow(model, selectionMode, model.id in selectedIds),
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
