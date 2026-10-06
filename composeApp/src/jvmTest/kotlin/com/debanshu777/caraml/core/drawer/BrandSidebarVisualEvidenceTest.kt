@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.debanshu777.caraml.core.drawer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import com.debanshu777.caraml.core.ui.layout.AppNavigationLayout
import com.debanshu777.caraml.core.ui.motion.LocalAuroraMotionPolicy
import com.debanshu777.caraml.core.ui.motion.auroraMotionPolicy
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.debanshu777.caraml.core.navigation.AppScreen
import com.debanshu777.caraml.core.theme.CaraMLTheme
import com.debanshu777.caraml.core.theme.ThemeMode
import com.debanshu777.caraml.core.theme.ThemePreferences
import com.debanshu777.caraml.core.ui.components.AuroraBackdrop
import com.debanshu777.caraml.features.chat.presentation.ChatScreenContent
import com.debanshu777.caraml.features.chat.presentation.ChatUiState
import com.debanshu777.caraml.features.chat.presentation.StreamingState
import com.debanshu777.caraml.features.modelhub.presentation.search.saveModelHubEvidence
import kotlin.test.Test

class BrandSidebarVisualEvidenceTest {
    @Test
    fun modalHeaderAndPageRevealRespectTheSafeViewportWithoutResizingTheRoute() = runSkikoComposeUiTest(size = Size(390f, 844f)) {
        val controller = DrawerController()
        var viewportHeight by mutableStateOf(844.dp)
        setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(1f),
                LocalAuroraMotionPolicy provides auroraMotionPolicy(durationScale = 0f),
            ) {
                MaterialTheme {
                    Box(Modifier.requiredSize(390.dp, viewportHeight).testTag("safe-sidebar-root")) {
                        AdaptiveNavigation(
                            navigation = AppNavigationLayout.ModalSidebar,
                            items = listOf(DrawerItem("create", "Create", BrandNavigationIcons.Create), DrawerItem("models", "Models", BrandNavigationIcons.Models)),
                            footerItems = listOf(DrawerItem("settings", "Settings", BrandNavigationIcons.Settings)),
                            selectedItemId = "models",
                            onItemClick = {},
                            drawerController = controller,
                            navigationInsets = WindowInsets(top = 60.dp, bottom = 24.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            Box(Modifier.fillMaxSize().background(Color.Magenta).testTag("unchanged-route-layout"))
                        }
                    }
                }
            }
        }
        val closed = onNodeWithTag("unchanged-route-layout").fetchSemanticsNode().boundsInRoot
        assertEquals(844f, closed.height)
        runOnIdle { controller.open() }
        val close = onNodeWithContentDescription("Close navigation menu")
            .assertIsDisplayed().assertIsFocused().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            .fetchSemanticsNode().boundsInRoot
        assertEquals(349f, close.center.x, absoluteTolerance = 1f)
        assertTrue(close.bottom < 136f, "Close must sit above the revealed safe-area page: $close")
        val create = onNodeWithContentDescription("Create").fetchSemanticsNode().boundsInRoot
        val models = onNodeWithContentDescription("Models, selected").fetchSemanticsNode().boundsInRoot
        assertEquals(19f, create.left)
        assertEquals(173f, create.top, absoluteTolerance = 1f)
        assertEquals(222.32f, create.width, absoluteTolerance = 1f)
        assertEquals(12f, models.top - create.bottom, absoluteTolerance = 1f)
        val settings = onNodeWithContentDescription("Settings").fetchSemanticsNode().boundsInRoot
        assertEquals(790f, settings.bottom, absoluteTolerance = 1f)
        // A 760dp safe viewport reveals at y=60+76, ends at y=820−76, with no inset reflow.
        val pixels = onNodeWithTag("safe-sidebar-root").captureToImage().toPixelMap()
        assertTrue(pixels[300, 134] != Color.Magenta)
        assertEquals(Color.Magenta, pixels[300, 138])
        assertEquals(Color.Magenta, pixels[300, 742])
        assertTrue(pixels[300, 746] != Color.Magenta)
        runOnIdle { viewportHeight = 420.dp }
        val shortClose = onNodeWithContentDescription("Close navigation menu")
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(shortClose.right < 390f * .66f, "Short-window close must stay inside the exposed area")
        runOnIdle { viewportHeight = 844.dp }
        onNodeWithContentDescription("Close navigation menu").performClick()
        assertEquals(closed, onNodeWithTag("unchanged-route-layout").fetchSemanticsNode().boundsInRoot)
    }


    @Test
    fun actualOpenSidebarAt390() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                CaraMLTheme(ThemePreferences(themeMode = ThemeMode.LIGHT, reduceMotion = true)) {
                    val backStack = remember { NavBackStack<NavKey>(AppScreen.Home) }
                    Box(Modifier.requiredSize(390.dp, 760.dp).testTag("visual-root")) {
                        AppDrawerShell(backStack = backStack, modifier = Modifier.fillMaxSize()) {
                            AuroraBackdrop {
                                ChatScreenContent(
                                    uiState = ChatUiState.Ready(),
                                    streamingState = StreamingState(),
                                    onSelectModel = {},
                                    onSendMessage = {},
                                    onCancelGeneration = {},
                                    onNavigateToSearch = {},
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    }
                }
            }
        }
        onNodeWithContentDescription("Open navigation menu").performClick()
        onNodeWithContentDescription("Close navigation menu").assertIsDisplayed()
        onNodeWithContentDescription("Models").assertIsDisplayed()
        onNodeWithContentDescription("Settings").assertIsDisplayed()
        saveModelHubEvidence("brand-sidebar-open.png")
    }
}
