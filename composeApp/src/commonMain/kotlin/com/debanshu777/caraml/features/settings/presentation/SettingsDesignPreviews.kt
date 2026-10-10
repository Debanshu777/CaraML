package com.debanshu777.caraml.features.settings.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.data.theme.ThemeRepository
import com.debanshu777.caraml.core.theme.AppTheme
import com.debanshu777.caraml.core.theme.CaraMLTheme
import com.debanshu777.caraml.core.theme.ThemeMode
import com.debanshu777.caraml.core.theme.ThemePreferences
import com.debanshu777.caraml.core.theme.ThemeViewModel
import com.debanshu777.caraml.core.ui.components.AuroraBackdrop
import com.debanshu777.caraml.core.ui.components.BrandPageHeader
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

@Preview(name = "Settings compact", widthDp = 412, heightDp = 915)
@Composable
private fun SettingsCompactPreview() {
    val themeViewModel = remember {
        ThemeViewModel(SettingsPreviewThemeRepository(ThemePreferences(themeMode = ThemeMode.LIGHT)))
    }
    val preferences by themeViewModel.preferences.collectAsState()
    CaraMLTheme(preferences) {
        AuroraBackdrop {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 22.dp),
                verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.spacing24),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.spacing12)) {
                    BrandPageHeader(title = "Your kind of CaraML.", navigationAction = {})
                    Text(
                        "A few small things. Just how you like them.",
                        style = AppTheme.typography.bodySmall,
                        color = AppTheme.colors.onSurface,
                    )
                }
                AppearanceSection(viewModel = themeViewModel)
                SettingsSectionHeader(
                    title = "Runtime",
                    supportingText = "Tune the local inference engine for this device.",
                )
                GpuAccelerationSection(enabled = true, onToggle = {})
            }
        }
    }
}

private class SettingsPreviewThemeRepository(initial: ThemePreferences) : ThemeRepository {
    private val preferences = MutableStateFlow(initial)
    override fun getPreferences(): Flow<ThemePreferences> = preferences
    override suspend fun updatePreferences(preferences: ThemePreferences) {
        this.preferences.value = preferences
    }
}
