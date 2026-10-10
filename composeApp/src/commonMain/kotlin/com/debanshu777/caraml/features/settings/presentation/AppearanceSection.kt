package com.debanshu777.caraml.features.settings.presentation

import com.debanshu777.caraml.core.ui.icons.AppIcons
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import com.debanshu777.caraml.core.ui.components.BrandButton
import com.debanshu777.caraml.core.ui.components.BrandButtonStyle
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.theme.AppTheme
import com.debanshu777.caraml.core.theme.ThemeDefaults
import com.debanshu777.caraml.core.theme.ThemeMode
import com.debanshu777.caraml.core.theme.ThemePaletteStyle
import com.debanshu777.caraml.core.theme.ThemeViewModel

/** Appearance preferences hosted in [SettingsScreen]. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppearanceSection(
    viewModel: ThemeViewModel,
    modifier: Modifier = Modifier,
) {
    val preferences by viewModel.preferences.collectAsState()
    val spacing = AppTheme.spacing
    var themeMenuOpen by rememberSaveable { mutableStateOf(false) }
    var paletteExpanded by rememberSaveable { mutableStateOf(false) }
    val selectedSeedIndex = ThemeDefaults.PRESET_SEEDS.indexOfFirst { color ->
        color.argbInt() == preferences.seedColor.argbInt()
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("settings-section-appearance"),
    ) {
        SettingsPreferenceRow(
            title = "Appearance",
            description = "Make yourself at home.",
        ) {
            Box {
                BrandButton(
                    style = BrandButtonStyle.Secondary,
                    onClick = { themeMenuOpen = true },
                    modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 96.dp)
                        .semantics { contentDescription = "Choose appearance"; stateDescription = preferences.themeMode.displayName() },
                ) {
                    Text(preferences.themeMode.displayName())
                    Icon(AppIcons.ChevronDown, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                DropdownMenu(expanded = themeMenuOpen, onDismissRequest = { themeMenuOpen = false }) {
                    ThemeMode.entries.forEach { mode ->
                        val selected = preferences.themeMode == mode
                        DropdownMenuItem(
                            text = { Text(mode.displayName()) },
                            onClick = { viewModel.updateThemeMode(mode); themeMenuOpen = false },
                            trailingIcon = if (selected) ({ Icon(AppIcons.Check, contentDescription = null) }) else null,
                            modifier = Modifier.semantics {
                                contentDescription = "Theme ${mode.displayName()}, " + if (selected) "selected" else "not selected"
                                stateDescription = if (selected) "Selected" else "Not selected"
                            },
                        )
                    }
                }
            }
        }
        SettingsRowDivider(tag = "settings-divider-appearance-theme")
        SettingsPreferenceRow(
            title = "A little color",
            description = "One family of colors, everywhere.",
            modifier = Modifier.clickable(role = Role.Button) { paletteExpanded = !paletteExpanded }
                .semantics { stateDescription = if (paletteExpanded) "Expanded" else "Collapsed" },
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                val brand = AppTheme.brandColors
                listOf(preferences.seedColor, brand.yellow, brand.lilac, brand.mint).forEach { color ->
                    Box(Modifier.size(width = 21.dp, height = 24.dp).rotate(6f)
                        .background(color, RoundedCornerShape(6.dp))
                        .border(1.dp, brand.ink, RoundedCornerShape(6.dp)))
                }
                Icon(if (paletteExpanded) Icons.Default.ExpandLess else AppIcons.ChevronDown, null, Modifier.size(18.dp), tint = AppTheme.colors.onSurface)
            }
        }
        if (paletteExpanded) {
            Surface(color = AppTheme.colors.surface, shape = AppTheme.shapes.medium) {
                Column(Modifier.padding(horizontal = spacing.spacing12)) {
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
                }
            }
        }
        SettingsRowDivider(tag = "settings-divider-appearance-palette")
        AppearanceToggle(
            title = "Keep things still",
            description = "Same personality, less motion.",
            checked = preferences.reduceMotion,
            onCheckedChange = viewModel::updateReduceMotion,
        )
        SettingsRowDivider(tag = "settings-divider-appearance-motion")
        AppearanceToggle(
            title = "Soft glass",
            description = "Translucent surfaces and a little depth.",
            checked = preferences.softEffects,
            onCheckedChange = viewModel::updateSoftEffects,
        )
        SettingsRowDivider(tag = "settings-divider-appearance")
    }
}

@Composable
internal fun AppearanceToggle(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    SettingsPreferenceRow(title, description) {
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.semantics { contentDescription = title },
        )
    }
}

@Composable
internal fun SettingsPreferenceRow(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit,
) {
    BoxWithConstraints(
        modifier = modifier.fillMaxWidth().padding(vertical = AppTheme.spacing.spacing16),
    ) {
        val label: @Composable (Modifier) -> Unit = { labelModifier ->
            Column(labelModifier, verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.spacing4)) {
                Text(title, style = AppTheme.typography.preferenceTitle, color = AppTheme.colors.onSurface)
                Text(description, style = AppTheme.typography.bodySmall, color = AppTheme.colors.onSurfaceVariant)
            }
        }
        if (LocalDensity.current.fontScale >= 1.5f || maxWidth < 300.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.spacing12)) {
                label(Modifier.fillMaxWidth())
                trailing()
            }
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.spacing12),
            ) {
                label(Modifier.weight(1f))
                trailing()
            }
        }
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
    val spacing = AppTheme.spacing
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = spacing.spacing16),
        verticalArrangement = Arrangement.spacedBy(spacing.spacing8),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.spacing12),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = AppTheme.typography.headingSmall,
                color = AppTheme.colors.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = selectedValue,
                style = AppTheme.typography.bodySmall,
                color = AppTheme.actionColor,
            )
        }
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(spacing.spacing8),
            verticalArrangement = Arrangement.spacedBy(spacing.spacing8),
        ) {
            content()
        }
        supportingText?.let {
            Text(
                text = it,
                style = AppTheme.typography.bodySmall,
                color = AppTheme.colors.onSurfaceVariant,
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
        AppTheme.colors.primary
    } else {
        AppTheme.colors.outlineVariant
    }
    val borderWidth = if (selected) AppTheme.dimensions.size3 else AppTheme.dimensions.size1
    Box(modifier = Modifier.size(AppTheme.spacing.spacing48)) {
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
                    .size(AppTheme.dimensions.size20)
                    .testTag(selectedIndicatorTestTag),
                shape = CircleShape,
                color = AppTheme.colors.primary,
                contentColor = AppTheme.colors.onPrimary,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = AppIcons.Check,
                        contentDescription = null,
                        modifier = Modifier.size(AppTheme.dimensions.size14),
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
