package com.debanshu777.caraml.core.theme

import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.debanshu777.caraml.core.data.theme.ThemeRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Single source of truth for the in-memory [ThemePreferences] state.
 *
 * Injected at the App composition root so the entire UI tree re-renders when
 * the user picks a new color/mode/style. Reused by Settings UI for editing.
 */
class ThemeViewModel(
    private val repository: ThemeRepository,
) : ViewModel() {
    private val preferenceUpdates = Mutex()

    val preferences: StateFlow<ThemePreferences> = repository.getPreferences()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ThemePreferences(),
        )

    fun updateSeedColor(color: Color) {
        updatePreferences { it.copy(seedColor = color) }
    }

    fun updateThemeMode(mode: ThemeMode) {
        updatePreferences { it.copy(themeMode = mode) }
    }

    fun updatePaletteStyle(style: ThemePaletteStyle) {
        updatePreferences { it.copy(paletteStyle = style) }
    }

    fun updateReduceMotion(reduceMotion: Boolean) {
        updatePreferences { it.copy(reduceMotion = reduceMotion) }
    }

    fun updateSoftEffects(softEffects: Boolean) {
        updatePreferences { it.copy(softEffects = softEffects) }
    }

    private fun updatePreferences(transform: (ThemePreferences) -> ThemePreferences) {
        viewModelScope.launch {
            // Read after the previous write so fast successive controls cannot undo each other.
            preferenceUpdates.withLock {
                repository.updatePreferences(transform(repository.getPreferences().first()))
            }
        }
    }
}
