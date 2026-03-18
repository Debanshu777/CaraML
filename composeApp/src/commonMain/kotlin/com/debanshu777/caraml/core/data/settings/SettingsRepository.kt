package com.debanshu777.caraml.core.data.settings

import com.debanshu777.caraml.core.settings.AppSettings
import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    fun getSettings(): Flow<AppSettings>
    suspend fun updateSettings(settings: AppSettings)
}
