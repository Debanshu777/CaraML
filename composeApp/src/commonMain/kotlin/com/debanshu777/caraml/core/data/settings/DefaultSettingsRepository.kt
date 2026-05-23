package com.debanshu777.caraml.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.debanshu777.caraml.core.settings.AppSettings
import com.debanshu777.caraml.core.settings.KvQuantPreset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class DefaultSettingsRepository(
    private val dataStore: DataStore<Preferences>,
) : SettingsRepository {

    private val systemPromptKey = stringPreferencesKey("system_prompt")
    private val temperatureKey = floatPreferencesKey("temperature")
    private val kvQuantPresetKey = stringPreferencesKey("kv_quant_preset")
    private val useGpuKey = booleanPreferencesKey("use_gpu")

    override fun getSettings(): Flow<AppSettings> =
        dataStore.data.map { prefs ->
            AppSettings(
                systemPrompt = prefs[systemPromptKey]
                    ?: AppSettings.Companion.DEFAULT_SYSTEM_PROMPT,
                temperature = prefs[temperatureKey] ?: AppSettings.Companion.DEFAULT_TEMPERATURE,
                kvQuantPreset = prefs[kvQuantPresetKey]
                    ?.let { name -> runCatching { KvQuantPreset.valueOf(name) }.getOrNull() }
                    ?: KvQuantPreset.AUTO,
                useGpu = prefs[useGpuKey] ?: true,
            )
        }

    override suspend fun updateSettings(settings: AppSettings) {
        dataStore.edit { prefs ->
            prefs[systemPromptKey] = settings.systemPrompt
            prefs[temperatureKey] = settings.temperature
            prefs[kvQuantPresetKey] = settings.kvQuantPreset.name
            prefs[useGpuKey] = settings.useGpu
        }
    }
}

