package com.debanshu777.caraml.features.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.debanshu777.caraml.core.settings.AppSettings
import com.debanshu777.caraml.core.data.settings.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val repository: SettingsRepository,
) : ViewModel() {

    val settings = repository.getSettings()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = AppSettings()
        )

    fun updateSystemPrompt(systemPrompt: String) {
        viewModelScope.launch {
            repository.updateSettings(settings.value.copy(systemPrompt = systemPrompt))
        }
    }

    fun updateTemperature(temperature: Float) {
        val clamped = temperature.coerceIn(0f, 2f)
        viewModelScope.launch {
            repository.updateSettings(settings.value.copy(temperature = clamped))
        }
    }
}
