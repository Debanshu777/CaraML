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
import com.debanshu777.caraml.core.theme.AppTheme
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
        modifier = Modifier.heightIn(min = AppTheme.spacing.spacing48).testTag("model-sort-filter-button").semantics {
            contentDescription = if (active) "Sort and filter models, active" else "Sort and filter models"
        },
    ) {
        Icon(
            Icons.Default.Tune,
            contentDescription = null,
            tint = if (active) AppTheme.colors.primary else AppTheme.colors.onSurface,
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
        val spacing = AppTheme.spacing
        Row(
            Modifier.fillMaxWidth().padding(horizontal = spacing.spacing12, vertical = spacing.spacing8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Filters", style = AppTheme.typography.headingBase, modifier = Modifier.weight(1f))
            TextButton(onClick = {
                draftMode = ModelHubBrowseMode.LanguageModels
                draftOrdering = ModelOrdering.Server(ModelSort.TRENDING)
                draftMin = ParameterRange.ZERO
                draftMax = ParameterRange.SIX_B
            }) { Text("Clear all") }
        }
        HorizontalDivider()
        BoxWithConstraints(Modifier.fillMaxWidth().heightIn(min = AppTheme.dimensions.size240, max = AppTheme.dimensions.size440)) {
            val railWidth = (maxWidth * 0.32f).coerceAtMost(AppTheme.dimensions.size132)
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.width(railWidth).fillMaxHeight()) {
                    sections.forEach { item ->
                        FilterSection(item, section == item) { section = item }
                    }
                }
                Box(Modifier.fillMaxHeight().width(AppTheme.dimensions.size1).background(AppTheme.colors.outlineVariant))
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
            modifier = Modifier.fillMaxWidth().padding(spacing.spacing12).heightIn(min = AppTheme.spacing.spacing48),
        ) { Text("View models") }
    }
}

@Composable
private fun FilterSection(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = AppTheme.dimensions.size56)
            .background(if (selected) AppTheme.colors.surfaceContainerHigh else Color.Transparent)
            .selectable(selected, onClick = onClick, role = Role.Tab),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(AppTheme.spacing.spacing4).height(AppTheme.dimensions.size56)
            .background(if (selected) AppTheme.colors.primary else Color.Transparent))
        Text(
            text = label,
            style = if (selected) AppTheme.typography.labelLarge else AppTheme.typography.bodyBase,
            color = if (selected) AppTheme.colors.onSurface else AppTheme.colors.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = AppTheme.spacing.spacing12, vertical = AppTheme.dimensions.size14),
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
                    style = AppTheme.typography.bodySmall,
                    color = AppTheme.colors.onSurfaceVariant,
                    modifier = Modifier.padding(AppTheme.spacing.spacing16))
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
                    style = AppTheme.typography.bodySmall,
                    color = AppTheme.colors.onSurfaceVariant,
                    modifier = Modifier.padding(AppTheme.spacing.spacing16))
            } else {
                Column(Modifier.fillMaxWidth().padding(horizontal = AppTheme.spacing.spacing16, vertical = AppTheme.dimensions.size20),
                    verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.spacing12)) {
                    Text("Parameters", style = AppTheme.typography.headingXSmall)
                    Text("Minimum ${min.apiValue}", style = AppTheme.typography.bodyBase,
                        color = AppTheme.colors.onSurfaceVariant)
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
                    Text("Maximum ${max.apiValue}", style = AppTheme.typography.bodyBase,
                        color = AppTheme.colors.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun FilterOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = AppTheme.dimensions.size52).selectable(selected, onClick = onClick, role = Role.RadioButton)
            .padding(horizontal = AppTheme.spacing.spacing16),
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.spacing12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = AppTheme.typography.bodyLarge,
            color = if (selected) AppTheme.colors.onSurface else AppTheme.colors.onSurfaceVariant)
    }
    HorizontalDivider()
}
