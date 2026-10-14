package com.debanshu777.caraml.features.chat.presentation.components

import com.debanshu777.caraml.core.ui.icons.AppIcons
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.theme.AppTheme
import com.debanshu777.caraml.features.chat.domain.GenerationMode

/** Local creation choices; changing one never changes the application's destination. */
@Composable
fun GenerationModeSwitcher(
    mode: GenerationMode,
    onModeSelected: (GenerationMode) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val largeText = LocalDensity.current.fontScale >= 1.3f
    if (!compact && largeText) {
        Column(modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            GenerationMode.entries.forEach { item ->
                GenerationModeChoice(item, item == mode, compact = false, stacked = true,
                    onClick = { onModeSelected(item) }, modifier = Modifier.fillMaxWidth())
            }
        }
    } else if (compact && largeText) {
        val scrollState = rememberScrollState()
        val widths = remember { mutableStateMapOf<GenerationMode, Int>() }
        var viewportWidth by remember { mutableIntStateOf(0) }
        val gap = with(LocalDensity.current) { 9.dp.roundToPx() }
        val selectedStart = GenerationMode.entries.take(mode.ordinal).sumOf { widths[it] ?: 0 } + mode.ordinal * gap
        val selectedWidth = widths[mode] ?: 0
        LaunchedEffect(mode, selectedStart, selectedWidth, viewportWidth) {
            if (selectedWidth > 0 && viewportWidth > 0) {
                val target = when {
                    selectedStart < scrollState.value -> selectedStart
                    selectedStart + selectedWidth > scrollState.value + viewportWidth ->
                        selectedStart + selectedWidth - viewportWidth
                    else -> scrollState.value
                }
                // Selection stays visible without an extra spatial animation.
                scrollState.scrollTo(target)
            }
        }
        Row(
            modifier.fillMaxWidth().onSizeChanged { viewportWidth = it.width }
                .horizontalScroll(scrollState).selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            GenerationMode.entries.forEach { item ->
                GenerationModeChoice(item, item == mode, compact = true, stacked = false,
                    onClick = { onModeSelected(item) },
                    modifier = Modifier.widthIn(min = 48.dp).onSizeChanged { widths[item] = it.width })
            }
        }
    } else {
        Row(modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            GenerationMode.entries.forEach { item ->
                GenerationModeChoice(item, item == mode, compact, stacked = false,
                    onClick = { onModeSelected(item) }, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun GenerationModeChoice(
    mode: GenerationMode,
    selected: Boolean,
    compact: Boolean,
    stacked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val label = when (mode) {
        GenerationMode.Text -> "Write"
        GenerationMode.Image -> "Imagine"
        GenerationMode.Video -> "Animate"
    }
    val semanticLabel = when (mode) {
        GenerationMode.Text -> "Text"
        GenerationMode.Image -> "Image"
        GenerationMode.Video -> "Video"
    }
    val icon = when (mode) {
        GenerationMode.Text -> AppIcons.Text
        GenerationMode.Image -> AppIcons.Image
        GenerationMode.Video -> AppIcons.Video
    }
    val selectedColor = when (mode) {
        GenerationMode.Text -> AppTheme.brandColors.yellow
        GenerationMode.Image -> AppTheme.brandColors.lilac
        GenerationMode.Video -> AppTheme.brandColors.mint
    }
    val shape = RoundedCornerShape(14.dp)
    Surface(
        modifier = modifier
            .heightIn(min = if (compact) 48.dp else 83.dp)
            .clip(shape)
            .selectable(selected, onClick = onClick, role = Role.Tab)
            .semantics(mergeDescendants = true) {
                contentDescription = if (selected) "$semanticLabel mode, selected" else "$semanticLabel mode"
            },
        color = if (selected) selectedColor else AppTheme.colors.surface.copy(alpha = if (AppTheme.softEffects) .8f else 1f),
        contentColor = if (selected) AppTheme.brandColors.ink else AppTheme.colors.onSurface,
        shape = shape,
        border = BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) AppTheme.brandColors.ink else AppTheme.colors.outlineVariant),
        shadowElevation = if (selected && AppTheme.softEffects) 3.dp else 0.dp,
    ) {
        when {
            compact -> Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) { Text(label, style = AppTheme.typography.labelBase, maxLines = 1, softWrap = false) }
            stacked -> Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
                Text(label, modifier = Modifier.weight(1f), style = AppTheme.typography.itemTitle)
            }
            else -> Column(
                modifier = Modifier.padding(start = 11.dp, end = 6.dp, top = 12.dp, bottom = 11.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
                Text(label, style = AppTheme.typography.itemTitle)
            }
        }
    }
}
