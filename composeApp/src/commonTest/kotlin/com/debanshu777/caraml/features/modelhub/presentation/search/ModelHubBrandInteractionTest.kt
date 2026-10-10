@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.debanshu777.caraml.features.modelhub.presentation.search

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasImeAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.input.ImeAction
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.debanshu777.caraml.core.drawer.DrawerController
import com.debanshu777.caraml.core.drawer.LocalDrawerController
import com.debanshu777.caraml.core.drawer.LocalNavigationMenuAction
import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import com.debanshu777.caraml.features.modelhub.presentation.downloaded.components.DownloadedListItem
import com.debanshu777.caraml.features.modelhub.presentation.details.ModelDetailStatus
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelHubContextStrip
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelHubDeviceInfo
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelHubInlineFilterPanel
import com.debanshu777.huggingfacemanager.model.ModelSort
import com.debanshu777.huggingfacemanager.model.ParameterRange
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelHubStateKind
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelHubStateView
import com.debanshu777.caraml.features.modelhub.presentation.search.components.SearchListItem
import com.debanshu777.caraml.features.modelhub.presentation.search.components.SearchBar
import com.debanshu777.huggingfacemanager.model.ListModelsResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelHubBrandInteractionTest {
    @Test
    fun deviceInfoUsesBackAndRefreshWithoutASecondNavigationMenu() = runComposeUiTest {
        val controller = DrawerController()
        val input = DirectNavigationEventInput()
        var deviceBacks = 0
        var fallbackBacks = 0
        val dispatcher = NavigationEventDispatcher { fallbackBacks++ }.also { it.addInput(input) }
        val owner = object : NavigationEventDispatcherOwner {
            override val navigationEventDispatcher = dispatcher
        }
        setContent {
            CompositionLocalProvider(
                LocalNavigationEventDispatcherOwner provides owner,
                LocalDrawerController provides controller,
                LocalNavigationMenuAction provides controller::toggle,
            ) {
                MaterialTheme {
                    NavigationBackHandler(
                        state = rememberNavigationEventState(NavigationEventInfo.None),
                        isBackEnabled = controller.isOpen,
                        onBackCompleted = controller::close,
                    )
                    Box(Modifier.requiredSize(390.dp, 740.dp)) {
                        ModelHubDeviceInfo(StorageInfoUiState(hasSampled = true), null,
                            onBack = { deviceBacks++ }, onRefresh = {}, onOpenProfile = null)
                    }
                }
            }
        }
        onNodeWithContentDescription("Open navigation menu").assertDoesNotExist()
        onNodeWithContentDescription("Refresh device info").assertIsDisplayed()
        runOnIdle { assertFalse(controller.isOpen); assertEquals(0, deviceBacks) }
        runOnIdle { input.backCompleted() }
        runOnIdle { assertEquals(1, deviceBacks); assertEquals(0, fallbackBacks) }
        onNodeWithContentDescription("Back to models").performClick()
        runOnIdle { assertEquals(2, deviceBacks) }
    }

    @Test
    fun shortViewportKeepsSearchFocusAndErrorRecoveryAtDoubleTextScale() = runComposeUiTest {
        var viewportHeight by mutableStateOf(740.dp)
        var query by mutableStateOf("")
        var retries = 0
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                MaterialTheme {
                    Box(Modifier.requiredSize(320.dp, viewportHeight)) {
                        ModelHubScreenLayout(
                            selectedTabIndex = 0, onTabSelected = {},
                            sharedContext = { ModelHubContextStrip(StorageInfoUiState(), null, null, onOpenDevice = {}) },
                            discoverContent = {
                                ModelHubTabLayout(
                                    command = { SearchBar(query, { query = it }, {}) },
                                    context = {}, toolbar = {}, summary = {},
                                    results = { item(key = "error") {
                                        ModelHubStateView(ModelHubStateKind.Error, "Local models are still available.",
                                            actionLabel = "Retry", onAction = { retries++ })
                                    } },
                                )
                            }, libraryContent = {},
                        )
                    }
                }
            }
        }
        val input = onNode(hasImeAction(ImeAction.Search))
        input.performClick().performTextReplacement("tiny")
        input.assertIsFocused()
        runOnIdle { viewportHeight = 220.dp }
        input.assertIsFocused().performTextInput(" brain")
        runOnIdle { assertEquals("tiny brain", query) }
        onNodeWithTag("model-primary-results").performScrollToKey("error")
        onNodeWithText("Retry").performScrollTo().assertIsDisplayed().performClick()
        runOnIdle { assertEquals(1, retries) }
    }

    @Test
    fun hubErrorProvidesIndependentRetryAndLibraryRecovery() = runComposeUiTest {
        var retries = 0
        var libraryOpens = 0
        setContent {
            MaterialTheme {
                Box(Modifier.width(320.dp)) {
                    ModelHubStateView(
                        kind = ModelHubStateKind.Error,
                        message = "Your local models are still available.",
                        actionLabel = "Retry", onAction = { retries++ },
                        secondaryActionLabel = "Open library", onSecondaryAction = { libraryOpens++ },
                    )
                }
            }
        }
        onNodeWithText("Retry").performClick()
        runOnIdle { assertEquals(1, retries); assertEquals(0, libraryOpens) }
        onNodeWithText("Open library").performClick()
        runOnIdle { assertEquals(1, retries); assertEquals(1, libraryOpens) }
    }

    @Test
    fun unavailableDeviceCanBeResampledAtDoubleTextScale() = runComposeUiTest {
        var refreshes = 0
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                MaterialTheme {
                    Box(Modifier.requiredSize(320.dp, 600.dp)) {
                        ModelHubDeviceInfo(StorageInfoUiState(hasSampled = true), null,
                            onBack = {}, onRefresh = { refreshes++ }, onOpenProfile = null)
                    }
                }
            }
        }
        onNodeWithText("Check again").performScrollTo().assertIsDisplayed().performClick()
        onNodeWithText("0 B").assertDoesNotExist()
        runOnIdle { assertEquals(1, refreshes) }
    }

    @Test
    fun inlineTaskChoiceIsStagedUntilApplyAndResetRestoresDefaults() = runComposeUiTest {
        var applied = emptyList<ModelHubBrowseMode>()
        setContent {
            MaterialTheme {
                Box(Modifier.width(320.dp)) {
                    ModelHubInlineFilterPanel(ModelHubBrowseMode.LanguageModels,
                        ModelOrdering.Server(ModelSort.TRENDING), ParameterRange.ZERO, ParameterRange.SIX_B,
                    ) { mode, _, _, _ -> applied = applied + mode }
                }
            }
        }
        onNodeWithContentDescription("Task").performClick()
        onNodeWithText("Image").performClick()
        runOnIdle { assertEquals(emptyList(), applied) }
        onNodeWithText("Apply filters").performClick()
        runOnIdle { assertEquals(listOf(ModelHubBrowseMode.DiffusionImage), applied) }
        onNodeWithText("Reset").performClick()
        runOnIdle { assertEquals(listOf(ModelHubBrowseMode.DiffusionImage, ModelHubBrowseMode.LanguageModels), applied) }
    }

    @Test
    fun detailRetryRemainsReachableInShortLandscapeAtDoubleTextScale() = runComposeUiTest {
        var retries = 0
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                MaterialTheme {
                    Box(Modifier.requiredSize(320.dp, 180.dp)) {
                        ModelDetailStatus(
                            kind = ModelHubStateKind.Error,
                            message = "Could not load model details. Please try again.",
                            onRetry = { retries++ },
                        )
                    }
                }
            }
        }
        onNodeWithText("Retry").performScrollTo().assertIsDisplayed().performClick()
        runOnIdle { assertEquals(1, retries) }
    }

    @Test
    fun unknownFitRemainsExplicitAndBrowseCardOpensModel() = runComposeUiTest {
        var opens = 0
        setContent {
            MaterialTheme {
                Box(Modifier.width(320.dp)) {
                    SearchListItem(
                        model = ListModelsResponse.Model(id = "sample/Tiny-brain", author = "sample"),
                        onClick = { opens++ },
                    )
                }
            }
        }
        onNodeWithText("Needs information", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithContentDescription("Open model sample/Tiny-brain").performClick()
        runOnIdle { assertEquals(1, opens) }
    }

    @Test
    fun localRepairActionDoesNotAlsoOpenTheModel() = runComposeUiTest {
        var opens = 0
        var repairs = 0
        setContent {
            MaterialTheme {
                Box(Modifier.width(320.dp)) {
                    DownloadedListItem(
                        model = LocalModelEntity(
                            id = 1,
                            modelId = "sample/Image-model",
                            filename = "model.safetensors",
                            localPath = "/preview/model.safetensors",
                            sizeBytes = 2_000_000_000L,
                            downloadedAt = 0L,
                            author = "sample",
                            libraryName = null,
                            pipelineTag = "text-to-image",
                            componentStatus = LocalModelEntity.STATUS_PARTIAL,
                        ),
                        selectionMode = false,
                        isSelected = false,
                        onOpenModel = { opens++ },
                        onToggleSelect = {},
                        onLongPress = {},
                        onFixComponents = { repairs++ },
                    )
                }
            }
        }
        onNodeWithText("Needs setup", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("Finish setup").performClick()
        runOnIdle {
            assertEquals(1, repairs)
            assertEquals(0, opens)
        }
    }

    @Test
    fun errorPanelRetainsActionableRetry() = runComposeUiTest {
        var retries = 0
        setContent {
            MaterialTheme {
                Box(Modifier.width(320.dp)) {
                    ModelHubStateView(
                        kind = ModelHubStateKind.Error,
                        message = "The model hub could not be reached.",
                        actionLabel = "Retry",
                        onAction = { retries++ },
                    )
                }
            }
        }
        onNodeWithText("The model hub could not be reached.").assertIsDisplayed()
        onNodeWithText("Retry").performClick()
        runOnIdle { assertEquals(1, retries) }
    }

    @Test
    fun unavailableDeviceDataIsShownAsUnknown() = runComposeUiTest {
        setContent {
            MaterialTheme {
                ModelHubContextStrip(StorageInfoUiState(), null, null)
            }
        }
        onNodeWithText("Device details aren't available yet. Fit estimates may need more information.")
            .assertIsDisplayed()
        onNodeWithText("0 B").assertDoesNotExist()
    }
}
