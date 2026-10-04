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
import com.debanshu777.caraml.core.theme.AppTheme
import com.debanshu777.caraml.core.theme.CaraMLTheme
import com.debanshu777.caraml.core.theme.ThemePreferences
import com.debanshu777.caraml.core.ui.components.AuroraBackdrop
import androidx.compose.ui.tooling.preview.Preview

@OptIn(ExperimentalLayoutApi::class)
@Preview(name = "Settings compact", widthDp = 412, heightDp = 915)
@Composable
private fun SettingsCompactPreview() {
    CaraMLTheme(ThemePreferences()) {
        AuroraBackdrop {
            val spacing = AppTheme.spacing
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = spacing.spacing16, vertical = spacing.spacing24),
                verticalArrangement = Arrangement.spacedBy(spacing.spacing24),
            ) {
                SettingsSectionHeader(
                    title = "Appearance",
                    supportingText = "Choose the workspace theme and focal accent.",
                )
                FlowRow(
                    modifier = Modifier.selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(spacing.spacing8),
                    verticalArrangement = Arrangement.spacedBy(spacing.spacing8),
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
