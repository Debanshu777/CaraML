package com.debanshu777.caraml.core.data.theme

import androidx.compose.ui.graphics.Color
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.debanshu777.caraml.core.theme.ThemeDefaults
import com.debanshu777.caraml.core.theme.ThemeMode
import com.debanshu777.caraml.core.theme.ThemePaletteStyle
import com.debanshu777.caraml.core.theme.ThemePreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * DataStore-backed [ThemeRepository]. Reuses the single Preferences DataStore
 * registered in [com.debanshu777.caraml.core.di.appModule] alongside
 * [com.debanshu777.caraml.core.data.settings.DefaultSettingsRepository].
 *
 * Color is stored as a 32-bit ARGB value cast to Long; enums are stored by
 * `name` so renames in [ThemeMode] / [ThemePaletteStyle] are compile-time
 * detectable. Unknown names fall back to defaults rather than throwing.
 */
class DefaultThemeRepository(
    private val dataStore: DataStore<Preferences>,
) : ThemeRepository {

    private val seedColorKey = longPreferencesKey("theme_seed_color")
    private val themeModeKey = stringPreferencesKey("theme_mode")
    private val paletteStyleKey = stringPreferencesKey("theme_palette_style")

    override fun getPreferences(): Flow<ThemePreferences> =
        dataStore.data.map { prefs ->
            ThemePreferences(
                seedColor = prefs[seedColorKey]
                    ?.let { Color(it.toInt()) }
                    ?: ThemeDefaults.DEFAULT_SEED_COLOR,
                themeMode = prefs[themeModeKey]
                    ?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } }
                    ?: ThemeDefaults.DEFAULT_THEME_MODE,
                paletteStyle = prefs[paletteStyleKey]
                    ?.let { name -> ThemePaletteStyle.entries.firstOrNull { it.name == name } }
                    ?: ThemeDefaults.DEFAULT_PALETTE_STYLE,
            )
        }

    override suspend fun updatePreferences(preferences: ThemePreferences) {
        dataStore.edit { prefs ->
            prefs[seedColorKey] = preferences.seedColor.toArgbLong()
            prefs[themeModeKey] = preferences.themeMode.name
            prefs[paletteStyleKey] = preferences.paletteStyle.name
        }
    }

    private fun Color.toArgbLong(): Long {
        val a = (alpha * 255f).toInt() and 0xFF
        val r = (red * 255f).toInt() and 0xFF
        val g = (green * 255f).toInt() and 0xFF
        val b = (blue * 255f).toInt() and 0xFF
        return ((a shl 24) or (r shl 16) or (g shl 8) or b).toLong() and 0xFFFFFFFFL
    }
}
