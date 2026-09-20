@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.debanshu777.caraml.core.drawer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.debanshu777.caraml.core.navigation.AppScreen
import com.debanshu777.caraml.core.ui.components.CaraMLPrimaryTopBar
import com.debanshu777.caraml.core.ui.layout.AppNavigationLayout
import com.debanshu777.caraml.core.ui.layout.LocalAppNavigationLayout
import com.debanshu777.caraml.core.ui.motion.LocalAuroraMotionPolicy
import com.debanshu777.caraml.core.ui.motion.auroraMotionPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SidebarNavigationUiTest {

    @Test
    fun compactShellStartsClosedAndOverlaysAStationaryRouteSurface() = runComposeUiTest {
        lateinit var backStack: NavBackStack<NavKey>

        mainClock.autoAdvance = false
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                MaterialTheme {
                    backStack = remember { NavBackStack(AppScreen.Home) }
                    AppDrawerShell(
                        backStack = backStack,
                        modifier = Modifier.requiredSize(width = 420.dp, height = 720.dp),
                    ) {
                        Column(Modifier.fillMaxSize().testTag("route-surface")) {
                            CaraMLPrimaryTopBar(title = "Create")
                            Box(Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }

        val closedBounds = onNodeWithTag("route-surface", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        onNodeWithContentDescription("Open navigation menu")
            .assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
        onAllNodesWithContentDescription("Create, selected").assertCountEquals(0)
        onAllNodesWithContentDescription("Models").assertCountEquals(0)
        onAllNodesWithContentDescription("Settings").assertCountEquals(0)

        onNodeWithContentDescription("Open navigation menu").performClick()
        mainClock.advanceTimeBy(90)

        val midpointPanel = onNodeWithTag("modal-sidebar-panel")
            .fetchSemanticsNode().boundsInRoot
        val midpointItem = onNodeWithContentDescription("Create, selected")
            .fetchSemanticsNode().boundsInRoot
        assertTrue(midpointItem.left < 0f, "The standard panel must still be translating at 90ms")
        assertTrue(midpointPanel.width <= 320f, "Compact sidebar width must be capped at 320dp")
        onNodeWithContentDescription("Create, selected").assertIsDisplayed()
        onNodeWithContentDescription("Models").assertIsDisplayed()
        onNodeWithContentDescription("Settings").assertIsDisplayed()
        onNodeWithContentDescription("Dismiss navigation menu").assertIsDisplayed()
        onAllNodesWithContentDescription("Open navigation menu").assertCountEquals(0)
        assertEquals(
            closedBounds,
            onNodeWithTag("route-surface", useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot,
            "The modal sidebar must overlay rather than push or resize route content",
        )

        mainClock.advanceTimeBy(91)
        mainClock.advanceTimeByFrame()
        assertTrue(
            onNodeWithContentDescription("Create, selected")
                .fetchSemanticsNode().boundsInRoot.left >= 0f,
        )

        onNodeWithContentDescription("Dismiss navigation menu").performClick()
        mainClock.advanceTimeBy(500)
        mainClock.advanceTimeByFrame()
        onAllNodesWithContentDescription("Create, selected").assertCountEquals(0)
        assertEquals(AppScreen.Home, backStack.last())
        assertEquals(
            closedBounds,
            onNodeWithTag("route-surface", useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot,
        )
    }

    @Test
    fun width840UsesTheLabeledSidebarInsteadOfWaitingUntilDesktopWide() = runComposeUiTest {
        lateinit var observedLayout: AppNavigationLayout

        setContent {
            MaterialTheme {
                val backStack = remember { NavBackStack<NavKey>(AppScreen.Home) }
                AppDrawerShell(
                    backStack = backStack,
                    modifier = Modifier.requiredSize(width = 840.dp, height = 720.dp),
                ) {
                    observedLayout = LocalAppNavigationLayout.current
                    Box(Modifier.fillMaxSize())
                }
            }
        }

        runOnIdle { assertEquals(AppNavigationLayout.Sidebar, observedLayout) }
        onNodeWithContentDescription("Create, selected").assertIsDisplayed()
    }

    @Test
    fun resizingAnOpenModalSidebarToRailClearsItsTransientState() = runComposeUiTest {
        var width by mutableStateOf(599.dp)
        lateinit var controller: DrawerController

        setContent {
            MaterialTheme {
                val backStack = remember { NavBackStack<NavKey>(AppScreen.Home) }
                Box(Modifier.width(width).height(720.dp)) {
                    AppDrawerShell(backStack = backStack, modifier = Modifier.fillMaxSize()) {
                        controller = LocalDrawerController.current
                        CaraMLPrimaryTopBar(title = "Create")
                    }
                }
            }
        }

        onNodeWithContentDescription("Open navigation menu").performClick()
        runOnIdle { assertTrue(controller.isOpen) }

        runOnIdle { width = 600.dp }
        waitForIdle()
        runOnIdle { assertFalse(controller.isOpen) }

        runOnIdle { width = 599.dp }
        waitForIdle()
        onNodeWithContentDescription("Open navigation menu").assertIsDisplayed()
        onAllNodesWithContentDescription("Create, selected").assertCountEquals(0)
    }

    @Test
    fun resizingInsideCompactModeKeepsTheUserOpenedSidebarAndAdaptsItsWidth() =
        runComposeUiTest {
            var width by mutableStateOf(320.dp)
            lateinit var controller: DrawerController

            setContent {
                MaterialTheme {
                    val backStack = remember { NavBackStack<NavKey>(AppScreen.Home) }
                    Box(Modifier.width(width).height(720.dp)) {
                        AppDrawerShell(backStack = backStack, modifier = Modifier.fillMaxSize()) {
                            controller = LocalDrawerController.current
                            CaraMLPrimaryTopBar(title = "Create")
                        }
                    }
                }
            }

            onNodeWithContentDescription("Open navigation menu").performClick()
            waitForIdle()
            val narrowPanel = onNodeWithTag("modal-sidebar-panel")
                .fetchSemanticsNode().boundsInRoot
            assertEquals(280f, narrowPanel.width)

            runOnIdle { width = 500.dp }
            waitForIdle()
            runOnIdle { assertTrue(controller.isOpen) }
            val widePanel = onNodeWithTag("modal-sidebar-panel")
                .fetchSemanticsNode().boundsInRoot
            assertEquals(320f, widePanel.width)
        }

    @Test
    fun pendingModalSelectionSurvivesAResizeIntoPersistentNavigation() = runComposeUiTest {
        var navigation by mutableStateOf(AppNavigationLayout.ModalSidebar)
        var selectedItemId by mutableStateOf("create")
        val controller = DrawerController(initiallyOpen = true)
        mainClock.autoAdvance = false

        setContent {
            MaterialTheme {
                AdaptiveNavigation(
                    navigation = navigation,
                    items = sidebarPrimaryItems().dropLast(1),
                    footerItems = sidebarPrimaryItems().takeLast(1),
                    selectedItemId = selectedItemId,
                    onItemClick = { selectedItemId = it.id },
                    drawerController = controller,
                    modifier = Modifier.requiredSize(width = 840.dp, height = 720.dp),
                    content = { Box(Modifier.fillMaxSize()) },
                )
            }
        }

        mainClock.advanceTimeBy(181)
        onNodeWithContentDescription("Models").performClick()

        runOnIdle {
            assertEquals(
                "models",
                selectedItemId,
                "Selection must update in the same event that begins closing the sidebar",
            )
            assertFalse(controller.isOpen)
        }

        runOnIdle { navigation = AppNavigationLayout.Rail }
        mainClock.advanceTimeBy(181)
        mainClock.advanceTimeByFrame()

        runOnIdle { assertEquals("models", selectedItemId) }
        onNodeWithContentDescription("Models, selected").assertIsDisplayed()
    }

    @Test
    fun persistentNavigationConsumesTheLeadingSafeInsetExactlyOnce() = runComposeUiTest {
        var navigation by mutableStateOf(AppNavigationLayout.Rail)

        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                MaterialTheme {
                    val safeInsets = WindowInsets(left = 32.dp)
                    AdaptiveNavigation(
                        navigation = navigation,
                        items = sidebarPrimaryItems().dropLast(1),
                        footerItems = sidebarPrimaryItems().takeLast(1),
                        selectedItemId = "create",
                        onItemClick = {},
                        navigationInsets = safeInsets,
                        modifier = Modifier.requiredSize(width = 840.dp, height = 720.dp),
                        content = {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .windowInsetsPadding(safeInsets.only(WindowInsetsSides.Start)),
                            ) {
                                Box(Modifier.fillMaxSize().testTag("safe-route-content"))
                            }
                        },
                    )
                }
            }
        }

        val railItem = onNodeWithContentDescription("Create, selected")
            .fetchSemanticsNode().boundsInRoot
        val railContent = onNodeWithTag("safe-route-content")
            .fetchSemanticsNode().boundsInRoot
        assertTrue(railItem.left >= 40f)
        assertEquals(112f, railContent.left)

        runOnIdle { navigation = AppNavigationLayout.Sidebar }
        val sidebarItem = onNodeWithContentDescription("Create, selected")
            .fetchSemanticsNode().boundsInRoot
        val sidebarContent = onNodeWithTag("safe-route-content")
            .fetchSemanticsNode().boundsInRoot
        assertTrue(sidebarItem.left >= 40f)
        assertEquals(288f, sidebarContent.left)
    }

    @Test
    fun backClosesTheModalSidebarBeforeFallingBackToRouteNavigation() = runComposeUiTest {
        val controller = DrawerController()
        val input = DirectNavigationEventInput()
        var fallbackCount = 0
        val dispatcher = NavigationEventDispatcher { fallbackCount += 1 }.also {
            it.addInput(input)
        }
        val owner = object : NavigationEventDispatcherOwner {
            override val navigationEventDispatcher = dispatcher
        }

        setContent {
            CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides owner) {
                MaterialTheme {
                    AdaptiveNavigation(
                        navigation = AppNavigationLayout.ModalSidebar,
                        items = sidebarPrimaryItems().dropLast(1),
                        footerItems = sidebarPrimaryItems().takeLast(1),
                        selectedItemId = "create",
                        onItemClick = {},
                        drawerController = controller,
                        modifier = Modifier.requiredSize(width = 420.dp, height = 720.dp),
                        content = { Box(Modifier.fillMaxSize()) },
                    )
                }
            }
        }

        runOnIdle { controller.open() }
        waitForIdle()
        runOnIdle { input.backCompleted() }
        runOnIdle {
            assertFalse(controller.isOpen)
            assertEquals(0, fallbackCount)
        }

        waitForIdle()
        runOnIdle { input.backCompleted() }
        runOnIdle { assertEquals(1, fallbackCount) }
    }

    @Test
    fun reducedMotionKeepsTheModalPanelStationaryAndSettlesWithinNinetyMillis() =
        runComposeUiTest {
            val controller = DrawerController()
            mainClock.autoAdvance = false

            setContent {
                CompositionLocalProvider(
                    LocalDensity provides Density(1f),
                    LocalAuroraMotionPolicy provides auroraMotionPolicy(durationScale = 0f),
                ) {
                    MaterialTheme {
                        AdaptiveNavigation(
                            navigation = AppNavigationLayout.ModalSidebar,
                            items = sidebarPrimaryItems().dropLast(1),
                            footerItems = sidebarPrimaryItems().takeLast(1),
                            selectedItemId = "create",
                            onItemClick = {},
                            drawerController = controller,
                            modifier = Modifier.requiredSize(width = 420.dp, height = 720.dp),
                            content = { Box(Modifier.fillMaxSize()) },
                        )
                    }
                }
            }

            runOnIdle { controller.open() }
            mainClock.advanceTimeByFrame()
            val enteringLeft = onNodeWithContentDescription("Create, selected")
                .fetchSemanticsNode().boundsInRoot.left
            mainClock.advanceTimeBy(45)
            val midpointLeft = onNodeWithContentDescription("Create, selected")
                .fetchSemanticsNode().boundsInRoot.left
            assertTrue(enteringLeft >= 0f)
            assertEquals(enteringLeft, midpointLeft)

            mainClock.advanceTimeBy(46)
            mainClock.advanceTimeByFrame()
            onNodeWithContentDescription("Create, selected").assertIsDisplayed()
            assertTrue(
                onNodeWithContentDescription("Create, selected")
                    .fetchSemanticsNode().boundsInRoot.left >= 0f,
            )

            val motion = sidebarMotionSpec(auroraMotionPolicy(durationScale = 0f))
            assertEquals(90, motion.panelDurationMillis)
            assertEquals(90, motion.scrimDurationMillis)
            assertFalse(motion.spatialMovementEnabled)
        }
}

private fun sidebarPrimaryItems() = listOf(
    DrawerItem("create", "Create", Icons.Default.ChatBubbleOutline),
    DrawerItem("models", "Models", Icons.Default.Storage),
    DrawerItem("settings", "Settings", Icons.Default.Settings),
)
