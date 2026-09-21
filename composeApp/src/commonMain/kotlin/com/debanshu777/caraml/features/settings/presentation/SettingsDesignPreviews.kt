package com.debanshu777.caraml.features.settings.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.debanshu777.caraml.core.theme.CaraMLTheme
import com.debanshu777.caraml.core.theme.LocalSpacing
import com.debanshu777.caraml.core.theme.ThemePreferences
import com.debanshu777.caraml.core.ui.components.AuroraBackdrop
import androidx.compose.ui.tooling.preview.Preview

@OptIn(ExperimentalLayoutApi::class)
@Preview(name = "Settings compact", widthDp = 412, heightDp = 915)
@Composable
private fun SettingsCompactPreview() {
    CaraMLTheme(ThemePreferences()) {
        AuroraBackdrop {
            val spacing = LocalSpacing.current
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = spacing.l, vertical = spacing.xl),
                verticalArrangement = Arrangement.spacedBy(spacing.xl),
            ) {
                SettingsSectionHeader(
                    title = "Appearance",
                    supportingText = "Choose the workspace theme and focal accent.",
                )
                FlowRow(
                    modifier = Modifier.selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(spacing.s),
                    verticalArrangement = Arrangement.spacedBy(spacing.s),
                ) {
                    listOf("System", "Light", "Dark").forEachIndexed { index, label ->
                        SettingFilterChip(
                            selected = index == 2,
                            onClick = {},
                            label = label,
                            selectedIndicatorTestTag = "preview-theme-$label",
                        )
                    }
                }
                AuroraThemePreview()
                SettingsRowDivider(tag = "preview-divider")
                SettingsSectionHeader(
                    title = "Runtime",
                    supportingText = "Tune the local inference engine for this device.",
                )
                GpuAccelerationSection(enabled = true, onToggle = {})
            }
        }
    }
}
