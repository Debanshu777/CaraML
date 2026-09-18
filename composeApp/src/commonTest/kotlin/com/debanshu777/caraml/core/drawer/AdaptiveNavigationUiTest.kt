@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.debanshu777.caraml.core.drawer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import com.debanshu777.caraml.core.navigation.AppScreen
import com.debanshu777.caraml.core.navigation.NavigationTransitionDisplay
import com.debanshu777.caraml.core.theme.auroraColors
import com.debanshu777.caraml.core.ui.components.CaraMLPrimaryTopBar
import com.debanshu777.caraml.core.ui.layout.AppNavigationLayout
import com.debanshu777.caraml.core.ui.layout.LocalAppNavigationLayout
import com.debanshu777.caraml.core.ui.motion.LocalAuroraMotionPolicy
import com.debanshu777.caraml.features.chat.domain.GenerationMode
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdaptiveNavigationUiTest {

    @Test
    fun width599UsesClosedModalSidebarWithOneMenuAction() = runComposeUiTest {
        lateinit var observedLayout: AppNavigationLayout

        setContent {
            MaterialTheme {
                val backStack = remember { NavBackStack<NavKey>(AppScreen.Home) }
                AppDrawerShell(
                    backStack = backStack,
                    modifier = Modifier.requiredSize(width = 599.dp, height = 720.dp),
                ) {
                    observedLayout = LocalAppNavigationLayout.current
                    CaraMLPrimaryTopBar(title = "Create")
                }
            }
        }

        runOnIdle { assertEquals(AppNavigationLayout.ModalSidebar, observedLayout) }
        onAllNodesWithContentDescription("Open navigation menu").assertCountEquals(1)
        onAllNodesWithContentDescription("Create, selected").assertCountEquals(0)
        onNodeWithContentDescription("Open navigation menu").performClick()
        onAllNodesWithContentDescription("Create, selected").assertCountEquals(1)
        onAllNodesWithContentDescription("Models").assertCountEquals(1)
        onAllNodesWithContentDescription("Settings").assertCountEquals(1)
        onAllNodesWithContentDescription("Text, selected").assertCountEquals(1)
        onAllNodesWithContentDescription("Image").assertCountEquals(1)
        onAllNodesWithContentDescription("Video").assertCountEquals(1)
    }

    @Test
    fun width600UsesRailAndWidth839StillUsesRail() = runComposeUiTest {
        var windowWidth by mutableStateOf(600.dp)
        lateinit var observedLayout: AppNavigationLayout

        setContent {
            MaterialTheme {
                val backStack = remember { NavBackStack<NavKey>(AppScreen.Home) }
                Box(Modifier.width(windowWidth).height(720.dp)) {
                    AppDrawerShell(
                        modifier = Modifier.fillMaxSize(),
                        backStack = backStack,
                    ) {
                        observedLayout = LocalAppNavigationLayout.current
                        Box(Modifier.fillMaxSize())
                    }
                }
            }
        }

        runOnIdle { assertEquals(AppNavigationLayout.Rail, observedLayout) }
        onAllNodesWithContentDescription("Create, selected").assertCountEquals(1)
        onAllNodesWithText("CaraML").assertCountEquals(0)

        runOnIdle { windowWidth = 839.dp }
        runOnIdle { assertEquals(AppNavigationLayout.Rail, observedLayout) }
        onAllNodesWithContentDescription("Create, selected").assertCountEquals(1)
        onAllNodesWithText("CaraML").assertCountEquals(0)
    }

    @Test
    fun width840UsesContextualSidebar() = runComposeUiTest {
        lateinit var observedLayout: AppNavigationLayout

        setContent {
            MaterialTheme {
                val backStack = remember { NavBackStack<NavKey>(AppScreen.Home) }
                AppDrawerShell(
                    modifier = Modifier.requiredSize(width = 840.dp, height = 720.dp),
                    backStack = backStack,
                ) {
                    observedLayout = LocalAppNavigationLayout.current
                    Box(Modifier.fillMaxSize())
                }
            }
        }

        runOnIdle { assertEquals(AppNavigationLayout.Sidebar, observedLayout) }
        onNodeWithText("CaraML").assertIsDisplayed()
        onNodeWithText("Create modes").assertIsDisplayed()
        onNodeWithText("Text").assertIsDisplayed()
        onNodeWithText("Image").assertIsDisplayed()
        onNodeWithText("Video").assertIsDisplayed()
        onAllNodesWithContentDescription("Create, selected").assertCountEquals(1)
    }

    @Test
    fun generationModeChangeDoesNotChangeSelectedCreateDestination() = runComposeUiTest {
        lateinit var backStack: NavBackStack<NavKey>
        lateinit var modeController: GenerationModeController

        setContent {
            MaterialTheme {
                backStack = remember { NavBackStack(AppScreen.Home) }
                AppDrawerShell(
                    modifier = Modifier.requiredSize(width = 840.dp, height = 720.dp),
                    backStack = backStack,
                ) {
                    modeController = LocalGenerationModeController.current
                    Box(Modifier.fillMaxSize())
                }
            }
        }

        assertCreateAndModeSelection("Text", backStack)

        onNodeWithContentDescription("Image").performClick()
        runOnIdle { assertEquals(GenerationMode.Image, modeController.mode) }
        assertCreateAndModeSelection("Image", backStack)

        onNodeWithContentDescription("Video").performClick()
        runOnIdle { assertEquals(GenerationMode.Video, modeController.mode) }
        assertCreateAndModeSelection("Video", backStack)

        onNodeWithContentDescription("Text").performClick()
        runOnIdle { assertEquals(GenerationMode.Text, modeController.mode) }
        assertCreateAndModeSelection("Text", backStack)
    }

    @Test
    fun selectingModelsUpdatesVisibleRouteAndSelectionAtomically() = runComposeUiTest {
        lateinit var backStack: NavBackStack<NavKey>

        setContent {
            MaterialTheme {
                backStack = remember { NavBackStack(AppScreen.Home) }
                AppDrawerShell(
                    modifier = Modifier.requiredSize(width = 599.dp, height = 720.dp),
                    backStack = backStack,
                ) {
                    val visibleRoute = requireNotNull(backStack.lastOrNull())
                    Column {
                        CaraMLPrimaryTopBar(title = visibleRoute.routeLabel())
                        androidx.compose.material3.Text(
                            text = visibleRoute.routeLabel(),
                            modifier = Modifier.testTag("visible-route"),
                        )
                    }
                }
            }
        }

        onNodeWithTag("visible-route").assertTextEquals("Create route")
        onNodeWithContentDescription("Open navigation menu").performClick()
        onNodeWithContentDescription("Models").performClick()

        onNodeWithTag("visible-route").assertTextEquals("Models route")
        onNodeWithContentDescription("Open navigation menu").performClick()
        onAllNodesWithContentDescription("Models, selected").assertCountEquals(1)
        onAllNodesWithContentDescription("Create").assertCountEquals(1)
        runOnIdle {
            assertEquals(1, backStack.size)
            assertEquals(AppScreen.Search, backStack.last())
        }
    }

    @Test
    fun detailsKeepsModelsSelectedWhileBackReturnsToSearch() = runComposeUiTest {
        lateinit var backStack: NavBackStack<NavKey>

        setContent {
            MaterialTheme {
                backStack = remember {
                    NavBackStack<NavKey>(
                        AppScreen.Search,
                        AppScreen.Details("org/model"),
                    )
                }
                AppDrawerShell(
                    modifier = Modifier.requiredSize(width = 600.dp, height = 720.dp),
                    backStack = backStack,
                ) {
                    val visibleRoute = requireNotNull(backStack.lastOrNull())
                    androidx.compose.material3.Text(
                        text = visibleRoute.routeLabel(),
                        modifier = Modifier.testTag("visible-route"),
                    )
                }
            }
        }

        onNodeWithTag("visible-route").assertTextEquals("Details route")
        onAllNodesWithContentDescription("Models, selected").assertCountEquals(1)
        runOnIdle {
            assertEquals(2, backStack.size)
            backStack.removeLastOrNull()
        }

        onNodeWithTag("visible-route").assertTextEquals("Models route")
        onAllNodesWithContentDescription("Models, selected").assertCountEquals(1)
        runOnIdle {
            assertEquals(1, backStack.size)
            assertEquals(AppScreen.Search, backStack.last())
        }
    }

    @Test
    fun peerDestinationTransitionNeverMovesTheRouteBounds() = runComposeUiTest {
        lateinit var backStack: NavBackStack<NavKey>
        mainClock.autoAdvance = false

        setContent {
            MaterialTheme {
                backStack = remember { NavBackStack(AppScreen.Home) }
                AppDrawerShell(
                    modifier = Modifier.requiredSize(width = 600.dp, height = 720.dp),
                    backStack = backStack,
                ) {
                    NavigationTransitionDisplay(
                        modifier = Modifier.fillMaxSize(),
                        backStack = backStack,
                        motionPolicy = LocalAuroraMotionPolicy.current,
                        detailOffsetPx = with(LocalDensity.current) { 16.dp.roundToPx() },
                        entryDecorators = emptyList(),
                        entryProvider = { key ->
                            NavEntry(key) { route ->
                                val tag = when (route) {
                                    AppScreen.Home -> "create-route-content"
                                    AppScreen.Search -> "models-route-content"
                                    else -> error("Unexpected peer route: $route")
                                }
                                Box(Modifier.fillMaxSize().testTag(tag))
                            }
                        },
                    )
                }
            }
        }

        val initialBounds = onNodeWithTag("create-route-content", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        onNodeWithContentDescription("Models").performClick()
        mainClock.advanceTimeBy(90)
        val midTransitionBounds = onNodeWithTag("models-route-content", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        mainClock.advanceTimeBy(180)
        val settledBounds = onNodeWithTag("models-route-content", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot

        assertEquals(initialBounds, midTransitionBounds)
        assertEquals(initialBounds, settledBounds)
        runOnIdle { assertEquals(AppScreen.Search, backStack.last()) }
    }

    @Test
    fun modalSidebarAndRailRespectSafeDrawingInsetsAtTwoHundredPercentFontScale() =
        runComposeUiTest {
            var navigation by mutableStateOf(AppNavigationLayout.ModalSidebar)
            val drawerController = DrawerController()
            val height = 260.dp

            setContent {
                CompositionLocalProvider(
                    LocalDensity provides Density(density = 1f, fontScale = 2f),
                ) {
                    MaterialTheme {
                        AdaptiveNavigation(
                            navigation = navigation,
                            items = primaryNavigationItems().dropLast(1),
                            footerItems = primaryNavigationItems().takeLast(1),
                            selectedItemId = "create",
                            onItemClick = {},
                            drawerController = drawerController,
                            navigationInsets = WindowInsets(top = 48.dp, bottom = 56.dp),
                            modifier = Modifier.requiredSize(width = 420.dp, height = height),
                            content = { Box(Modifier.fillMaxSize()) },
                        )
                    }
                }
            }

            runOnIdle { drawerController.open() }
            waitForIdle()
            val compactCreate = onNodeWithContentDescription("Create, selected")
                .performScrollTo()
                .assertIsDisplayed()
                .fetchSemanticsNode().boundsInRoot
            val compactSettings = onNodeWithContentDescription("Settings")
                .assertIsDisplayed()
                .fetchSemanticsNode().boundsInRoot
            assertTrue(compactCreate.bottom <= height.value - 56f)
            assertTrue(compactSettings.bottom <= height.value - 56f)

            runOnIdle { navigation = AppNavigationLayout.Rail }

            val railCreate = onNodeWithContentDescription("Create, selected")
                .assertIsDisplayed()
                .fetchSemanticsNode().boundsInRoot
            assertTrue(railCreate.top >= 48f)
            onNodeWithContentDescription("Settings")
                .assertIsDisplayed()
            val railSettings = onNodeWithContentDescription("Settings")
                .fetchSemanticsNode().boundsInRoot
            assertTrue(railSettings.bottom <= height.value - 56f)
        }

    @Test
    fun contextualSidebarRemainsScrollableInsideSafeInsetsAtTwoHundredPercentFontScale() =
        runComposeUiTest {
            val height = 360.dp

            setContent {
                CompositionLocalProvider(
                    LocalDensity provides Density(density = 1f, fontScale = 2f),
                ) {
                    MaterialTheme {
                        AdaptiveNavigation(
                            navigation = AppNavigationLayout.Sidebar,
                            items = primaryNavigationItems(),
                            selectedItemId = "create",
                            onItemClick = {},
                            navigationInsets = WindowInsets(top = 48.dp, bottom = 56.dp),
                            contextualItems = modeNavigationItems(),
                            selectedContextualItemId = "mode-text",
                            onContextualItemClick = {},
                            modifier = Modifier.requiredSize(width = 520.dp, height = height),
                            content = { Box(Modifier.fillMaxSize()) },
                        )
                    }
                }
            }

            val header = onNodeWithText("CaraML").fetchSemanticsNode().boundsInRoot
            assertTrue(header.top >= 48f)
            onNodeWithContentDescription("Video")
                .performScrollTo()
                .assertIsDisplayed()
            val lastMode = onNodeWithContentDescription("Video").fetchSemanticsNode().boundsInRoot
            assertTrue(lastMode.bottom <= height.value - 56f)
        }

    @Test
    fun selectedNavigationItemUsesOneSignalRailOverANeutralTonalSurface() = runComposeUiTest {
        val scheme = darkColorScheme(
            surface = Color(0xFF101217),
            surfaceContainerHigh = Color(0xFF2B303A),
            primary = Color(0xFF4F83FF),
        )
        var selectedSurface = Color.Unspecified

        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                MaterialTheme(colorScheme = scheme) {
                    selectedSurface = MaterialTheme.auroraColors.selectedSurface
                    AdaptiveNavigation(
                        navigation = AppNavigationLayout.Sidebar,
                        items = primaryNavigationItems(),
                        selectedItemId = "create",
                        onItemClick = {},
                        navigationInsets = WindowInsets(0),
                        modifier = Modifier.requiredSize(width = 520.dp, height = 360.dp),
                        content = {},
                    )
                }
            }
        }

        val pixels = onNodeWithContentDescription("Create, selected")
            .captureToImage()
            .toPixelMap()
        val centerY = pixels.height / 2
        (0..2).forEach { x ->
            assertTrue(
                pixels[x, centerY].colorDistance(scheme.primary) <= 0.05f,
                "Selected navigation must paint one 3dp accent signal rail",
            )
        }
        assertTrue(pixels[3, centerY].colorDistance(selectedSurface) <= 0.05f)
        assertTrue(pixels[pixels.width - 8, centerY].colorDistance(selectedSurface) <= 0.05f)
        assertTrue(
            pixels[3, centerY].colorDistance(pixels[pixels.width - 8, centerY]) <= 0.02f,
            "Selected navigation background must remain a uniform neutral tone",
        )
    }

    private fun androidx.compose.ui.test.ComposeUiTest.assertCreateAndModeSelection(
        modeLabel: String,
        backStack: NavBackStack<NavKey>,
    ) {
        onAllNodesWithContentDescription("Create, selected").assertCountEquals(1)
        onAllNodesWithContentDescription("$modeLabel, selected").assertCountEquals(1)
        runOnIdle { assertEquals(AppScreen.Home, backStack.last()) }
    }
}

private fun primaryNavigationItems() = listOf(
    DrawerItem("create", "Create", Icons.Default.ChatBubbleOutline),
    DrawerItem("models", "Models", Icons.Default.Storage),
    DrawerItem("settings", "Settings", Icons.Default.Settings),
)

private fun modeNavigationItems() = listOf(
    DrawerItem("mode-text", "Text", Icons.Default.ChatBubbleOutline),
    DrawerItem("mode-image", "Image", Icons.Default.ChatBubbleOutline),
    DrawerItem("mode-video", "Video", Icons.Default.ChatBubbleOutline),
)

private fun NavKey.routeLabel(): String = when (this) {
    AppScreen.Home -> "Create route"
    AppScreen.Search -> "Models route"
    AppScreen.Settings -> "Settings route"
    is AppScreen.Details -> "Details route"
    else -> error("Unexpected route $this")
}

private fun Color.colorDistance(other: Color): Float =
    abs(red - other.red) + abs(green - other.green) + abs(blue - other.blue)
