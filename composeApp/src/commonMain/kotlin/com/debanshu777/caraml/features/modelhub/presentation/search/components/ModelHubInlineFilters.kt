package com.debanshu777.caraml.features.modelhub.presentation.search.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.debanshu777.caraml.core.theme.AppTheme
import com.debanshu777.caraml.core.ui.motion.LocalAuroraMotionPolicy
import com.debanshu777.caraml.features.modelhub.presentation.search.ModelHubBrowseMode
import com.debanshu777.caraml.features.modelhub.presentation.search.ModelOrdering
import com.debanshu777.huggingfacemanager.model.ModelSort
import com.debanshu777.huggingfacemanager.model.ParameterRange

/** Inline refinements keep the visible result count and active query in context. */
@Composable
internal fun ModelHubInlineFilters(
    resultLabel: String,
    mode: ModelHubBrowseMode,
    ordering: ModelOrdering,
    minParams: ParameterRange,
    maxParams: ParameterRange,
    onApply: (ModelHubBrowseMode, ModelOrdering, ParameterRange, ParameterRange) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val active = mode != ModelHubBrowseMode.LanguageModels || minParams != ParameterRange.ZERO ||
        maxParams != ParameterRange.SIX_B || ordering != ModelOrdering.Server(ModelSort.TRENDING)
    val motion = LocalAuroraMotionPolicy.current
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = AppTheme.spacing.spacing8), verticalAlignment = Alignment.CenterVertically) {
            Text(resultLabel, style = AppTheme.typography.labelSmall, color = AppTheme.colors.onSurface,
                modifier = Modifier.weight(1f).testTag("model-summary"))
            TextButton(
                onClick = { expanded = !expanded },
                colors = ButtonDefaults.textButtonColors(contentColor = AppTheme.colors.onSurface),
                modifier = Modifier.testTag("model-sort-filter-button").semantics {
                    contentDescription = if (active) "Sort and filter models, active" else "Sort and filter models"
                    stateDescription = if (expanded) "Expanded" else "Collapsed"
                },
            ) {
                Icon(Icons.Default.Tune, null, Modifier.size(AppTheme.dimensions.size18))
                Text(if (active) "  Filters · active" else "  Filters", style = AppTheme.typography.bodySmall)
            }
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(motion.opacityDurationMillis)) + expandVertically(tween(if (motion.spatialTransitionsEnabled) motion.peerTransitionMillis else 0)),
            exit = fadeOut(tween(motion.opacityDurationMillis)) + shrinkVertically(tween(if (motion.spatialTransitionsEnabled) motion.peerTransitionMillis else 0)),
        ) {
            ModelHubInlineFilterPanel(mode, ordering, minParams, maxParams) { task, sort, min, max ->
                onApply(task, sort, min, max)
                expanded = false
            }
        }
    }
}

@Composable
internal fun ModelHubInlineFilterPanel(
    mode: ModelHubBrowseMode,
    ordering: ModelOrdering,
    minParams: ParameterRange,
    maxParams: ParameterRange,
    onApply: (ModelHubBrowseMode, ModelOrdering, ParameterRange, ParameterRange) -> Unit,
) {
    var draftMode by remember(mode) { mutableStateOf(mode) }
    var draftOrdering by remember(ordering) { mutableStateOf(ordering) }
    var draftMin by remember(minParams) { mutableStateOf(minParams) }
    var draftMax by remember(maxParams) { mutableStateOf(maxParams) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    val spacing = AppTheme.spacing
    Surface(shape = AppTheme.shapes.medium, color = AppTheme.colors.surfaceContainerLowest,
        border = BorderStroke(AppTheme.dimensions.size1, AppTheme.colors.outlineVariant)) {
        Column(Modifier.fillMaxWidth().padding(spacing.spacing16), verticalArrangement = Arrangement.spacedBy(spacing.spacing8)) {
            ModelFilterChoice("Task", draftMode, listOf(
                ModelHubBrowseMode.LanguageModels to "Text", ModelHubBrowseMode.DiffusionImage to "Image",
                ModelHubBrowseMode.DiffusionVideo to "Video",
            )) { draftMode = it }
            if (draftMode == ModelHubBrowseMode.LanguageModels) {
                ModelFilterChoice("Parameters", draftMax, ParameterRange.entries
                    .filter { it != ParameterRange.ZERO || draftMax == ParameterRange.ZERO }
                    .map { it to "Up to ${it.apiValue}" }) {
                    draftMax = it; if (draftMin.ordinal > it.ordinal) draftMin = it
                }
                TextButton(onClick = { advanced = !advanced }, colors = ButtonDefaults.textButtonColors(contentColor = AppTheme.actionColor)) {
                    Text(if (advanced) "Fewer options" else "Sort & more options", style = AppTheme.typography.bodySmall)
                }
                if (advanced) {
                    ModelFilterChoice("Minimum", draftMin, ParameterRange.entries.map { it to it.apiValue }) {
                        draftMin = it; if (draftMax.ordinal < it.ordinal) draftMax = it
                    }
                    ModelFilterChoice("Sort", draftOrdering, listOf(
                        ModelOrdering.Server(ModelSort.TRENDING) to "Trending",
                        ModelOrdering.Server(ModelSort.DOWNLOADS) to "Most downloads",
                        ModelOrdering.Server(ModelSort.LIKES) to "Most liked",
                        ModelOrdering.Server(ModelSort.CREATED) to "Newest",
                        ModelOrdering.Server(ModelSort.MODIFIED) to "Recently updated",
                        ModelOrdering.Personalized to "Best fit among loaded",
                    )) { draftOrdering = it }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.spacing8)) {
                Button(
                    onClick = { onApply(draftMode, draftOrdering, draftMin, draftMax) },
                    shape = AppTheme.shapes.small,
                    colors = ButtonDefaults.buttonColors(containerColor = AppTheme.colors.primary, contentColor = AppTheme.colors.onPrimary),
                ) { Text("Apply filters", style = AppTheme.typography.bodySmall) }
                ModelHubAction("Reset", onClick = {
                    onApply(ModelHubBrowseMode.LanguageModels, ModelOrdering.Server(ModelSort.TRENDING), ParameterRange.ZERO, ParameterRange.SIX_B)
                })
            }
        }
    }
}

@Composable
private fun <T> ModelFilterChoice(label: String, value: T, options: List<Pair<T, String>>, onChange: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.spacing12)) {
        Text(label, style = AppTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Box(Modifier.weight(1.5f)) {
            OutlinedButton(onClick = { expanded = true }, shape = AppTheme.shapes.small, modifier = Modifier.fillMaxWidth().heightIn(min = AppTheme.spacing.spacing48).semantics { contentDescription = label },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = AppTheme.colors.onSurface, containerColor = AppTheme.colors.background)) {
                Text(options.firstOrNull { it.first == value }?.second.orEmpty(), style = AppTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                Icon(Icons.Default.ExpandMore, null, Modifier.size(AppTheme.dimensions.size18))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (option, text) ->
                    DropdownMenuItem(text = { Text(text) }, onClick = { expanded = false; onChange(option) })
                }
            }
        }
    }
}
