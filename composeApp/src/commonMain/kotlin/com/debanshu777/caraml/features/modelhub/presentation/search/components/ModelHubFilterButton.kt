package com.debanshu777.caraml.features.modelhub.presentation.search.components

import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RangeSlider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.theme.LocalSpacing
import com.debanshu777.caraml.features.modelhub.presentation.search.ModelHubBrowseMode
import com.debanshu777.caraml.features.modelhub.presentation.search.ModelOrdering
import com.debanshu777.huggingfacemanager.model.ModelSort
import com.debanshu777.huggingfacemanager.model.ParameterRange
import kotlin.math.roundToInt

/** One entry point for all Discover refinements, beside the pinned search field. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelHubFilterButton(
    mode: ModelHubBrowseMode,
    ordering: ModelOrdering,
    minParams: ParameterRange,
    maxParams: ParameterRange,
    onApply: (ModelHubBrowseMode, ModelOrdering, ParameterRange, ParameterRange) -> Unit,
) {
    var visible by remember { mutableStateOf(false) }
    val active = mode != ModelHubBrowseMode.LanguageModels ||
        minParams != ParameterRange.ZERO || maxParams != ParameterRange.SIX_B ||
        ordering != ModelOrdering.Server(ModelSort.TRENDING)
    IconButton(
        onClick = { visible = true },
        modifier = Modifier.heightIn(min = 48.dp).testTag("model-sort-filter-button").semantics {
            contentDescription = if (active) "Sort and filter models, active" else "Sort and filter models"
        },
    ) {
        Icon(
            Icons.Default.Tune,
            contentDescription = null,
            tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
    if (!visible) return

    ModalBottomSheet(
        onDismissRequest = { visible = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        ModelHubFilterPanel(mode, ordering, minParams, maxParams) { selectedMode, selectedOrdering, selectedMin, selectedMax ->
            onApply(selectedMode, selectedOrdering, selectedMin, selectedMax)
            visible = false
        }
    }
}

/** Also rendered inline by compact layout fixtures, avoiding dialog-window test sizing. */
@Composable
internal fun ModelHubFilterPanel(
    mode: ModelHubBrowseMode,
    ordering: ModelOrdering,
    minParams: ParameterRange,
    maxParams: ParameterRange,
    onApply: (ModelHubBrowseMode, ModelOrdering, ParameterRange, ParameterRange) -> Unit,
) {
    var draftMode by remember { mutableStateOf(mode) }
    var draftOrdering by remember { mutableStateOf(ordering) }
    var draftMin by remember { mutableStateOf(minParams) }
    var draftMax by remember { mutableStateOf(maxParams) }
    var section by remember { mutableStateOf("Type") }
    val sections = listOf("Type", "Sort", "Size")
    Column(Modifier.fillMaxWidth()) {
        val spacing = LocalSpacing.current
        Row(
            Modifier.fillMaxWidth().padding(horizontal = spacing.m, vertical = spacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Filters", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = {
                draftMode = ModelHubBrowseMode.LanguageModels
                draftOrdering = ModelOrdering.Server(ModelSort.TRENDING)
                draftMin = ParameterRange.ZERO
                draftMax = ParameterRange.SIX_B
            }) { Text("Clear all") }
        }
        HorizontalDivider()
        BoxWithConstraints(Modifier.fillMaxWidth().heightIn(min = 240.dp, max = 440.dp)) {
            val railWidth = (maxWidth * 0.32f).coerceAtMost(132.dp)
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.width(railWidth).fillMaxHeight()) {
                    sections.forEach { item ->
                        FilterSection(item, section == item) { section = item }
                    }
                }
                Box(Modifier.fillMaxHeight().width(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                    FilterChoices(section, draftMode, draftOrdering, draftMin, draftMax,
                        { draftMode = it }, { draftOrdering = it },
                        { draftMin = it; if (draftMax.ordinal < it.ordinal) draftMax = it },
                        { draftMax = it; if (draftMin.ordinal > it.ordinal) draftMin = it })
                }
            }
        }
        HorizontalDivider()
        Button(
            onClick = { onApply(draftMode, draftOrdering, draftMin, draftMax) },
            modifier = Modifier.fillMaxWidth().padding(spacing.m).heightIn(min = 48.dp),
        ) { Text("View models") }
    }
}

@Composable
private fun FilterSection(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .background(if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent)
            .selectable(selected, onClick = onClick, role = Role.Tab),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(4.dp).height(56.dp)
            .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent))
        Text(
            text = label,
            style = if (selected) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodyMedium,
            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
        )
    }
    HorizontalDivider()
}

@Composable
private fun FilterChoices(
    section: String,
    mode: ModelHubBrowseMode,
    ordering: ModelOrdering,
    min: ParameterRange,
    max: ParameterRange,
    onMode: (ModelHubBrowseMode) -> Unit,
    onOrdering: (ModelOrdering) -> Unit,
    onMin: (ParameterRange) -> Unit,
    onMax: (ParameterRange) -> Unit,
) {
    when (section) {
        "Type" -> listOf(
            ModelHubBrowseMode.LanguageModels to "Text",
            ModelHubBrowseMode.DiffusionImage to "Image",
            ModelHubBrowseMode.DiffusionVideo to "Video",
        ).forEach { (value, label) -> FilterOption(label, value == mode) { onMode(value) } }
        "Sort" -> {
            if (mode != ModelHubBrowseMode.LanguageModels) {
                Text("Sorting applies to the online text catalog.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp))
            } else {
                listOf(
                    ModelOrdering.Server(ModelSort.TRENDING) to "Trending",
                    ModelOrdering.Server(ModelSort.DOWNLOADS) to "Most downloads",
                    ModelOrdering.Server(ModelSort.LIKES) to "Most liked",
                    ModelOrdering.Server(ModelSort.CREATED) to "Newest",
                    ModelOrdering.Server(ModelSort.MODIFIED) to "Recently updated",
                    ModelOrdering.Personalized to "Best fit among loaded",
                ).forEach { (value, label) -> FilterOption(label, value == ordering) { onOrdering(value) } }
            }
        }
        "Size" -> {
            if (mode != ModelHubBrowseMode.LanguageModels) {
                Text("Size filtering applies to the online text catalog.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp))
            } else {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Parameters", style = MaterialTheme.typography.titleSmall)
                    Text("Minimum ${min.apiValue}", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    RangeSlider(
                        value = min.ordinal.toFloat()..max.ordinal.toFloat(),
                        modifier = Modifier.testTag("model-size-range").semantics {
                            contentDescription = "Model parameter size range"
                            stateDescription = "Minimum ${min.apiValue}, maximum ${max.apiValue} parameters"
                        },
                        onValueChange = { range ->
                            val lower = range.start.roundToInt().coerceIn(0, ParameterRange.entries.lastIndex)
                            val upper = range.endInclusive.roundToInt().coerceIn(lower, ParameterRange.entries.lastIndex)
                            onMin(ParameterRange.entries[lower])
                            onMax(ParameterRange.entries[upper])
                        },
                        valueRange = 0f..ParameterRange.entries.lastIndex.toFloat(),
                        steps = ParameterRange.entries.size - 2,
                    )
                    Text("Maximum ${max.apiValue}", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun FilterOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).selectable(selected, onClick = onClick, role = Role.RadioButton)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyLarge,
            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider()
}
