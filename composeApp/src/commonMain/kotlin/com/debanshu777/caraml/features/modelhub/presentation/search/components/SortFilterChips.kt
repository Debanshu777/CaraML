package com.debanshu777.caraml.features.modelhub.presentation.search.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.debanshu777.huggingfacemanager.model.ModelSort
import com.debanshu777.huggingfacemanager.model.ParameterRange
import com.debanshu777.caraml.features.modelhub.presentation.search.ModelHubBrowseMode
import com.debanshu777.caraml.features.modelhub.presentation.search.ModelOrdering
import com.debanshu777.caraml.core.theme.AuroraSurfaceLevel

@Composable
fun SortFilterChips(
    ordering: ModelOrdering,
    sort: ModelSort,
    minParams: ParameterRange,
    maxParams: ParameterRange,
    onSortChange: (ModelSort) -> Unit,
    onOrderingChange: (ModelOrdering) -> Unit,
    onMinParamsChange: (ParameterRange) -> Unit,
    onMaxParamsChange: (ParameterRange) -> Unit,
    modifier: Modifier = Modifier
) {
    ResponsiveControlContainer(modifier = modifier) {
        SortFilterControlItems(
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

@Composable
fun ModelHubBrowseControls(
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
    ResponsiveControlContainer(modifier = modifier) {
        ModelKindControlItems(
            browseMode = browseMode,
            onBrowseModeChange = onBrowseModeChange,
        )
        if (showSortFilters) {
            SortFilterControlItems(
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
private fun ResponsiveControlContainer(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        if (maxWidth < 600.dp) {
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                content()
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                content()
            }
        }
    }
}

@Composable
private fun ModelKindControlItems(
    browseMode: ModelHubBrowseMode,
    onBrowseModeChange: (ModelHubBrowseMode) -> Unit,
) {
    val modes = listOf(
        ModelHubBrowseMode.LanguageModels to "LLM",
        ModelHubBrowseMode.DiffusionImage to "Image",
        ModelHubBrowseMode.DiffusionVideo to "Video",
    )
    modes.forEach { (mode, label) ->
        FilterChip(
            selected = browseMode == mode,
            onClick = { onBrowseModeChange(mode) },
            label = { Text(label) },
            modifier = Modifier.heightIn(min = 48.dp),
            shape = MaterialTheme.shapes.small,
        )
    }
}

@Composable
private fun SortFilterControlItems(
    ordering: ModelOrdering,
    sort: ModelSort,
    minParams: ParameterRange,
    maxParams: ParameterRange,
    onSortChange: (ModelSort) -> Unit,
    onOrderingChange: (ModelOrdering) -> Unit,
    onMinParamsChange: (ParameterRange) -> Unit,
    onMaxParamsChange: (ParameterRange) -> Unit,
) {
    SortDropdown(
        label = if (ordering is ModelOrdering.Personalized) "Recommended" else "Server order",
        options = listOf(ModelOrdering.Personalized, ModelOrdering.Server(sort)),
        selected = ordering,
        highlighted = ordering is ModelOrdering.Personalized,
        onSelect = onOrderingChange,
    )
    SortDropdown(
        label = "Sort: ${sort.displayName}",
        options = ModelSort.entries.filter { it != ModelSort.SIMILAR },
        selected = sort,
        highlighted = sort != ModelSort.TRENDING,
        onSelect = onSortChange,
    )
    SortDropdown(
        label = "Min: ${minParams.displayName}",
        options = ParameterRange.entries,
        selected = minParams,
        highlighted = minParams != ParameterRange.ZERO,
        onSelect = onMinParamsChange,
    )
    SortDropdown(
        label = "Max: ${maxParams.displayName}",
        options = ParameterRange.entries,
        selected = maxParams,
        highlighted = maxParams != ParameterRange.SIX_B,
        onSelect = onMaxParamsChange,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> SortDropdown(
    label: String,
    options: List<T>,
    selected: T,
    highlighted: Boolean,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    Box(modifier = modifier) {
        FilterChip(
            selected = highlighted,
            onClick = { expanded = true },
            label = { Text(label) },
            modifier = Modifier.heightIn(min = 48.dp),
            shape = MaterialTheme.shapes.small,
            trailingIcon = {
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = "Choose $label",
                    modifier = Modifier.size(18.dp),
                )
            },
        )
    }
    if (expanded) {
        ModalBottomSheet(
            onDismissRequest = { expanded = false },
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
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
                options.forEach { option ->
                    val displayName = when (option) {
                        ModelOrdering.Personalized -> "Recommended for me"
                        is ModelOrdering.Server -> "Server: ${option.value.displayName}"
                        is ModelSort -> option.displayName
                        is ParameterRange -> option.displayName
                        else -> option.toString()
                    }
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(
                                selected = option == selected,
                                role = Role.RadioButton,
                                onClick = {
                                    onSelect(option)
                                    expanded = false
                                },
                            ),
                        shape = MaterialTheme.shapes.small,
                        color = if (option == selected) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                text = displayName,
                                style = MaterialTheme.typography.labelLarge,
                                color = if (option == selected) {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                modifier = Modifier.weight(1f),
                            )
                            RadioButton(
                                selected = option == selected,
                                onClick = null,
                            )
                        }
                    }
                }
            }
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
