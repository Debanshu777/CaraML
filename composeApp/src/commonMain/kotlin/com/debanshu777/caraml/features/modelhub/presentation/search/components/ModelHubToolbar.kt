package com.debanshu777.caraml.features.modelhub.presentation.search.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.theme.AuroraSurfaceLevel
import com.debanshu777.caraml.features.modelhub.presentation.search.ModelHubBrowseMode
import com.debanshu777.caraml.features.modelhub.presentation.search.ModelOrdering
import com.debanshu777.huggingfacemanager.model.ModelSort
import com.debanshu777.huggingfacemanager.model.ParameterRange

@Composable
fun ModelHubToolbar(
    browseMode: ModelHubBrowseMode,
    onBrowseModeChange: (ModelHubBrowseMode) -> Unit,
    showSortFilters: Boolean,
    ordering: ModelOrdering,
    sort: ModelSort,
    minParams: ParameterRange,
    maxParams: ParameterRange,
    onSortChange: (ModelSort) -> Unit,
    onOrderingChange: (ModelOrdering) -> Unit,
    onMinParamsChange: (ParameterRange) -> Unit,
    onMaxParamsChange: (ParameterRange) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .testTag("model-toolbar"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ModelKindControls(
            browseMode = browseMode,
            onBrowseModeChange = onBrowseModeChange,
        )
        if (showSortFilters) {
            ModelSortAndFilterControls(
                ordering = ordering,
                sort = sort,
                minParams = minParams,
                maxParams = maxParams,
                onSortChange = onSortChange,
                onOrderingChange = onOrderingChange,
                onMinParamsChange = onMinParamsChange,
                onMaxParamsChange = onMaxParamsChange,
            )
        }
    }
}

@Composable
private fun RowScope.ModelKindControls(
    browseMode: ModelHubBrowseMode,
    onBrowseModeChange: (ModelHubBrowseMode) -> Unit,
) {
    listOf(
        ModelHubBrowseMode.LanguageModels to "Text",
        ModelHubBrowseMode.DiffusionImage to "Image",
        ModelHubBrowseMode.DiffusionVideo to "Video",
    ).forEach { (mode, label) ->
        FilterChip(
            selected = browseMode == mode,
            onClick = { onBrowseModeChange(mode) },
            label = { Text(label) },
            modifier = Modifier.heightIn(min = 48.dp),
            shape = MaterialTheme.shapes.extraSmall,
        )
    }
}

@Composable
internal fun RowScope.ModelSortAndFilterControls(
    ordering: ModelOrdering,
    sort: ModelSort,
    minParams: ParameterRange,
    maxParams: ParameterRange,
    onSortChange: (ModelSort) -> Unit,
    onOrderingChange: (ModelOrdering) -> Unit,
    onMinParamsChange: (ParameterRange) -> Unit,
    onMaxParamsChange: (ParameterRange) -> Unit,
) {
    var orderingExpanded by remember { mutableStateOf(false) }
    var filtersExpanded by remember { mutableStateOf(false) }
    val activeFilterCount = listOf(
        sort != ModelSort.TRENDING,
        minParams != ParameterRange.ZERO,
        maxParams != ParameterRange.SIX_B,
    ).count { it }
    val orderingLabel = when (ordering) {
        ModelOrdering.Personalized -> "Recommended"
        is ModelOrdering.Server -> ordering.value.displayName
    }

    OutlinedButton(
        onClick = { orderingExpanded = true },
        modifier = Modifier
            .heightIn(min = 48.dp)
            .semantics {
                contentDescription = "Sort models"
                stateDescription = orderingLabel
            },
        shape = MaterialTheme.shapes.extraSmall,
    ) {
        Text("Sort")
        Icon(
            imageVector = Icons.Default.ArrowDropDown,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
    }
    OutlinedButton(
        onClick = { filtersExpanded = true },
        modifier = Modifier
            .heightIn(min = 48.dp)
            .semantics {
                contentDescription = if (activeFilterCount == 0) {
                    "Filters"
                } else {
                    "Filters, $activeFilterCount active"
                }
                stateDescription = "$activeFilterCount active filters"
            },
        shape = MaterialTheme.shapes.extraSmall,
    ) {
        Icon(
            imageVector = Icons.Default.Tune,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Text(if (activeFilterCount == 0) "Filters" else "Filters ($activeFilterCount)")
    }

    if (orderingExpanded) {
        OrderingSheet(
            sort = sort,
            selected = ordering,
            onSelect = onOrderingChange,
            onDismiss = { orderingExpanded = false },
        )
    }
    if (filtersExpanded) {
        ModelFilterSheet(
            sort = sort,
            minParams = minParams,
            maxParams = maxParams,
            onSortChange = onSortChange,
            onMinParamsChange = onMinParamsChange,
            onMaxParamsChange = onMaxParamsChange,
            onDismiss = { filtersExpanded = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OrderingSheet(
    sort: ModelSort,
    selected: ModelOrdering,
    onSelect: (ModelOrdering) -> Unit,
    onDismiss: () -> Unit,
) {
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
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Sort models", style = MaterialTheme.typography.titleLarge)
            SelectionRow(
                label = "Recommended for me",
                selected = selected is ModelOrdering.Personalized,
                onClick = {
                    onSelect(ModelOrdering.Personalized)
                    onDismiss()
                },
            )
            SelectionRow(
                label = "Server: ${sort.displayName}",
                selected = selected is ModelOrdering.Server,
                onClick = {
                    onSelect(ModelOrdering.Server(sort))
                    onDismiss()
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelFilterSheet(
    sort: ModelSort,
    minParams: ParameterRange,
    maxParams: ParameterRange,
    onSortChange: (ModelSort) -> Unit,
    onMinParamsChange: (ParameterRange) -> Unit,
    onMaxParamsChange: (ParameterRange) -> Unit,
    onDismiss: () -> Unit,
) {
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
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Filters", style = MaterialTheme.typography.titleLarge)
            FilterSelectionGroup(
                title = "Sort by",
                options = ModelSort.entries.filter { it != ModelSort.SIMILAR },
                selected = sort,
                label = { it.displayName },
                onSelect = onSortChange,
            )
            FilterSelectionGroup(
                title = "Minimum parameters",
                options = ParameterRange.entries,
                selected = minParams,
                label = { it.displayName },
                onSelect = onMinParamsChange,
            )
            FilterSelectionGroup(
                title = "Maximum parameters",
                options = ParameterRange.entries,
                selected = maxParams,
                label = { it.displayName },
                onSelect = onMaxParamsChange,
            )
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text("Done")
            }
        }
    }
}

@Composable
private fun <T> FilterSelectionGroup(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    options.forEach { option ->
        SelectionRow(
            label = label(option),
            selected = option == selected,
            onClick = { onSelect(option) },
        )
    }
}

@Composable
private fun SelectionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onClick,
            ),
        shape = MaterialTheme.shapes.extraSmall,
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, modifier = Modifier.weight(1f))
            RadioButton(selected = selected, onClick = null)
        }
    }
}

private val ModelSort.displayName: String
    get() = when (this) {
        ModelSort.TRENDING -> "Trending"
        ModelSort.LIKES -> "Likes"
        ModelSort.DOWNLOADS -> "Downloads"
        ModelSort.CREATED -> "Created"
        ModelSort.MODIFIED -> "Modified"
        ModelSort.MOST_PARAMS -> "Most params"
        ModelSort.LEAST_PARAMS -> "Least params"
        ModelSort.SIMILAR -> "Similar"
    }

private val ParameterRange.displayName: String
    get() = when (this) {
        ParameterRange.ZERO -> "0"
        ParameterRange.THREE_B -> "3B"
        ParameterRange.SIX_B -> "6B"
        ParameterRange.NINE_B -> "9B"
        ParameterRange.TWELVE_B -> "12B"
        ParameterRange.TWENTY_FOUR_B -> "24B"
        ParameterRange.THIRTY_TWO_B -> "32B"
    }
