package com.debanshu777.caraml.features.modelhub.presentation.search.components

import com.debanshu777.caraml.core.ui.icons.AppIcons
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.theme.AppTheme
import com.debanshu777.caraml.core.ui.components.BrandButton
import com.debanshu777.caraml.core.ui.components.BrandButtonStyle
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
    onFiltersApplied: () -> Unit = {},
    onParameterFiltersApplied: ((ParameterRange, ParameterRange) -> Unit)? = null,
) {
    val spacing = AppTheme.spacing
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .testTag("model-toolbar"),
    ) {
        val compact = maxWidth < AppTheme.dimensions.size600
        if (compact) {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.spacing8)) {
                ModelKindControls(
                    browseMode = browseMode,
                    onBrowseModeChange = onBrowseModeChange,
                    modifier = Modifier.fillMaxWidth(),
                    equalWidth = true,
                )
                if (showSortFilters) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(spacing.spacing8),
                    ) {
                        ModelSortAndFilterControls(
                            ordering = ordering,
                            sort = sort,
                            minParams = minParams,
                            maxParams = maxParams,
                            onSortChange = onSortChange,
                            onOrderingChange = onOrderingChange,
                            onMinParamsChange = onMinParamsChange,
                            onMaxParamsChange = onMaxParamsChange,
                            onFiltersApplied = onFiltersApplied,
                            onParameterFiltersApplied = onParameterFiltersApplied,
                            expand = true,
                        )
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.spacing8),
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
                        onFiltersApplied = onFiltersApplied,
                        onParameterFiltersApplied = onParameterFiltersApplied,
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelKindControls(
    browseMode: ModelHubBrowseMode,
    onBrowseModeChange: (ModelHubBrowseMode) -> Unit,
    modifier: Modifier = Modifier,
    equalWidth: Boolean = false,
) {
    val colors = AppTheme.auroraColors
    Row(
        modifier = modifier
            .selectableGroup()
            .testTag("model-kind-group"),
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.spacing8),
    ) {
        listOf(
            ModelHubBrowseMode.LanguageModels to "Text",
            ModelHubBrowseMode.DiffusionImage to "Image",
            ModelHubBrowseMode.DiffusionVideo to "Video",
        ).forEach { (mode, label) ->
            val selected = browseMode == mode
            FilterChip(
                selected = selected,
                onClick = { onBrowseModeChange(mode) },
                label = { Text(label) },
                leadingIcon = if (selected) {
                    {
                        Icon(
                            imageVector = AppIcons.Check,
                            contentDescription = null,
                            modifier = Modifier
                                .size(AppTheme.dimensions.size18)
                                .testTag("Selected model kind $label"),
                        )
                    }
                } else {
                    null
                },
                modifier = Modifier
                    .then(if (equalWidth) Modifier.weight(1f) else Modifier)
                    .heightIn(min = AppTheme.spacing.spacing48)
                    .semantics {
                        this.selected = selected
                        role = Role.RadioButton
                    },
                shape = AppTheme.shapes.small,
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = colors.focusPrimary,
                    selectedLabelColor = colors.onFocusPrimary,
                    selectedLeadingIconColor = colors.onFocusPrimary,
                ),
            )
        }
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
    onFiltersApplied: () -> Unit = {},
    expand: Boolean = false,
    onParameterFiltersApplied: ((ParameterRange, ParameterRange) -> Unit)? = null,
) {
    var orderingExpanded by remember { mutableStateOf(false) }
    var filtersExpanded by remember { mutableStateOf(false) }
    val activeFilterCount = listOf(
        minParams != ParameterRange.ZERO,
        maxParams != ParameterRange.SIX_B,
    ).count { it }
    val orderingLabel = when (ordering) {
        ModelOrdering.Personalized -> "Recommended"
        is ModelOrdering.Server -> ordering.value.displayName
    }

    BrandButton(
        style = BrandButtonStyle.Secondary,
        onClick = { orderingExpanded = true },
        modifier = Modifier
            .then(if (expand) Modifier.weight(1f) else Modifier)
            .heightIn(min = AppTheme.spacing.spacing48)
            .semantics {
                contentDescription = "Sort models"
                stateDescription = orderingLabel
            },
    ) {
        Text("Sort")
        Icon(
            imageVector = AppIcons.ChevronDown,
            contentDescription = null,
            modifier = Modifier.size(AppTheme.dimensions.size18),
        )
    }
    BrandButton(
        style = BrandButtonStyle.Secondary,
        onClick = { filtersExpanded = true },
        modifier = Modifier
            .then(if (expand) Modifier.weight(1f) else Modifier)
            .heightIn(min = AppTheme.spacing.spacing48)
            .semantics {
                contentDescription = if (activeFilterCount == 0) {
                    "Filters"
                } else {
                    "Filters, $activeFilterCount active"
                }
                stateDescription = "$activeFilterCount active filters"
            },
    ) {
        Icon(
            imageVector = AppIcons.Filters,
            contentDescription = null,
            modifier = Modifier.size(AppTheme.dimensions.size18),
        )
        Text(if (activeFilterCount == 0) "Filters" else "Filters ($activeFilterCount)")
    }

    if (orderingExpanded) {
        OrderingSheet(
            sort = sort,
            selected = ordering,
            onSelect = { selectedOrdering ->
                if (selectedOrdering is ModelOrdering.Server) {
                    onSortChange(selectedOrdering.value)
                }
                onOrderingChange(selectedOrdering)
            },
            onDismiss = { orderingExpanded = false },
        )
    }
    if (filtersExpanded) {
        ModelFilterSheet(
            minParams = minParams,
            maxParams = maxParams,
            onMinParamsChange = onMinParamsChange,
            onMaxParamsChange = onMaxParamsChange,
            onFiltersApplied = onFiltersApplied,
            onParameterFiltersApplied = onParameterFiltersApplied,
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
        shape = AppTheme.shapes.extraLarge,
        containerColor = AuroraSurfaceLevel.Floating.containerColor(AppTheme.colors),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppTheme.spacing.spacing16)
                .padding(bottom = AppTheme.spacing.spacing32),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.spacing4),
        ) {
            Text("Sort models", style = AppTheme.typography.headingBase)
            SelectionRow(
                label = "Best fit among loaded models",
                selected = selected is ModelOrdering.Personalized,
                onClick = {
                    onSelect(ModelOrdering.Personalized)
                    onDismiss()
                },
            )
            Text("Hugging Face order", style = AppTheme.typography.headingSmall)
            HUB_SORT_OPTIONS.forEach { option ->
                SelectionRow(
                    label = option.displayName,
                    selected = selected is ModelOrdering.Server && sort == option,
                    onClick = {
                        onSelect(ModelOrdering.Server(option))
                        onDismiss()
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelFilterSheet(
    minParams: ParameterRange,
    maxParams: ParameterRange,
    onMinParamsChange: (ParameterRange) -> Unit,
    onMaxParamsChange: (ParameterRange) -> Unit,
    onFiltersApplied: () -> Unit,
    onParameterFiltersApplied: ((ParameterRange, ParameterRange) -> Unit)?,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var pendingMinParams by remember(minParams) { mutableStateOf(minParams) }
    var pendingMaxParams by remember(maxParams) { mutableStateOf(maxParams) }
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
                .padding(horizontal = AppTheme.spacing.spacing16)
                .padding(bottom = AppTheme.spacing.spacing32),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.spacing8),
        ) {
            Text("Filters", style = AppTheme.typography.headingBase)
            FilterSelectionGroup(
                title = "Minimum parameters",
                options = ParameterRange.entries,
                selected = pendingMinParams,
                label = { it.displayName },
                onSelect = { pendingMinParams = it },
            )
            FilterSelectionGroup(
                title = "Maximum parameters",
                options = ParameterRange.entries,
                selected = pendingMaxParams,
                label = { it.displayName },
                onSelect = { pendingMaxParams = it },
            )
            BrandButton(
                style = BrandButtonStyle.Secondary,
                onClick = {
                    if (onParameterFiltersApplied != null) {
                        onParameterFiltersApplied(pendingMinParams, pendingMaxParams)
                    } else {
                        onMinParamsChange(pendingMinParams)
                        onMaxParamsChange(pendingMaxParams)
                        onFiltersApplied()
                    }
                    onDismiss()
                },
                modifier = Modifier.align(Alignment.End),
            ) {
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
    Text(title, style = AppTheme.typography.headingSmall)
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
            .heightIn(min = AppTheme.spacing.spacing48)
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onClick,
            ),
        shape = AppTheme.shapes.small,
        color = if (selected) {
            AppTheme.colors.secondaryContainer
        } else {
            AppTheme.colors.surfaceContainerHigh
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = AppTheme.spacing.spacing16),
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

private val HUB_SORT_OPTIONS = listOf(
    ModelSort.TRENDING,
    ModelSort.DOWNLOADS,
    ModelSort.LIKES,
    ModelSort.MODIFIED,
    ModelSort.CREATED,
)

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
