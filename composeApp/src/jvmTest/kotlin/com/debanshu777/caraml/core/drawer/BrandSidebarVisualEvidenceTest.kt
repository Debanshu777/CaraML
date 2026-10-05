@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.debanshu777.caraml.core.drawer

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
    fun actualOpenSidebarAt390() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                CaraMLTheme(ThemePreferences(themeMode = ThemeMode.LIGHT, reduceMotion = true)) {
                    val backStack = remember { NavBackStack<NavKey>(AppScreen.Home) }
                    Box(Modifier.requiredSize(390.dp, 740.dp).testTag("visual-root")) {
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
