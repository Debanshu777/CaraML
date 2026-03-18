package com.debanshu777.caraml.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.debanshu777.caraml.core.settings.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class DefaultSettingsRepository(
    private val dataStore: DataStore<Preferences>,
) : SettingsRepository {

    private val systemPromptKey = stringPreferencesKey("system_prompt")
    private val temperatureKey = floatPreferencesKey("temperature")

    override fun getSettings(): Flow<AppSettings> =
        dataStore.data.map { prefs ->
            AppSettings(
                systemPrompt = prefs[systemPromptKey]
                    ?: AppSettings.Companion.DEFAULT_SYSTEM_PROMPT,
                temperature = prefs[temperatureKey] ?: AppSettings.Companion.DEFAULT_TEMPERATURE
            )
        }

    override suspend fun updateSettings(settings: AppSettings) {
        dataStore.edit { prefs ->
            prefs[systemPromptKey] = settings.systemPrompt
            prefs[temperatureKey] = settings.temperature
        }
    }
}
