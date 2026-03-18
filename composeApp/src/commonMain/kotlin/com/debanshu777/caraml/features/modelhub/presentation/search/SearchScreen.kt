package com.debanshu777.caraml.features.modelhub.presentation.search

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.drawer.LocalDrawerController
import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import com.debanshu777.caraml.features.modelhub.presentation.downloaded.DownloadedModelsViewModel
import com.debanshu777.caraml.features.modelhub.presentation.downloaded.components.LocalModelListItem
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelListItem
import com.debanshu777.caraml.features.modelhub.presentation.search.components.SearchBar
import com.debanshu777.caraml.features.modelhub.presentation.search.components.SearchModelListItem
import com.debanshu777.caraml.features.modelhub.presentation.search.components.SortFilterChips
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    modelViewModel: ModelViewModel,
    downloadedModelsViewModel: DownloadedModelsViewModel,
    onNavigateToDetails: (String) -> Unit,
    onSelectModelAndGoBack: (LocalModelEntity) -> Unit,
    onBack: () -> Unit,
    isRootDestination: Boolean,
    modifier: Modifier = Modifier
) {
    var selectedTabIndex by remember { mutableStateOf(0) }
    val tabs = listOf("Search", "Downloaded")

    val storageInfo by modelViewModel.storageInfo.collectAsState()

    val drawerController = LocalDrawerController.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Models") },
                navigationIcon = {
                    if (isRootDestination) {
                        IconButton(onClick = { drawerController.toggle() }) {
                            Icon(Icons.Default.Menu, contentDescription = "Open menu")
                        }
                    } else {
                        TextButton(onClick = onBack) {
                            Text("← Back")
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(modifier = Modifier.padding(paddingValues)) {
            StorageInfoBar(storageInfo = storageInfo)
            PrimaryTabRow(selectedTabIndex = selectedTabIndex) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTabIndex == index,
                        onClick = { selectedTabIndex = index },
                        text = { Text(title) }
                    )
                }
            }

            when (selectedTabIndex) {
                0 -> SearchTabContent(
                    viewModel = modelViewModel,
                    onNavigateToDetails = onNavigateToDetails,
                    modifier = Modifier.fillMaxSize()
                )
                1 -> DownloadedTabContent(
                    viewModel = downloadedModelsViewModel,
                    onSelectModelAndGoBack = onSelectModelAndGoBack,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

@Composable
private fun SearchTabContent(
    viewModel: ModelViewModel,
    onNavigateToDetails: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val searchQuery by viewModel.searchQuery.collectAsState()
    val searchResponse by viewModel.searchResponse.collectAsState()
    val isSearchLoading by viewModel.isSearchLoading.collectAsState()
    val searchError by viewModel.searchError.collectAsState()

    val listParams by viewModel.listParams.collectAsState()
    val listResponse by viewModel.listResponse.collectAsState()
    val isListLoading by viewModel.isListLoading.collectAsState()
    val listError by viewModel.listError.collectAsState()

    val isSearchMode = searchQuery.isNotEmpty() || searchResponse != null

    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SearchBar(
                query = searchQuery,
                onQueryChange = { viewModel.updateSearchQuery(it) },
                onSearch = {
                    if (searchQuery.isNotEmpty()) {
                        viewModel.performSearch()
                    } else {
                        viewModel.loadModels()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
        }

        if (isSearchMode) {
            if (searchResponse != null || searchError != null) {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text(
                        text = "Results for \"$searchQuery\" (${searchResponse?.modelsCount ?: 0} models)",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                    TextButton(
                        onClick = {
                            viewModel.clearSearch()
                        }
                    ) {
                        Text("← Back to Browse")
                    }
                }
            }
        } else {
            SortFilterChips(
                sort = listParams.sort,
                minParams = listParams.minParams,
                maxParams = listParams.maxParams,
                onSortChange = { viewModel.updateParams(sort = it) },
                onMinParamsChange = { viewModel.updateParams(minParams = it) },
                onMaxParamsChange = { viewModel.updateParams(maxParams = it) }
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center
        ) {
            when {
                isSearchMode -> {
                    when {
                        isSearchLoading -> CircularProgressIndicator()
                        searchError != null -> Text(
                            text = searchError ?: "Something went wrong. Please try again.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )

                        searchResponse?.models.isNullOrEmpty() -> Text(
                            text = "No models found for \"$searchQuery\".",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        else -> LazyColumn(
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(
                                items = searchResponse?.models?.filterNotNull() ?: emptyList(),
                                key = { it.id ?: it.hashCode().toString() }
                            ) { model ->
                                SearchModelListItem(
                                    model = model,
                                    onClick = { model.id?.let { id -> onNavigateToDetails(id) } }
                                )
                            }
                        }
                    }
                }

                else -> {
                    when {
                        isListLoading -> CircularProgressIndicator()
                        listError != null -> Text(
                            text = listError ?: "Something went wrong. Please try again.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )

                        listResponse?.models.isNullOrEmpty() -> Text(
                            text = "No models found. Tap Search to try.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        else -> LazyColumn(
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(
                                items = listResponse?.models?.filterNotNull() ?: emptyList(),
                                key = { it.id ?: it.hashCode().toString() }
                            ) { model ->
                                ModelListItem(
                                    model = model,
                                    onClick = { model.id?.let { id -> onNavigateToDetails(id) } }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StorageInfoBar(
    storageInfo: StorageInfoUiState,
    modifier: Modifier = Modifier
) {
    if (storageInfo.totalDeviceBytes <= 0L) return

    val usedFraction = if (storageInfo.totalDeviceBytes > 0L) {
        (storageInfo.usedByModelsBytes.toFloat() / storageInfo.totalDeviceBytes).coerceIn(0f, 1f)
    } else 0f

    val animatedProgress by animateFloatAsState(
        targetValue = usedFraction,
        animationSpec = tween(durationMillis = 600)
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Device Storage",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "${formatStorageBytes(storageInfo.availableDeviceBytes)} free of ${formatStorageBytes(storageInfo.totalDeviceBytes)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = if (usedFraction > 0.85f) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Models: ${formatStorageBytes(storageInfo.usedByModelsBytes)}",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

private fun formatStorageBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex++
    }
    val display = if (value >= 100 || unitIndex == 0) {
        value.toInt().toString()
    } else {
        val rounded = kotlin.math.round(value * 10.0) / 10.0
        if (rounded % 1.0 == 0.0) rounded.toInt().toString() else rounded.toString()
    }
    return "$display ${units[unitIndex]}"
}

@Composable
private fun DownloadedTabContent(
    viewModel: DownloadedModelsViewModel,
    onSelectModelAndGoBack: (LocalModelEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    val downloadedModels by viewModel.downloadedModels.collectAsState()
    val scope = rememberCoroutineScope()

    Box(
        modifier = modifier,
        contentAlignment = if (downloadedModels.isEmpty()) Alignment.Center else Alignment.TopStart
    ) {
        if (downloadedModels.isEmpty()) {
            Text(
                text = "No downloaded models yet.\nBrowse and download models to see them here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp)
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize()
            ) {
                items(
                    items = downloadedModels,
                    key = { it.id }
                ) { model ->
                    LocalModelListItem(
                        model = model,
                        onClick = {
                            scope.launch {
                                viewModel.trackModelUsage(model)
                            }
                            onSelectModelAndGoBack(model)
                        }
                    )
                }
            }
        }
    }
}
