@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.debanshu777.caraml.features.modelhub.presentation.details

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import com.debanshu777.caraml.core.theme.AppTheme
import com.debanshu777.caraml.core.theme.ThemeMode
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.debanshu777.caraml.features.modelhub.presentation.search.saveModelHubEvidence
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import com.debanshu777.caraml.core.navigation.AppScreen
import com.debanshu777.caraml.core.navigation.NavigationTransitionDisplay
import com.debanshu777.caraml.core.theme.CaraMLTheme
import com.debanshu777.caraml.core.theme.ThemePreferences
import com.debanshu777.caraml.core.ui.motion.LocalAuroraMotionPolicy
import com.debanshu777.caraml.core.ui.motion.LocalModelSharedTransitionScope
import com.debanshu777.caraml.core.ui.motion.modelTransitionContent
import com.debanshu777.caraml.features.modelhub.presentation.components.ModelRow
import com.debanshu777.caraml.features.modelhub.presentation.components.discoveryModelRow
import com.debanshu777.caraml.features.modelhub.presentation.details.components.ModelDetailContent
import com.debanshu777.caraml.sdk.discovery.huggingface.model.ListModelsResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelDetailsTransitionUiTest {
    @Test
    fun identityRemainsMatchedWhenDetailsFinishLoadingAndOnReturn() = transitionJourney(false)

    @Test
    fun reducedMotionKeepsNavigationWithoutSharedSpatialAnimation() = transitionJourney(true)

    private fun transitionJourney(reduceMotion: Boolean) = runComposeUiTest {
        val model = ListModelsResponse.Model(
            id = "sample/Compact-Chat", author = "sample", downloads = 300, likes = 12,
            authorData = ListModelsResponse.Model.AuthorData(avatarUrl = "test:avatar"),
        )
        val preview = model.detailsPreview()
        val backStack = NavBackStack<NavKey>(AppScreen.Search)
        var loading by mutableStateOf(true)
        var sharedActive = false
        setContent {
            CaraMLTheme(ThemePreferences(themeMode = ThemeMode.LIGHT, reduceMotion = reduceMotion)) {
                Box(Modifier.requiredSize(390.dp, 780.dp).background(AppTheme.colors.background).testTag("visual-root")) {
                    NavigationTransitionDisplay(
                        modifier = Modifier.fillMaxSize(), backStack = backStack,
                        motionPolicy = LocalAuroraMotionPolicy.current, detailOffsetPx = 16,
                        entryDecorators = emptyList(),
                        entryProvider = { key -> NavEntry(key) {
                            sharedActive = LocalModelSharedTransitionScope.current?.isTransitionActive == true
                            if (key == AppScreen.Search) {
                                Box(Modifier.modelTransitionContent().padding(top = 180.dp, start = 16.dp, end = 16.dp)) {
                                    ModelRow(discoveryModelRow(model, null, 0),
                                        onOpenModel = { backStack.add(AppScreen.Details(preview.modelId, preview = preview)) },
                                        sharedTransitionKey = preview.transitionKey)
                                }
                            } else {
                                ModelDetailContent(
                                    model = preview.asDisplayModel(), avatarUrl = preview.avatarUrl,
                                    transitionKey = preview.transitionKey,
                                    ggufFiles = emptyList(), isDownloading = false,
                                    onDownloadClick = { _, _, _ -> error("Preview must not authorize downloads") },
                                    isDetailLoading = loading, contentPadding = PaddingValues(top = 96.dp, start = 16.dp, end = 16.dp),
                                    modifier = Modifier.modelTransitionContent(),
                                )
                            }
                        } },
                    )
                }
            }
        }
        mainClock.autoAdvance = false
        onNodeWithContentDescription("Open model sample/Compact-Chat").performClick()
        mainClock.advanceTimeBy(64)
        waitForIdle()
        runOnIdle { assertEquals(!reduceMotion, sharedActive, "Identity must match while entering") }
        if (!reduceMotion) saveModelHubEvidence("details-transition-enter.png")
        runOnIdle { loading = false }
        mainClock.advanceTimeBy(48)
        waitForIdle()
        if (!reduceMotion) {
            runOnIdle { assertTrue(sharedActive, "Loading completion must retain the shared identity") }
            saveModelHubEvidence("details-transition-loaded.png")
        }
        mainClock.advanceTimeBy(600)
        waitForIdle()
        onNodeWithText("sample/Compact-Chat").assertIsDisplayed()
        runOnIdle { assertFalse(sharedActive); backStack.removeLastOrNull() }
        mainClock.advanceTimeBy(64)
        waitForIdle()
        runOnIdle { assertEquals(!reduceMotion, sharedActive, "Return must share the same identity") }
        if (!reduceMotion) saveModelHubEvidence("details-transition-return.png")
        mainClock.advanceTimeBy(600)
        waitForIdle()
        onNodeWithContentDescription("Open model sample/Compact-Chat").assertIsDisplayed()
        assertEquals(null, preview.asDisplayModel().sha)
        assertTrue(preview.asDisplayModel().siblings.isNullOrEmpty())
    }
}
