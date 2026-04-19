package com.debanshu777.caraml.core.theme

import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.debanshu777.caraml.core.data.theme.ThemeRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Single source of truth for the in-memory [ThemePreferences] state.
 *
 * Injected at the App composition root so the entire UI tree re-renders when
 * the user picks a new color/mode/style. Reused by Settings UI for editing.
 */
class ThemeViewModel(
    private val repository: ThemeRepository,
) : ViewModel() {

    val preferences: StateFlow<ThemePreferences> = repository.getPreferences()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ThemePreferences(),
        )

    fun updateSeedColor(color: Color) {
        viewModelScope.launch {
            repository.updatePreferences(preferences.value.copy(seedColor = color))
        }
    }

    fun updateThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            repository.updatePreferences(preferences.value.copy(themeMode = mode))
        }
    }

    fun updatePaletteStyle(style: ThemePaletteStyle) {
        viewModelScope.launch {
            repository.updatePreferences(preferences.value.copy(paletteStyle = style))
        }
    }
}
