@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.debanshu777.caraml.core.drawer

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdaptiveNavigationUiTest {

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
