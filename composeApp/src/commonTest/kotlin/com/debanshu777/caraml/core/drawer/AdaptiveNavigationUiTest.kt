@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.debanshu777.caraml.core.drawer

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test

class AdaptiveNavigationUiTest {

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
