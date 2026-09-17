package com.debanshu777.caraml.features.modelhub.presentation.search.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.features.modelhub.presentation.search.ModelHubBrowseMode
import com.debanshu777.caraml.features.modelhub.presentation.search.ModelOrdering
import com.debanshu777.huggingfacemanager.model.ModelSort
import com.debanshu777.huggingfacemanager.model.ParameterRange

/** Source-compatible sort/filter entry point retained for existing callers and tests. */
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
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
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
        )
    }
}

/** Source-compatible bridge to the single-band Prism toolbar. */
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
    ModelHubToolbar(
        browseMode = browseMode,
        onBrowseModeChange = onBrowseModeChange,
        showSortFilters = showSortFilters,
        ordering = ordering,
        sort = sort,
        minParams = minParams,
        maxParams = maxParams,
        onSortChange = onSortChange,
        onOrderingChange = onOrderingChange,
        onMinParamsChange = onMinParamsChange,
        onMaxParamsChange = onMaxParamsChange,
        modifier = modifier,
    )
}
