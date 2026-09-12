package com.debanshu777.caraml.features.modelhub.presentation.search.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.debanshu777.huggingfacemanager.model.ModelSort
import com.debanshu777.huggingfacemanager.model.ParameterRange
import com.debanshu777.caraml.features.modelhub.presentation.search.ModelOrdering

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
    LazyRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "ordering") {
            SortDropdown(
                label = if (ordering is ModelOrdering.Personalized) "Recommended" else "Server order",
                options = listOf(ModelOrdering.Personalized, ModelOrdering.Server(sort)),
                selected = ordering,
                highlighted = ordering is ModelOrdering.Personalized,
                onSelect = onOrderingChange,
            )
        }
        item(key = "sort") {
            SortDropdown(
                label = "Sort: ${sort.displayName}",
                options = ModelSort.entries.filter { it != ModelSort.SIMILAR },
                selected = sort,
                highlighted = sort != ModelSort.TRENDING,
                onSelect = onSortChange,
            )
        }
        item(key = "min-params") {
            SortDropdown(
                label = "Min: ${minParams.displayName}",
                options = ParameterRange.entries,
                selected = minParams,
                highlighted = minParams != ParameterRange.ZERO,
                onSelect = onMinParamsChange,
            )
        }
        item(key = "max-params") {
            SortDropdown(
                label = "Max: ${maxParams.displayName}",
                options = ParameterRange.entries,
                selected = maxParams,
                highlighted = maxParams != ParameterRange.SIX_B,
                onSelect = onMaxParamsChange,
            )
        }
    }
}

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
    Box(modifier = modifier) {
        FilterChip(
            selected = highlighted,
            onClick = { expanded = true },
            label = { Text(label) },
            trailingIcon = {
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = "Choose $label",
                    modifier = Modifier.size(18.dp),
                )
            },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { option ->
                val displayName = when (option) {
                    ModelOrdering.Personalized -> "Recommended for me"
                    is ModelOrdering.Server -> "Server: ${option.value.displayName}"
                    is ModelSort -> option.displayName
                    is ParameterRange -> option.displayName
                    else -> option.toString()
                }
                DropdownMenuItem(
                    text = { Text(displayName) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    }
                )
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
