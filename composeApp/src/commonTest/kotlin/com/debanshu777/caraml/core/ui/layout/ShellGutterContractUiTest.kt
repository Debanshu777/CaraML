@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.debanshu777.caraml.core.ui.layout

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.debanshu777.caraml.core.drawer.AppDrawerShell
import com.debanshu777.caraml.core.navigation.AppScreen
import com.debanshu777.caraml.core.ui.components.CaraMLPrimaryTopBar
import com.debanshu777.caraml.core.ui.components.CaraMLTopBar
import com.debanshu777.caraml.core.ui.components.TopBarNavigation
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class ShellGutterContractUiTest {

    @Test
    fun shellKeepsOuterWindowGuttersAcrossModelsSettingsAndDetails() = runComposeUiTest {
        var width by mutableStateOf(599.dp)
        var contentKind by mutableStateOf(AppContentKind.ModelHub)

        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                MaterialTheme {
                    val backStack = remember {
                        NavBackStack<NavKey>(AppScreen.Details("org/model"))
                    }
                    AppDrawerShell(
                        modifier = Modifier
                            .requiredSize(width = width, height = 720.dp)
                            .testTag("app-shell"),
                        backStack = backStack,
                    ) {
                        Column(Modifier.fillMaxSize()) {
                            if (contentKind == AppContentKind.Details) {
                                CaraMLTopBar(
                                    title = "Artifact",
                                    navigation = TopBarNavigation.Back,
                                    onNavigationClick = {},
                                    contentKind = contentKind,
                                )
                            } else {
                                CaraMLPrimaryTopBar(
                                    title = "${contentKind.name} workspace",
                                    contentKind = contentKind,
                                )
                            }
                            ResponsiveContentPane(
                                kind = contentKind,
                                modifier = Modifier.weight(1f),
                            ) {
                                Box(
                                    Modifier
                                        .fillMaxSize()
                                        .testTag("workspace-body"),
                                )
                            }
                        }
                    }
                }
            }
        }

        val cases = listOf(
            GutterCase(AppContentKind.ModelHub, 599.dp, 16f),
            GutterCase(AppContentKind.ModelHub, 600.dp, 104f),
            GutterCase(AppContentKind.ModelHub, 1199.dp, 143.5f),
            GutterCase(AppContentKind.ModelHub, 1200.dp, 280f),
            GutterCase(AppContentKind.Settings, 599.dp, 16f),
            GutterCase(AppContentKind.Settings, 600.dp, 104f),
            GutterCase(AppContentKind.Settings, 1199.dp, 283.5f),
            GutterCase(AppContentKind.Settings, 1200.dp, 372f),
            GutterCase(AppContentKind.Details, 599.dp, 16f),
            GutterCase(AppContentKind.Details, 600.dp, 104f),
            GutterCase(AppContentKind.Details, 1199.dp, 143.5f),
            GutterCase(AppContentKind.Details, 1200.dp, 280f),
        )

        cases.forEach { case ->
            runOnIdle {
                width = case.windowWidth
                contentKind = case.contentKind
            }
            waitForIdle()

            val shellLeft = onNodeWithTag("app-shell")
                .fetchSemanticsNode().boundsInRoot.left
            val bodyLeft = onNodeWithTag("workspace-body")
                .fetchSemanticsNode().boundsInRoot.left - shellLeft
            val headerLeft = if (case.contentKind == AppContentKind.Details) {
                onNodeWithContentDescription("Navigate back")
                    .fetchSemanticsNode().boundsInRoot.left
            } else {
                onNodeWithText("${case.contentKind.name} workspace")
                    .fetchSemanticsNode().boundsInRoot.left
            } - shellLeft

            assertNear(
                expected = case.expectedLeadingEdge,
                actual = bodyLeft,
                label = "${case.contentKind} body at ${case.windowWidth}",
            )
            assertNear(
                expected = case.expectedLeadingEdge,
                actual = headerLeft,
                label = "${case.contentKind} header at ${case.windowWidth}",
            )
        }
    }
}

private data class GutterCase(
    val contentKind: AppContentKind,
    val windowWidth: Dp,
    val expectedLeadingEdge: Float,
)

private fun assertNear(expected: Float, actual: Float, label: String) {
    assertTrue(
        abs(expected - actual) <= 1f,
        "$label expected x=$expected but was x=$actual",
    )
}
