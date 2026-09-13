@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.debanshu777.caraml.core.drawer

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubbleOutline
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
import androidx.compose.ui.test.assertIsDisplayed
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
import androidx.navigation3.runtime.NavKey
import com.debanshu777.caraml.core.navigation.AppScreen
import com.debanshu777.caraml.core.ui.layout.AppNavigationLayout
import com.debanshu777.caraml.features.chat.presentation.components.ModelSelectorTopBar
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdaptiveNavigationUiTest {

    @Test
    fun openingModalDrawerKeepsRouteContentAtIdenticalBounds() = runComposeUiTest {
        var drawerState by mutableStateOf(CustomDrawerState.Closed)
        mainClock.autoAdvance = false

        setContent {
            MaterialTheme {
                AnimatedDrawerScaffold(
                    drawerState = drawerState,
                    onDrawerStateChange = { drawerState = it },
                    gestureEnabled = true,
                    drawerContent = { Box(Modifier.fillMaxSize()) },
                    content = {
                        Box(Modifier.fillMaxSize().testTag("route-content"))
                    },
                    modifier = Modifier.requiredSize(width = 320.dp, height = 480.dp),
                )
            }
        }

        val closedBounds = onNodeWithTag("route-content", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        runOnIdle { drawerState = CustomDrawerState.Opened }
        mainClock.advanceTimeBy(150)
        val midTransitionBounds = onNodeWithTag("route-content", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        mainClock.advanceTimeBy(500)
        val openBounds = onNodeWithTag("route-content", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot

        assertEquals(closedBounds, midTransitionBounds)
        assertEquals(closedBounds, openBounds)
    }

    @Test
    fun drawerDestinationChangesOnlyAfterTheOverlayCloses() = runComposeUiTest {
        lateinit var backStack: NavBackStack<NavKey>
        mainClock.autoAdvance = false

        setContent {
            MaterialTheme {
                backStack = remember { NavBackStack(AppScreen.Home) }
                AppDrawerShell(
                    backStack = backStack,
                    modifier = Modifier.requiredSize(width = 599.dp, height = 720.dp),
                ) {
                    ModelSelectorTopBar(
                        onMenuClick = LocalDrawerController.current::toggle,
                    )
                }
            }
        }

        onNodeWithContentDescription("Open navigation menu").performClick()
        mainClock.advanceTimeBy(300)
        onNodeWithText("Models").performClick()

        runOnIdle { assertEquals(AppScreen.Home, backStack.last()) }
        mainClock.advanceTimeBy(300)
        runOnIdle { assertEquals(AppScreen.Search, backStack.last()) }
    }

    @Test
    fun primaryTopBarShowsOneMenuActionOnlyInTheModalShell() = runComposeUiTest {
        var windowWidth by mutableStateOf(599.dp)

        setContent {
            MaterialTheme {
                val backStack = remember { NavBackStack<NavKey>(AppScreen.Home) }
                Box(Modifier.width(windowWidth).height(720.dp)) {
                    AppDrawerShell(
                        modifier = Modifier.fillMaxSize(),
                        backStack = backStack,
                    ) {
                        ModelSelectorTopBar(
                            onMenuClick = LocalDrawerController.current::toggle,
                        )
                    }
                }
            }
        }

        onAllNodesWithContentDescription("Open navigation menu").assertCountEquals(1)

        runOnIdle { windowWidth = 600.dp }
        onAllNodesWithContentDescription("Open navigation menu").assertCountEquals(0)
        onAllNodesWithContentDescription("Chat, selected").assertCountEquals(1)

        runOnIdle { windowWidth = 839.dp }
        onAllNodesWithContentDescription("Open navigation menu").assertCountEquals(0)
        onAllNodesWithText("CaraML").assertCountEquals(0)

        runOnIdle { windowWidth = 840.dp }
        onAllNodesWithContentDescription("Open navigation menu").assertCountEquals(0)
        onAllNodesWithText("CaraML").assertCountEquals(1)
        onAllNodesWithContentDescription("Chat, selected").assertCountEquals(1)
    }

    @Test
    fun leavingModalLayoutClosesTheDrawerBeforeReturningToCompactWidth() = runComposeUiTest {
        var windowWidth by mutableStateOf(599.dp)
        lateinit var controller: DrawerController

        setContent {
            MaterialTheme {
                val backStack = remember { NavBackStack<NavKey>(AppScreen.Home) }
                Box(Modifier.width(windowWidth).height(720.dp)) {
                    AppDrawerShell(
                        modifier = Modifier.fillMaxSize(),
                        backStack = backStack,
                    ) {
                        controller = LocalDrawerController.current
                        ModelSelectorTopBar(onMenuClick = controller::toggle)
                    }
                }
            }
        }

        onNodeWithContentDescription("Open navigation menu").performClick()
        runOnIdle { assertEquals(CustomDrawerState.Opened, controller.drawerState) }
        onNodeWithText("CaraML").assertIsDisplayed()

        runOnIdle { windowWidth = 600.dp }
        runOnIdle { assertEquals(CustomDrawerState.Closed, controller.drawerState) }

        runOnIdle { windowWidth = 599.dp }
        onAllNodesWithText("CaraML").assertCountEquals(0)
        onAllNodesWithContentDescription("Open navigation menu").assertCountEquals(1)
    }

    @Test
    fun railKeepsFirstAndLastActionsInsideInjectedSafeDrawingInsetsAtLargeText() =
        runComposeUiTest {
            var clickedId = ""
            val height = 320.dp
            setContent {
                CompositionLocalProvider(
                    LocalDensity provides Density(density = 1f, fontScale = 2f),
                ) {
                    MaterialTheme {
                        AdaptiveNavigation(
                            navigation = AppNavigationLayout.Rail,
                            items = persistentNavigationItems(),
                            selectedItemId = "chat",
                            drawerState = CustomDrawerState.Closed,
                            onDrawerStateChange = {},
                            gestureEnabled = false,
                            onItemClick = { clickedId = it.id },
                            navigationInsets = WindowInsets(top = 48.dp, bottom = 56.dp),
                            modifier = Modifier.requiredSize(width = 420.dp, height = height),
                            content = {},
                        )
                    }
                }
            }

            val first = onNodeWithContentDescription("Chat, selected").fetchSemanticsNode()
            assertTrue(first.boundsInRoot.top >= 48f)

            onNodeWithContentDescription("Settings")
                .performScrollTo()
                .assertIsDisplayed()
                .performClick()
            val last = onNodeWithContentDescription("Settings").fetchSemanticsNode()
            assertTrue(last.boundsInRoot.bottom <= height.value - 56f)
            runOnIdle { assertEquals("settings", clickedId) }
        }

    @Test
    fun sidebarKeepsHeaderAndLastActionInsideInjectedSafeDrawingInsetsAtLargeText() =
        runComposeUiTest {
            var clickedId = ""
            val height = 360.dp
            setContent {
                CompositionLocalProvider(
                    LocalDensity provides Density(density = 1f, fontScale = 2f),
                ) {
                    MaterialTheme {
                        AdaptiveNavigation(
                            navigation = AppNavigationLayout.Sidebar,
                            items = persistentNavigationItems(),
                            selectedItemId = "chat",
                            drawerState = CustomDrawerState.Closed,
                            onDrawerStateChange = {},
                            gestureEnabled = false,
                            onItemClick = { clickedId = it.id },
                            navigationInsets = WindowInsets(top = 48.dp, bottom = 56.dp),
                            modifier = Modifier.requiredSize(width = 520.dp, height = height),
                            content = {},
                        )
                    }
                }
            }

            val header = onNodeWithText("CaraML").fetchSemanticsNode()
            assertTrue(header.boundsInRoot.top >= 48f)

            onNodeWithContentDescription("Settings")
                .performScrollTo()
                .assertIsDisplayed()
                .performClick()
            val last = onNodeWithContentDescription("Settings").fetchSemanticsNode()
            assertTrue(last.boundsInRoot.bottom <= height.value - 56f)
            runOnIdle { assertEquals("settings", clickedId) }
        }

    @Test
    fun expandedPanelKeepsHeaderAndActionsBelowInjectedSafeTopInsetAtLargeText() = runComposeUiTest {
        var clickCount = 0

        setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                MaterialTheme {
                    AppNavigationPanel(
                        items = listOf(
                            DrawerItem(
                                id = "chat",
                                title = "Chat",
                                icon = Icons.Default.ChatBubbleOutline,
                            ),
                        ),
                        selectedItemId = "chat",
                        compact = false,
                        onItemClick = { clickCount += 1 },
                        modifier = Modifier.requiredSize(width = 360.dp, height = 640.dp),
                        contentInsets = WindowInsets(top = 48.dp),
                    )
                }
            }
        }

        val headerTop = onNodeWithText("CaraML").fetchSemanticsNode().boundsInRoot.top
        assertTrue(
            headerTop >= 48f,
            "Drawer header must start below the injected 48dp safe top inset, but started at ${headerTop}px",
        )
        onNodeWithContentDescription("Chat, selected")
            .assertIsDisplayed()
            .performClick()
        assertEquals(1, clickCount)
    }

    @Test
    fun expandedPanelShowsBrandAndDestinationLabels() = runComposeUiTest {
        setContent {
            MaterialTheme {
                AppNavigationPanel(
                    items = listOf(
                        DrawerItem(
                            id = "chat",
                            title = "Chat",
                            icon = Icons.Default.ChatBubbleOutline,
                        ),
                    ),
                    selectedItemId = "chat",
                    compact = false,
                    onItemClick = {},
                )
            }
        }

        onNodeWithText("CaraML").assertIsDisplayed()
        onNodeWithText("Chat").assertIsDisplayed()
        onNodeWithContentDescription("Chat, selected").assertIsDisplayed()
    }

    @Test
    fun closedDrawerRemovesItsVisualAndSemanticPanel() = runComposeUiTest {
        var drawerState by mutableStateOf(CustomDrawerState.Opened)

        setContent {
            MaterialTheme {
                AnimatedDrawerScaffold(
                    drawerState = drawerState,
                    onDrawerStateChange = { drawerState = it },
                    gestureEnabled = true,
                    drawerContent = {
                        AppNavigationPanel(
                            items = listOf(
                                DrawerItem(
                                    id = "chat",
                                    title = "Chat",
                                    icon = Icons.Default.ChatBubbleOutline,
                                ),
                            ),
                            selectedItemId = "chat",
                            compact = false,
                            onItemClick = {},
                        )
                    },
                    content = {},
                )
            }
        }

        onNodeWithText("CaraML").assertIsDisplayed()
        runOnIdle { drawerState = CustomDrawerState.Closed }
        waitForIdle()

        onAllNodesWithText("CaraML").assertCountEquals(0)
        onAllNodesWithContentDescription("Chat, selected").assertCountEquals(0)
    }
}

private fun persistentNavigationItems() = listOf(
    DrawerItem("chat", "Chat", Icons.Default.ChatBubbleOutline),
    DrawerItem("image", "Image", Icons.Default.ChatBubbleOutline),
    DrawerItem("video", "Video", Icons.Default.ChatBubbleOutline),
    DrawerItem("models", "Models", Icons.Default.ChatBubbleOutline),
    DrawerItem("settings", "Settings", Icons.Default.ChatBubbleOutline),
)
