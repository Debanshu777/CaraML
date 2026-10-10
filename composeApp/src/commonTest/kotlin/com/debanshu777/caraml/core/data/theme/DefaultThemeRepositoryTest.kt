package com.debanshu777.caraml.core.data.theme

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.debanshu777.caraml.core.theme.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DefaultThemeRepositoryTest {
    @Test
    fun appearanceEffectsSurviveRepositoryRecreationAlongsideThemeChoice() = runTest {
        val store = MemoryPreferences()
        val repository = DefaultThemeRepository(store)
        val initial = repository.getPreferences().first()
        assertFalse(initial.reduceMotion)
        assertTrue(initial.softEffects)
        repository.updatePreferences(initial.copy(themeMode = ThemeMode.DARK, reduceMotion = true, softEffects = false))

        val restored = DefaultThemeRepository(store).getPreferences().first()
        assertTrue(restored.reduceMotion)
        assertFalse(restored.softEffects)
        assertEquals(ThemeMode.DARK, restored.themeMode)
        assertEquals(initial.seedColor, restored.seedColor)
        assertEquals(initial.paletteStyle, restored.paletteStyle)
    }
}

private class MemoryPreferences : DataStore<Preferences> {
    override val data = MutableStateFlow(emptyPreferences())
    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
        transform(data.value).also { data.value = it }
}
