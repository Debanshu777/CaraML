@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.debanshu777.caraml.features.modelhub.presentation.search

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.drawer.LocalNavigationMenuAction
import com.debanshu777.caraml.core.ui.components.FrostedPageScaffold
import com.debanshu777.caraml.core.ui.layout.AppContentKind
import com.debanshu777.caraml.core.theme.ThemePreferences
import com.debanshu777.caraml.core.theme.CaraMLTheme
import com.debanshu777.caraml.features.modelhub.presentation.search.components.SearchBar
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModelHubStickyChromeTest {
    @Test
    fun scrollingHeaderClearsStatusGlassWithoutReservingAnEmptyAppBar() = runComposeUiTest {
        setContent {
            CaraMLTheme(ThemePreferences()) {
                Box(Modifier.requiredSize(820.dp, 360.dp)) {
                    FrostedPageScaffold(
                        kind = AppContentKind.ModelHub,
                        header = null,
                        safeInsets = WindowInsets(0, 24, 0, 0),
                    ) { padding ->
                        Box(Modifier.fillMaxWidth().padding(padding)) {
                            Box(Modifier.size(1.dp).testTag("safe-top-origin"))
                        }
                    }
                }
            }
        }

        val origin = onNodeWithTag("safe-top-origin").fetchSemanticsNode().boundsInRoot
        assertEquals(24f, onNodeWithTag("page-sticky-chrome").fetchSemanticsNode().boundsInRoot.height)
        assertEquals(56f, origin.top, "The first control must clear the 24dp inset and 32dp fade")
    }

    @Test
    fun landscapeKeepsTheWholeSearchFieldVisibleAndResultsScrollable() = runComposeUiTest {
        setContent {
            CaraMLTheme(ThemePreferences()) {
                Box(Modifier.requiredSize(820.dp, 360.dp)) {
                    ModelHubScreenLayout(
                        selectedTabIndex = 0, onTabSelected = {},
                        sharedContext = {
                            Box(Modifier.fillMaxWidth().height(60.dp).testTag("model-device-card"))
                        },
                        discoverContent = {
                            ModelHubTabLayout(
                                context = {}, toolbar = {}, summary = {},
                                command = { SearchBar("", {}, {}) },
                                results = {
                                    items(40) { Text("Model $it", Modifier.height(100.dp)) }
                                },
                            )
                        }, libraryContent = {},
                    )
                }
            }
        }

        val title = onNodeWithText("Models").fetchSemanticsNode().boundsInRoot
        val search = onNodeWithTag("model-command").fetchSemanticsNode().boundsInRoot
        val device = onNodeWithTag("model-device-card").fetchSemanticsNode().boundsInRoot
        assertTrue(title.top < 48f, "Empty header chrome pushed the title down: $title")
        assertTrue(search.bottom <= 360f, "Search field was clipped below the landscape viewport: $search")
        assertTrue(device.top - search.bottom >= 20f, "Search and device card are too close: $search, $device")
        onNodeWithTag("model-command").assertIsDisplayed()
        onNodeWithTag("model-primary-results").performScrollToIndex(25)
        onNodeWithText("Model 21").assertIsDisplayed()
    }

    @Test
    fun titleTabsAndSearchKeepTheirBoundsWhenResultsScroll() = runComposeUiTest {
        setContent {
            CaraMLTheme(ThemePreferences()) {
                CompositionLocalProvider(LocalNavigationMenuAction provides {}) {
                    Box(Modifier.requiredSize(390.dp, 740.dp)) {
                        ModelHubScreenLayout(
                            selectedTabIndex = 0, onTabSelected = {},
                            discoverContent = {
                                ModelHubTabLayout(
                                    context = {}, toolbar = {}, summary = {},
                                    command = { SearchBar("", {}, {}) },
                                    results = {
                                        items(40) { Text("Model $it", Modifier.height(100.dp)) }
                                    },
                                )
                            }, libraryContent = {},
                        )
                    }
                }
            }
        }
        val title = onNodeWithText("Models").fetchSemanticsNode().boundsInRoot
        val search = onNodeWithTag("model-command").fetchSemanticsNode().boundsInRoot
        val tabs = onNodeWithTag("model-tabs").fetchSemanticsNode().boundsInRoot
        onNodeWithTag("model-primary-results").performScrollToIndex(25)
        onNodeWithContentDescription("Open navigation menu").assertIsDisplayed()
        onNodeWithText("Models").assertIsDisplayed()
        assertEquals(title, onNodeWithText("Models").fetchSemanticsNode().boundsInRoot)
        assertEquals(search, onNodeWithTag("model-command").fetchSemanticsNode().boundsInRoot)
        assertEquals(tabs, onNodeWithTag("model-tabs").fetchSemanticsNode().boundsInRoot)
    }
}
