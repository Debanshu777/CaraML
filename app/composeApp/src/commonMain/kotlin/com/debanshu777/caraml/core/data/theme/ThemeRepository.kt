package com.debanshu777.caraml.core.data.theme

import com.debanshu777.caraml.core.theme.ThemePreferences
import kotlinx.coroutines.flow.Flow

interface ThemeRepository {
    fun getPreferences(): Flow<ThemePreferences>
    suspend fun updatePreferences(preferences: ThemePreferences)
}
