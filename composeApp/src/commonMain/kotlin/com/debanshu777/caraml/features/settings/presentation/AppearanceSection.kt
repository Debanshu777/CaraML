package com.debanshu777.caraml.features.settings.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.theme.LocalSpacing
import com.debanshu777.caraml.core.theme.ThemeDefaults
import com.debanshu777.caraml.core.theme.ThemeMode
import com.debanshu777.caraml.core.theme.ThemePaletteStyle
import com.debanshu777.caraml.core.theme.ThemeViewModel
import com.debanshu777.caraml.core.theme.auroraColors

/** Appearance preferences hosted in [SettingsScreen]. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppearanceSection(
    viewModel: ThemeViewModel,
    modifier: Modifier = Modifier,
) {
    val preferences by viewModel.preferences.collectAsState()
    val spacing = LocalSpacing.current
    val selectedSeedIndex = ThemeDefaults.PRESET_SEEDS.indexOfFirst { color ->
        color.argbInt() == preferences.seedColor.argbInt()
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("settings-section-appearance"),
    ) {
        SettingsSectionHeader(
            title = "Appearance",
            supportingText = "Shape the workspace atmosphere without changing how it works.",
        )

        AuroraThemePreview(
            modifier = Modifier.padding(top = spacing.l, bottom = spacing.l),
        )
        SettingsRowDivider(tag = "settings-divider-appearance-preview")

        SettingsChoiceBlock(
            title = "Theme",
            selectedValue = preferences.themeMode.displayName(),
        ) {
            ThemeMode.entries.forEach { mode ->
                val selected = preferences.themeMode == mode
                SettingFilterChip(
                    selected = selected,
                    onClick = { viewModel.updateThemeMode(mode) },
                    label = mode.displayName(),
                    selectedIndicatorTestTag =
                        "Selected theme ${mode.displayName()}",
                    modifier = Modifier.semantics {
                        contentDescription = "Theme ${mode.displayName()}, " +
                            if (selected) "selected" else "not selected"
                        stateDescription = if (selected) "Selected" else "Not selected"
                    },
                )
            }
        }
        SettingsRowDivider(tag = "settings-divider-appearance-theme")

        SettingsChoiceBlock(
            title = "Seed color",
            selectedValue = if (selectedSeedIndex >= 0) {
                "Color ${selectedSeedIndex + 1}"
            } else {
                "Custom color"
            },
        ) {
            ThemeDefaults.PRESET_SEEDS.forEachIndexed { index, color ->
                SeedSwatch(
                    color = color,
                    selected = color.argbInt() == preferences.seedColor.argbInt(),
                    label = "Seed color ${index + 1}",
                    selectedIndicatorTestTag = "Selected seed color ${index + 1}",
                    onClick = { viewModel.updateSeedColor(color) },
                )
            }
        }
        SettingsRowDivider(tag = "settings-divider-appearance-seed")

        SettingsChoiceBlock(
            title = "Palette style",
            selectedValue = preferences.paletteStyle.displayName(),
            supportingText = preferences.paletteStyle.description(),
        ) {
            ThemePaletteStyle.entries.forEach { style ->
                val selected = preferences.paletteStyle == style
                SettingFilterChip(
                    selected = selected,
                    onClick = { viewModel.updatePaletteStyle(style) },
                    label = style.displayName(),
                    selectedIndicatorTestTag =
                        "Selected palette ${style.displayName()}",
                    modifier = Modifier.semantics {
                        contentDescription = "Palette ${style.displayName()}, " +
                            if (selected) "selected" else "not selected"
                        stateDescription = if (selected) "Selected" else "Not selected"
                    },
                )
            }
        }
        SettingsRowDivider(tag = "settings-divider-appearance")
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsChoiceBlock(
    title: String,
    selectedValue: String,
    supportingText: String? = null,
    content: @Composable () -> Unit,
) {
    val spacing = LocalSpacing.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = spacing.l),
        verticalArrangement = Arrangement.spacedBy(spacing.s),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = selectedValue,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(spacing.s),
            verticalArrangement = Arrangement.spacedBy(spacing.s),
        ) {
            content()
        }
        supportingText?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun AuroraThemePreview(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.auroraColors
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 104.dp)
            .clip(MaterialTheme.shapes.large)
            .drawWithCache {
                val field = Brush.linearGradient(
                    colors = listOf(colors.primaryGlow, colors.tertiaryGlow),
                    start = Offset.Zero,
                    end = Offset(size.width, size.height),
                )
                onDrawBehind {
                    drawRect(colors.canvas)
                    drawRect(field)
                }
            }
            .semantics(mergeDescendants = true) {
                contentDescription = "Current Aurora theme preview"
            },
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = "Prism workbench",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Current workspace atmosphere",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SeedSwatch(
    color: Color,
    selected: Boolean,
    label: String,
    selectedIndicatorTestTag: String,
    onClick: () -> Unit,
) {
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }
    val borderWidth = if (selected) 3.dp else 1.dp
    Box(modifier = Modifier.size(48.dp)) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(CircleShape)
                .background(color)
                .border(borderWidth, borderColor, CircleShape)
                .semantics {
                    contentDescription = label
                    stateDescription = if (selected) "Selected" else "Not selected"
                }
                .selectable(
                    selected = selected,
                    role = Role.RadioButton,
                    onClick = onClick,
                ),
        )
        if (selected) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(20.dp)
                    .testTag(selectedIndicatorTestTag),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
    }
}

private fun ThemeMode.displayName(): String = when (this) {
    ThemeMode.SYSTEM -> "System"
    ThemeMode.LIGHT -> "Light"
    ThemeMode.DARK -> "Dark"
}

private fun ThemePaletteStyle.displayName(): String = when (this) {
    ThemePaletteStyle.TONAL_SPOT -> "Tonal Spot"
    ThemePaletteStyle.NEUTRAL -> "Neutral"
    ThemePaletteStyle.VIBRANT -> "Vibrant"
    ThemePaletteStyle.EXPRESSIVE -> "Expressive"
    ThemePaletteStyle.CONTENT -> "Content"
    ThemePaletteStyle.FIDELITY -> "Fidelity"
    ThemePaletteStyle.MONOCHROME -> "Monochrome"
    ThemePaletteStyle.RAINBOW -> "Rainbow"
    ThemePaletteStyle.FRUIT_SALAD -> "Fruit Salad"
}

private fun ThemePaletteStyle.description(): String = when (this) {
    ThemePaletteStyle.TONAL_SPOT -> "Balanced Material 3 default — secondary hues stay near the seed."
    ThemePaletteStyle.NEUTRAL -> "Muted, low-chroma palette — closest to grayscale."
    ThemePaletteStyle.VIBRANT -> "Saturated palette that stays anchored to the seed."
    ThemePaletteStyle.EXPRESSIVE -> "Diverges secondary and tertiary hues for contrast — least cohesive."
    ThemePaletteStyle.CONTENT -> "Palette pulled directly from the seed for content-driven UIs."
    ThemePaletteStyle.FIDELITY -> "Faithful to the exact seed color, with light tonal range."
    ThemePaletteStyle.MONOCHROME -> "Single hue across the entire scheme."
    ThemePaletteStyle.RAINBOW -> "Spreads the palette across multiple hues — playful, not unified."
    ThemePaletteStyle.FRUIT_SALAD -> "Mixes complementary hues — bold, deliberately divergent."
}

/** Stable ARGB Int for swatch equality, immune to floating-point drift. */
private fun Color.argbInt(): Int {
    val a = (alpha * 255f).toInt() and 0xFF
    val r = (red * 255f).toInt() and 0xFF
    val g = (green * 255f).toInt() and 0xFF
    val b = (blue * 255f).toInt() and 0xFF
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}
