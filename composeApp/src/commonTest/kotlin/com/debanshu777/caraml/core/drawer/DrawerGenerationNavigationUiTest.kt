@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
package com.debanshu777.caraml.core.drawer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.debanshu777.caraml.core.navigation.AppScreen
import com.debanshu777.caraml.features.chat.domain.GenerationMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DrawerGenerationNavigationUiTest {
    @Test
    fun selectedDrawerModeSurvivesStateRestoration() = runComposeUiTest {
        var registry by mutableStateOf(SaveableStateRegistry(null) { true })
        var showApp by mutableStateOf(true)
        lateinit var modes: GenerationModeController
        setContent {
            if (showApp) {
                CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                    MaterialTheme {
                        val stack = remember { NavBackStack<NavKey>(AppScreen.Home) }
                        AppDrawerShell(Modifier.requiredSize(840.dp, 720.dp), stack) {
                            modes = LocalGenerationModeController.current
                            Box(Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
        onNodeWithContentDescription("Videos").performClick()
        var savedState: Map<String, List<Any?>>? = null
        runOnIdle {
            savedState = registry.performSave()
            showApp = false
        }
        runOnIdle {
            registry = SaveableStateRegistry(savedState) { true }
            showApp = true
        }
        runOnIdle { assertEquals(GenerationMode.Video, modes.mode) }
        onNodeWithContentDescription("Videos, selected").assertIsDisplayed()
    }

    @Test
    fun drawerModesSelectHomeFromOtherDestinationsAndCloseAtLargeText() = runComposeUiTest {
        lateinit var backStack: NavBackStack<NavKey>
        lateinit var modes: GenerationModeController
        lateinit var drawer: DrawerController
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                MaterialTheme {
                    backStack = remember { NavBackStack<NavKey>(AppScreen.Settings) }
                    AppDrawerShell(Modifier.requiredSize(320.dp, 780.dp), backStack) {
                        modes = LocalGenerationModeController.current
                        drawer = LocalDrawerController.current
                        Box(Modifier.fillMaxSize())
                    }
                }
            }
        }
        listOf("Images" to GenerationMode.Image, "Videos" to GenerationMode.Video, "Chat" to GenerationMode.Text).forEach { (label, mode) ->
            runOnIdle { drawer.open() }
            onNodeWithContentDescription(label).assertIsDisplayed().performClick()
            runOnIdle {
                assertEquals(mode, modes.mode)
                assertEquals(listOf(AppScreen.Home), backStack.toList())
                assertFalse(drawer.isOpen)
                drawer.open()
            }
            onNodeWithContentDescription("$label, selected").assertIsDisplayed()
            onNodeWithContentDescription("Models").performClick()
            runOnIdle { assertEquals(AppScreen.Search, backStack.last()) }
        }
    }
}
