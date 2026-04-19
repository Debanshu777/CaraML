package com.debanshu777.caraml.features.settings.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.theme.LocalSpacing
import com.debanshu777.caraml.core.theme.ThemeDefaults
import com.debanshu777.caraml.core.theme.ThemeMode
import com.debanshu777.caraml.core.theme.ThemePaletteStyle
import com.debanshu777.caraml.core.theme.ThemeViewModel

/**
 * Appearance preferences hosted in [SettingsScreen].
 *
 * Owns its own [ThemeViewModel] (separate from [SettingsViewModel]) so that
 * inference settings and theme settings stay decoupled — the App composition
 * root collects from the same singleton VM, ensuring edits here propagate
 * globally.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceSection(
    viewModel: ThemeViewModel,
    modifier: Modifier = Modifier,
) {
    val preferences by viewModel.preferences.collectAsState()

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            modifier = Modifier.padding(LocalSpacing.current.l),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                text = "Appearance",
                style = MaterialTheme.typography.titleMedium,
            )

            // Theme mode --------------------------------------------------
            Column(verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.s)) {
                Text(
                    text = "Theme",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    ThemeMode.entries.forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = preferences.themeMode == mode,
                            onClick = { viewModel.updateThemeMode(mode) },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = ThemeMode.entries.size,
                            ),
                            label = { Text(mode.displayName()) },
                        )
                    }
                }
            }

            // Seed color --------------------------------------------------
            Column(verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.s)) {
                Text(
                    text = "Seed color",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(LocalSpacing.current.m),
                    contentPadding = PaddingValues(vertical = LocalSpacing.current.xs),
                ) {
                    items(ThemeDefaults.PRESET_SEEDS) { color ->
                        SeedSwatch(
                            color = color,
                            selected = color.argbInt() == preferences.seedColor.argbInt(),
                            onClick = { viewModel.updateSeedColor(color) },
                        )
                    }
                }
            }

            // Palette style -----------------------------------------------
            Column(verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.s)) {
                Text(
                    text = "Palette style",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(LocalSpacing.current.s),
                    contentPadding = PaddingValues(vertical = LocalSpacing.current.xs),
                ) {
                    items(ThemePaletteStyle.entries) { style ->
                        FilterChip(
                            selected = preferences.paletteStyle == style,
                            onClick = { viewModel.updatePaletteStyle(style) },
                            label = { Text(style.displayName()) },
                            colors = FilterChipDefaults.filterChipColors(),
                        )
                    }
                }
                Text(
                    text = preferences.paletteStyle.description(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SeedSwatch(
    color: Color,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val borderColor =
        if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.outlineVariant
    val borderWidth = if (selected) 3.dp else 1.dp
    // 48dp meets the WCAG 2.5.8 / Material accessibility minimum touch target.
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(color)
            .border(borderWidth, borderColor, CircleShape)
            .clickable(
                onClickLabel = if (selected) "Selected seed color" else "Use this seed color",
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = Color.White,
            )
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

/**
 * One-line trade-off explainer shown under the chip row. Surfaces the fact that
 * some styles (Expressive, Rainbow, Fruit Salad) deliberately diverge hues from
 * the seed — which can look "inconsistent" to users expecting one dominant color.
 */
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
