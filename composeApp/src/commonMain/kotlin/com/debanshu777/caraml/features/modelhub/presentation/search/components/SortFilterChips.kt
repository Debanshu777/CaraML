package com.debanshu777.caraml.features.modelhub.presentation.search.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
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

@Composable
fun SortFilterChips(
    sort: ModelSort,
    minParams: ParameterRange,
    maxParams: ParameterRange,
    onSortChange: (ModelSort) -> Unit,
    onMinParamsChange: (ParameterRange) -> Unit,
    onMaxParamsChange: (ParameterRange) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SortDropdown(
            label = sort.displayName,
            options = ModelSort.entries.filter { it != ModelSort.SIMILAR },
            selected = sort,
            onSelect = onSortChange
        )
        SortDropdown(
            label = "Min: ${minParams.displayName}",
            options = ParameterRange.entries,
            selected = minParams,
            onSelect = onMinParamsChange
        )
        SortDropdown(
            label = "Max: ${maxParams.displayName}",
            options = ParameterRange.entries,
            selected = maxParams,
            onSelect = onMaxParamsChange
        )
    }
}

@Composable
private fun <T> SortDropdown(
    label: String,
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        OutlinedButton(
            onClick = { expanded = true }
        ) {
            Text(label)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { option ->
                val displayName = when (option) {
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
