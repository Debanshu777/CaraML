@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.debanshu777.caraml.features.modelhub.presentation.details

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
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
import com.debanshu777.caraml.core.drawer.AppDrawerShell
import com.debanshu777.caraml.core.drawer.LocalAppWindowWidth
import com.debanshu777.caraml.core.download.DownloadArtifactState
import com.debanshu777.caraml.core.download.DownloadBatchState
import com.debanshu777.caraml.core.navigation.AppScreen
import com.debanshu777.caraml.core.rating.SdArchitecture
import com.debanshu777.caraml.core.recommendation.DiffusionComponentDescriptor
import com.debanshu777.caraml.core.recommendation.DiffusionMode
import com.debanshu777.caraml.core.recommendation.DiffusionModelDescriptor
import com.debanshu777.caraml.core.recommendation.LlmModelDescriptor
import com.debanshu777.caraml.core.recommendation.ModelFileIdentity
import com.debanshu777.caraml.core.recommendation.QuantizationEvidence
import com.debanshu777.caraml.core.ui.motion.LocalAuroraMotionPolicy
import com.debanshu777.caraml.core.ui.motion.auroraMotionPolicy
import com.debanshu777.caraml.features.modelhub.domain.DescriptorState
import com.debanshu777.caraml.features.modelhub.domain.RecommendedModelUiState
import com.debanshu777.caraml.features.modelhub.presentation.details.components.ModelDetailContent
import com.debanshu777.caraml.features.modelhub.presentation.details.components.RepositoryHeading
import com.debanshu777.caraml.features.modelhub.presentation.details.components.formatHubTimestamp
import com.debanshu777.caraml.features.modelhub.presentation.details.components.GgufFileAction
import com.debanshu777.caraml.features.modelhub.presentation.details.components.GgufFileListItem
import com.debanshu777.caraml.features.modelhub.presentation.details.components.InstallBundleCard
import com.debanshu777.caraml.features.modelhub.presentation.details.components.splitRepositoryId
import com.debanshu777.caraml.features.modelhub.presentation.details.components.visibleModelTags
import com.debanshu777.caraml.features.modelhub.presentation.search.GgufFileUiState
import com.debanshu777.caraml.features.modelhub.presentation.search.InstallBundleUiState
import com.debanshu777.caraml.features.modelhub.presentation.search.SetupComponentUiState
import com.debanshu777.caraml.features.modelhub.presentation.search.DurableDownloadControlUiState
import com.debanshu777.huggingfacemanager.download.DownloadArtifactIdentity
import com.debanshu777.huggingfacemanager.download.DownloadMetadataDTO
import com.debanshu777.huggingfacemanager.model.ListModelsResponse
import com.debanshu777.huggingfacemanager.model.ModelDetailResponse
import com.debanshu777.huggingfacemanager.sdcpp.ComponentRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModelDetailsAuroraUiTest {

    @Test
    fun darkGgufRowsPaintReadableIdentityAndDownloadActionOnTheRouteCanvas() =
        runComposeUiTest {
            val surface = Color(0xFF07191D)
            val scheme = darkColorScheme(
                surface = surface,
                onSurface = Color(0xFFE5F2F2),
                onSurfaceVariant = Color(0xFFB8CACA),
            )
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    MaterialTheme(colorScheme = scheme) {
                        Box(
                            Modifier
                                .width(360.dp)
                                .background(surface),
                        ) {
                            GgufFileListItem(
                                filename = "Qwen3.8-27B-Q4_K_M.gguf",
                                sizeBytes = 902_823_936L,
                                isDownloaded = false,
                                progress = null,
                                isDownloading = false,
                                onDownloadClick = {},
                                modifier = Modifier.testTag("dark-gguf-row"),
                            )
                        }
                    }
                }
            }

            val identityContrast = onNodeWithText("Qwen3.8-27B-Q4_K_M.gguf")
                .captureToImage()
                .maximumContrastAgainst(surface)
            val actionContrast = onNodeWithContentDescription(
                "Download Qwen3.8-27B-Q4_K_M.gguf",
            ).captureToImage().maximumContrastAgainst(surface)

            assertTrue(identityContrast >= 4.5f, "File identity contrast was $identityContrast:1")
            assertTrue(actionContrast >= 3f, "Download icon contrast was $actionContrast:1")
        }

    @Test
    fun repositoryHeadingSeparatesOwnerFromNameWithoutRepeatingEither() {
        assertEquals(
            RepositoryHeading(
                owner = "GnLOLot",
                name = "MiniCPM5-1B-Claude-Opus-Fable5-Thinking-GGUF",
            ),
            splitRepositoryId("GnLOLot/MiniCPM5-1B-Claude-Opus-Fable5-Thinking-GGUF"),
        )
        assertEquals(RepositoryHeading(null, "ownerless-model"), splitRepositoryId("ownerless-model"))
    }

    @Test
    fun metadataFormatsDatesAndFiltersStructuredDuplicateTags() {
        assertEquals("3 Jul 2026", formatHubTimestamp("2026-07-03T09:24:41.000Z"))
        assertEquals("not-a-date", formatHubTimestamp("not-a-date"))
        assertEquals(
            listOf("gguf", "llama.cpp", "quantized", "coding"),
            visibleModelTags(
                tags = listOf(
                    "gguf",
                    "llama.cpp",
                    "quantized",
                    "coding",
                    "base_model:org/model",
                    "text-generation",
                ),
                pipelineTag = "text-generation",
            ),
        )
    }

    @Test
    fun compactDetailsUseHumanHierarchyAndDiscloseOnlyUsefulTags() = runComposeUiTest {
        val modelName = "MiniCPM5-1B-Claude-Opus-Fable5-Thinking-GGUF"
        val baseModel = "OtherOrg/MiniCPM5-1B-Claude-Opus-Fable5-Thinking"
        val createdAt = "2026-07-03T09:24:41.000Z"
        setContent {
            AtTwoHundredPercentFontScale {
                MaterialTheme {
                    Box(Modifier.width(420.dp).height(520.dp)) {
                        ModelDetailContent(
                            model = ModelDetailResponse(
                                modelId = "GnLOLot/$modelName",
                                author = "GnLOLot",
                                libraryName = "gguf",
                                pipelineTag = "text-generation",
                                createdAt = createdAt,
                                lastModified = "2026-07-13T14:56:34.000Z",
                                cardData = ModelDetailResponse.CardData(
                                    baseModel = listOf(baseModel),
                                    license = "apache-2.0",
                                ),
                                tags = listOf(
                                    "gguf",
                                    "llama.cpp",
                                    "quantized",
                                    "coding",
                                    "base_model:OtherOrg/model",
                                    "text-generation",
                                    "en",
                                    "zh",
                                ),
                            ),
                            ggufFiles = emptyList(),
                            isDownloading = false,
                            onDownloadClick = { _, _, _ -> },
                        )
                    }
                }
            }
        }

        onAllNodes(hasText("GnLOLot")).assertCountEquals(1)
        onNodeWithText(modelName).assertExists()
        onNodeWithText("GnLOLot/$modelName").assertDoesNotExist()
        onNodeWithText(createdAt).assertDoesNotExist()
        onNodeWithText("3 Jul 2026").assertDoesNotExist()

        onNodeWithText("Technical details").performScrollTo().performClick()
        onNodeWithText(baseModel).performScrollTo().assertIsDisplayed()
        val baseLabelBounds = onNodeWithText("Base model").fetchSemanticsNode().boundsInRoot
        val baseValueBounds = onNodeWithText(baseModel).fetchSemanticsNode().boundsInRoot
        assertTrue(baseValueBounds.top >= baseLabelBounds.bottom)
        assertTrue(kotlin.math.abs(baseValueBounds.left - baseLabelBounds.left) < 1f)

        onNodeWithContentDescription("Tag: base_model:OtherOrg/model").assertDoesNotExist()
        onNodeWithContentDescription("Tag: text-generation").assertDoesNotExist()
        onNodeWithText("zh").assertDoesNotExist()
        onNodeWithText("Show all").performScrollTo().assertIsDisplayed().performClick()
        onNodeWithText("3 Jul 2026").performScrollTo().assertIsDisplayed()
        onNodeWithContentDescription("Tag: llama.cpp").performScrollTo().assertIsDisplayed()
        onNodeWithText("zh").assertDoesNotExist()
        onNodeWithText("Show less").assertExists()
    }

    @Test
    fun detailsUseSupportingPaneOnlyAtExpandedWidth() {
        assertEquals(false, modelDetailsUseSupportingPane(839.dp))
        assertEquals(true, modelDetailsUseSupportingPane(840.dp))
    }

    @Test
    fun fileDownloadStillInvokesTheExactPath() = runComposeUiTest {
        var requested = ""
        setContent {
            MaterialTheme {
                GgufFileListItem(
                    filename = "weights/model-q4.gguf",
                    sizeBytes = 1_073_741_824L,
                    isDownloaded = false,
                    progress = null,
                    isDownloading = false,
                    onDownloadClick = { requested = "weights/model-q4.gguf" },
                )
            }
        }

        onNodeWithContentDescription("Download weights/model-q4.gguf")
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        runOnIdle { assertEquals("weights/model-q4.gguf", requested) }
    }

    @Test
    fun downloadSemanticsMergeOnlyWhileTheFileIsActivelyDownloading() = runComposeUiTest {
        var requested = ""
        setContent {
            MaterialTheme {
                Column {
                    GgufFileListItem(
                        filename = "idle.gguf",
                        sizeBytes = 1_024L,
                        isDownloaded = false,
                        progress = null,
                        isDownloading = false,
                        onDownloadClick = { requested = "idle.gguf" },
                    )
                    GgufFileListItem(
                        filename = "active.gguf",
                        sizeBytes = 2_048L,
                        isDownloaded = false,
                        progress = 50f,
                        isDownloading = true,
                        onDownloadClick = {},
                    )
                }
            }
        }

        val idleLabel = onNodeWithText("idle.gguf").fetchSemanticsNode()
        val idleAction = onNode(
            hasContentDescription("Download idle.gguf") and hasClickAction(),
        ).fetchSemanticsNode()
        assertTrue(
            idleLabel.boundsInRoot.right <= idleAction.boundsInRoot.left,
            "Idle file text semantics must remain separate from its Download action",
        )
        onNode(hasContentDescription("Download idle.gguf") and hasClickAction()).performClick()
        runOnIdle { assertEquals("idle.gguf", requested) }

        onAllNodes(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Downloading") and
                hasText("50%"),
        ).assertCountEquals(1)
    }

    @Test
    fun modelDetailsAssociatesDownloadStateAndCallbacksWithTheExactSelectedFile() =
        runComposeUiTest {
            val fixture = ggufDownloadFixture()
            var isDownloading by mutableStateOf(true)
            var activeDownloadArtifact by mutableStateOf<DownloadArtifactIdentity?>(fixture.activeArtifact)
            var requestedModelId = ""
            var requestedPath = ""
            var requestedMetadata: DownloadMetadataDTO? = null
            setContent {
                MaterialTheme {
                    Box(Modifier.width(620.dp).height(720.dp)) {
                        ModelDetailContent(
                            model = fixture.model,
                            ggufFiles = fixture.files,
                            isDownloading = isDownloading,
                            activeDownloadArtifact = activeDownloadArtifact,
                            onDownloadClick = { modelId, path, metadata ->
                                requestedModelId = modelId
                                requestedPath = path
                                requestedMetadata = metadata
                            },
                            recommendationState = fixture.recommendation,
                        )
                    }
                }
            }

            onAllNodes(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "Downloading",
                ),
            ).assertCountEquals(1)
            onNode(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "Downloading",
                ) and hasText("model-q4-00001-of-00002.gguf"),
            ).assertExists()

            val idleLabel = onNodeWithText("model-q4-00002-of-00002.gguf").fetchSemanticsNode()
            val idleAction = onNodeWithContentDescription(
                "Download weights/model-q4-00002-of-00002.gguf",
            )
                .assertIsNotEnabled()
                .fetchSemanticsNode()
            assertTrue(
                idleLabel.boundsInRoot.right <= idleAction.boundsInRoot.left,
                "An idle row must keep filename and exact-path Download action separate",
            )

            runOnIdle {
                isDownloading = false
                activeDownloadArtifact = null
            }

            onNodeWithContentDescription("Download weights/model-q4-00001-of-00002.gguf")
                .assertIsEnabled()
            onNodeWithContentDescription("Download weights/model-q4-00002-of-00002.gguf")
                .assertIsEnabled()
                .performClick()

            runOnIdle {
                assertEquals(fixture.model.modelId, requestedModelId)
                assertEquals(fixture.idleArtifact.relativePath, requestedPath)
                assertEquals(fixture.idleArtifact, requestedMetadata?.artifact)
            }
        }

    @Test
    fun needsInformationRemainsVisibleWithoutDisablingExactArtifactDownloads() =
        runComposeUiTest {
            val fixture = ggufDownloadFixture()
            var requestedModelId = ""
            var requestedPath = ""
            var requestedMetadata: DownloadMetadataDTO? = null
            setContent {
                MaterialTheme {
                    Box(Modifier.width(620.dp).height(720.dp)) {
                        ModelDetailContent(
                            model = fixture.model,
                            ggufFiles = fixture.files,
                            isDownloading = false,
                            onDownloadClick = { modelId, path, metadata ->
                                requestedModelId = modelId
                                requestedPath = path
                                requestedMetadata = metadata
                            },
                            recommendationState = null,
                        )
                    }
                }
            }

            onNodeWithText("Needs information").assertIsDisplayed()
            onNodeWithContentDescription(
                "Download weights/model-q4-00001-of-00002.gguf",
            )
                .assertIsEnabled()
                .performClick()

            runOnIdle {
                assertEquals(fixture.model.modelId, requestedModelId)
                assertEquals(fixture.activeArtifact.relativePath, requestedPath)
                assertEquals(fixture.activeArtifact, requestedMetadata?.artifact)
            }
        }

    @Test
    fun expandedLargeTextUsesOneScrollOwnerAcrossPrimaryAndSupportingColumns() =
        runComposeUiTest {
            val fixture = ggufDownloadFixture()
            var downloadClicks = 0
            setContent {
                AtTwoHundredPercentFontScale {
                    MaterialTheme {
                        Box(Modifier.width(900.dp).height(280.dp)) {
                            ModelDetailContent(
                                model = fixture.model.copy(
                                    tags = listOf(
                                        "text-generation",
                                        "transformers",
                                        "large-language-model",
                                        "on-device-inference",
                                    ),
                                ),
                                ggufFiles = fixture.files,
                                isDownloading = false,
                                onDownloadClick = { _, _, _ -> downloadClicks += 1 },
                                recommendationState = fixture.recommendation,
                                windowWidth = 900.dp,
                            )
                        }
                    }
                }
            }

            val scrollOwners = onAllNodes(
                SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange),
            ).fetchSemanticsNodes()
            assertEquals(1, scrollOwners.size)

            val overview = onNodeWithTag("detail-overview").fetchSemanticsNode().boundsInRoot
            val deviceFit = onNodeWithText("Device fit").fetchSemanticsNode().boundsInRoot
            assertTrue(deviceFit.left > overview.left, "Expanded details must remain two-column")

            onNodeWithContentDescription("Download weights/model-q4-00001-of-00002.gguf")
                .performScrollTo()
                .assertIsDisplayed()
                .performClick()
            runOnIdle { assertEquals(1, downloadClicks) }
        }

    @Test
    fun fileSectionProgressAndDownloadRemainReachableAtTwoHundredPercentFontScale() =
        runComposeUiTest {
            setContent {
                AtTwoHundredPercentFontScale {
                    MaterialTheme {
                        Box(Modifier.width(360.dp).height(180.dp)) {
                            Column(Modifier.verticalScroll(rememberScrollState())) {
                                Spacer(Modifier.height(240.dp))
                                GgufFileListItem(
                                    filename = "weights/model-q4.gguf",
                                    sizeBytes = 1_073_741_824L,
                                    isDownloaded = false,
                                    progress = 50f,
                                    isDownloading = false,
                                    onDownloadClick = {},
                                )
                            }
                        }
                    }
                }
            }

            onNodeWithText("model-q4.gguf")
                .performScrollTo()
                .assertIsDisplayed()
            onNodeWithText("50%")
                .performScrollTo()
                .assertIsDisplayed()
            onNodeWithContentDescription("Download weights/model-q4.gguf")
                .performScrollTo()
                .assertIsDisplayed()
        }

    @Test
    fun drawerShellWindowWidthMovesDetailsIntoSupportingPaneAtExpandedBoundary() = runComposeUiTest {
        var windowWidth by mutableStateOf(839.dp)
        setContent {
            MaterialTheme {
                val backStack = remember {
                    NavBackStack<NavKey>(AppScreen.Details("org/model"))
                }
                Box(Modifier.width(windowWidth).height(720.dp)) {
                    AppDrawerShell(
                        modifier = Modifier.fillMaxSize(),
                        backStack = backStack,
                    ) {
                        ModelDetailContent(
                            model = ModelDetailResponse(
                                modelId = "org/model",
                                author = "Model author",
                            ),
                            ggufFiles = emptyList(),
                            isDownloading = false,
                            onDownloadClick = { _, _, _ -> },
                            installBundleState = InstallBundleUiState(isSelfContained = true),
                            showInstallBundle = true,
                            windowWidth = LocalAppWindowWidth.current,
                        )
                    }
                }
            }
        }

        val compactOverview = onNodeWithTag("detail-overview")
            .fetchSemanticsNode().positionInRoot
        val compactInstall = onNodeWithText("Install summary")
            .fetchSemanticsNode().positionInRoot
        assertTrue(compactInstall.y > compactOverview.y)

        runOnIdle { windowWidth = 840.dp }

        val expandedOverview = onNodeWithTag("detail-overview")
            .fetchSemanticsNode().positionInRoot
        val expandedInstall = onNodeWithText("Install summary")
            .fetchSemanticsNode().positionInRoot
        assertTrue(expandedInstall.x > expandedOverview.x)
    }

    @Test
    fun detailsOutsideDrawerShellUseTheirOwnCompactConstraint() = runComposeUiTest {
        setContent {
            MaterialTheme {
                Box(Modifier.width(839.dp).height(720.dp)) {
                    ModelDetailContent(
                        model = ModelDetailResponse(modelId = "org/model"),
                        ggufFiles = emptyList(),
                        isDownloading = false,
                        onDownloadClick = { _, _, _ -> },
                        installBundleState = InstallBundleUiState(isSelfContained = true),
                        showInstallBundle = true,
                    )
                }
            }
        }

        val overview = onNodeWithTag("detail-overview").fetchSemanticsNode().positionInRoot
        val install = onNodeWithText("Install summary").fetchSemanticsNode().positionInRoot
        assertTrue(install.y > overview.y)
    }

    @Test
    fun compactLargeTextKeepsOneScrollOwnerAndClickableInstallAction() = runComposeUiTest {
        val fixture = realisticInstallFixture()
        var installClicks = 0
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                MaterialTheme {
                    Box(Modifier.width(420.dp).height(280.dp)) {
                        ModelDetailContent(
                            model = ModelDetailResponse(
                                modelId = fixture.recommendation.repositoryId,
                                author = "Model author",
                                tags = listOf("text-to-image", "safetensors", "large model"),
                            ),
                            ggufFiles = emptyList(),
                            isDownloading = false,
                            onDownloadClick = { _, _, _ -> },
                            installBundleState = fixture.installState,
                            onVariantSelected = {},
                            onSmartInstall = { installClicks++ },
                            showInstallBundle = true,
                            recommendationState = fixture.recommendation,
                        )
                    }
                }
            }
        }

        val scrollOwners = onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange),
        ).fetchSemanticsNodes()
        assertEquals(1, scrollOwners.size)

        onNode(hasText("Install", substring = true) and hasClickAction())
            .assertIsDisplayed()
            .performClick()
        runOnIdle { assertEquals(1, installClicks) }
    }

    @Test
    fun durableTransferLocksOtherRowsWhileExactTaskControlsRemainAvailable() =
        runComposeUiTest {
            val fixture = ggufDownloadFixture()
            val activeControl = durableControl(
                batchId = "active-batch",
                artifactState = DownloadArtifactState.RUNNING,
                batchState = DownloadBatchState.RUNNING,
            )
            var pausedBatch = ""
            var cancelledBatch = ""
            setContent {
                MaterialTheme {
                    Box(Modifier.width(900.dp).height(720.dp)) {
                        ModelDetailContent(
                            model = fixture.model,
                            ggufFiles = fixture.files.map { file ->
                                if (file.artifact == fixture.activeArtifact) {
                                    file.copy(durableControl = activeControl)
                                } else {
                                    file
                                }
                            },
                            isDownloading = true,
                            activeDownloadArtifact = fixture.activeArtifact,
                            onDownloadClick = { _, _, _ -> },
                            recommendationState = fixture.recommendation,
                            windowWidth = 900.dp,
                            onPauseDownload = { batchId, _ -> pausedBatch = batchId },
                            onCancelDownload = { batchId, _ -> cancelledBatch = batchId },
                        )
                    }
                }
            }

            onNodeWithContentDescription(
                "Pause download ${fixture.activeArtifact.relativePath}",
            ).performScrollTo().assertIsEnabled().performClick()
            onNodeWithContentDescription(
                "Cancel download ${fixture.activeArtifact.relativePath}",
            ).performScrollTo().assertIsEnabled().performClick()
            onNodeWithContentDescription(
                "Download ${fixture.idleArtifact.relativePath}",
            ).performScrollTo().assertIsNotEnabled()

            runOnIdle {
                assertEquals("active-batch", pausedBatch)
                assertEquals("active-batch", cancelledBatch)
            }
        }

    @Test
    fun durableFileActionsNameTheirArtifactAndRetryableTaskCanStillBeCancelled() =
        runComposeUiTest {
            val callbacks = mutableListOf<String>()
            setContent {
                MaterialTheme {
                    Column {
                        GgufFileAction(
                            filename = "running.gguf",
                            isDownloaded = false,
                            isDownloading = true,
                            downloadEnabled = true,
                            interactionLocked = false,
                            durableState = DownloadArtifactState.RUNNING,
                            onDownloadClick = {},
                            onPause = { callbacks += "pause" },
                            onResume = {},
                            onCancel = { callbacks += "cancel-running" },
                            onRetry = {},
                        )
                        GgufFileAction(
                            filename = "paused.gguf",
                            isDownloaded = false,
                            isDownloading = false,
                            downloadEnabled = true,
                            interactionLocked = false,
                            durableState = DownloadArtifactState.PAUSED,
                            onDownloadClick = {},
                            onPause = {},
                            onResume = { callbacks += "resume" },
                            onCancel = { callbacks += "cancel-paused" },
                            onRetry = {},
                        )
                        GgufFileAction(
                            filename = "failed.gguf",
                            isDownloaded = false,
                            isDownloading = false,
                            downloadEnabled = true,
                            interactionLocked = false,
                            durableState = DownloadArtifactState.FAILED_RETRYABLE,
                            onDownloadClick = {},
                            onPause = {},
                            onResume = {},
                            onCancel = { callbacks += "cancel-failed" },
                            onRetry = { callbacks += "retry" },
                        )
                    }
                }
            }

            onNodeWithContentDescription("Pause download running.gguf").performClick()
            onNodeWithContentDescription("Resume download paused.gguf").performClick()
            onNodeWithContentDescription("Retry download failed.gguf").performClick()
            onNodeWithContentDescription("Cancel download failed.gguf").performClick()

            runOnIdle {
                assertEquals(listOf("pause", "resume", "retry", "cancel-failed"), callbacks)
            }
        }

    @Test
    fun retryableInstallOffersIndependentRetryAndCancelActions() = runComposeUiTest {
        val fixture = realisticInstallFixture()
        val artifact = requireNotNull(fixture.installState.variants.first().artifact)
        var retries = 0
        var cancellations = 0
        setContent {
            MaterialTheme {
                InstallBundleCard(
                    modelId = artifact.repositoryId,
                    state = fixture.installState,
                    familyLabel = "Model family",
                    modelDescription = null,
                    onVariantSelected = {},
                    onInstall = {},
                    durableControl = durableControl(
                        batchId = "retryable-batch",
                        artifactState = DownloadArtifactState.FAILED_RETRYABLE,
                        batchState = DownloadBatchState.FAILED_RETRYABLE,
                    ),
                    onRetry = { retries++ },
                    onCancel = { cancellations++ },
                )
            }
        }

        onNodeWithText("Retry").performClick()
        onNodeWithText("Cancel download").performClick()
        runOnIdle {
            assertEquals(1, retries)
            assertEquals(1, cancellations)
        }
    }

    @Test
    fun installControlsUseOnlyTheProjectedSelectedBatch() =
        runComposeUiTest {
            val fixture = realisticInstallFixture()
            var resumedBatch = ""
            var cancelledBatch = ""
            setContent {
                MaterialTheme {
                    Box(Modifier.width(420.dp).height(640.dp)) {
                        ModelDetailContent(
                            model = ModelDetailResponse(modelId = fixture.recommendation.repositoryId),
                            ggufFiles = emptyList(),
                            isDownloading = true,
                            onDownloadClick = { _, _, _ -> },
                            installBundleState = fixture.installState.copy(
                                isInstalling = true,
                                durableControl = durableControl(
                                    batchId = "selected-paused",
                                    artifactState = DownloadArtifactState.PAUSED,
                                    batchState = DownloadBatchState.PAUSED,
                                ),
                            ),
                            onVariantSelected = {},
                            onSmartInstall = {},
                            showInstallBundle = true,
                            recommendationState = fixture.recommendation,
                            onResumeDownload = { batchId, _ -> resumedBatch = batchId },
                            onCancelDownload = { batchId, _ -> cancelledBatch = batchId },
                        )
                    }
                }
            }

            onNodeWithText("Resume").assertIsDisplayed().performClick()
            onNodeWithText("Cancel download").assertIsDisplayed().performClick()
            onNodeWithText("Pause").assertDoesNotExist()
            runOnIdle {
                assertEquals("selected-paused", resumedBatch)
                assertEquals("selected-paused", cancelledBatch)
            }
        }

    @Test
    fun determinateFileProgressAnimatesOnlyTowardTheReportedValue() = runComposeUiTest {
        var progress by mutableStateOf(0f)
        mainClock.autoAdvance = false
        setContent {
            MaterialTheme {
                GgufFileListItem(
                    filename = "model-q4.gguf",
                    sizeBytes = 1_073_741_824L,
                    isDownloaded = false,
                    progress = progress,
                    isDownloading = true,
                    onDownloadClick = {},
                )
            }
        }
        mainClock.advanceTimeByFrame()

        runOnIdle { progress = 100f }
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeBy(90)

        val rangeInfo = onNode(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo),
        ).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)
        requireNotNull(rangeInfo)
        assertTrue(rangeInfo.current > 0f && rangeInfo.current < 1f)

        mainClock.advanceTimeBy(100)
        val completed = onNode(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo),
        ).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)
        assertEquals(1f, requireNotNull(completed).current, absoluteTolerance = 0.001f)
    }

    @Test
    fun reducedMotionAppliesReportedFileProgressImmediately() = runComposeUiTest {
        var progress by mutableStateOf(0f)
        mainClock.autoAdvance = false
        setContent {
            CompositionLocalProvider(
                LocalAuroraMotionPolicy provides auroraMotionPolicy(durationScale = 0f),
            ) {
                MaterialTheme {
                    GgufFileListItem(
                        filename = "model-q4.gguf",
                        sizeBytes = 1_073_741_824L,
                        isDownloaded = false,
                        progress = progress,
                        isDownloading = true,
                        onDownloadClick = {},
                    )
                }
            }
        }
        mainClock.advanceTimeByFrame()

        runOnIdle { progress = 100f }
        mainClock.advanceTimeByFrame()

        val rangeInfo = onNode(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo),
        ).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)
        assertEquals(1f, requireNotNull(rangeInfo).current, absoluteTolerance = 0.001f)
    }

    @Test
    fun determinateInstallProgressAnimatesTowardItsReportedFraction() = runComposeUiTest {
        var state by mutableStateOf(
            InstallBundleUiState(
                isInstalling = true,
                isSelfContained = true,
                overallProgress = 0f,
            ),
        )
        mainClock.autoAdvance = false
        setContent {
            MaterialTheme {
                InstallBundleCard(
                    modelId = "org/model",
                    state = state,
                    familyLabel = "Model family",
                    modelDescription = "Model description",
                    onVariantSelected = {},
                    onInstall = {},
                )
            }
        }
        mainClock.advanceTimeByFrame()

        runOnIdle { state = state.copy(overallProgress = 1f) }
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeBy(90)

        val reportedRange = onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo),
        ).fetchSemanticsNodes()
            .mapNotNull { it.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo) }
            .single { it != androidx.compose.ui.semantics.ProgressBarRangeInfo.Indeterminate }
        assertTrue(reportedRange.current > 0f && reportedRange.current < 1f)

        mainClock.advanceTimeBy(100)
        val completed = onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo),
        ).fetchSemanticsNodes()
            .mapNotNull { it.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo) }
            .single { it != androidx.compose.ui.semantics.ProgressBarRangeInfo.Indeterminate }
        assertEquals(1f, completed.current, absoluteTolerance = 0.001f)
    }

    @Test
    fun indeterminateInstallUsesOneMaterialProgressIndicator() = runComposeUiTest {
        setContent {
            MaterialTheme {
                InstallBundleCard(
                    modelId = "org/model",
                    state = InstallBundleUiState(
                        isInstalling = true,
                        isSelfContained = true,
                        overallProgress = null,
                    ),
                    familyLabel = "Model family",
                    modelDescription = null,
                    onVariantSelected = {},
                    onInstall = {},
                )
            }
        }

        val indeterminateIndicators = onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo),
        ).fetchSemanticsNodes()
            .mapNotNull { it.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo) }
            .count { it == androidx.compose.ui.semantics.ProgressBarRangeInfo.Indeterminate }
        assertEquals(1, indeterminateIndicators)
    }
}

private data class InstallFixture(
    val installState: InstallBundleUiState,
    val recommendation: RecommendedModelUiState,
)

private data class GgufDownloadFixture(
    val model: ModelDetailResponse,
    val files: List<GgufFileUiState>,
    val activeArtifact: DownloadArtifactIdentity,
    val idleArtifact: DownloadArtifactIdentity,
    val recommendation: RecommendedModelUiState,
)

private fun durableControl(
    batchId: String,
    artifactState: DownloadArtifactState,
    batchState: DownloadBatchState,
): DurableDownloadControlUiState = DurableDownloadControlUiState(
        batchId = batchId,
        artifactId = "$batchId-artifact",
        batchState = batchState,
        artifactState = artifactState,
    )

private fun ggufDownloadFixture(): GgufDownloadFixture {
    val repositoryId = "org/gguf-model"
    val revision = "c".repeat(40)
    fun artifact(path: String, oid: Char, expectedBytes: Long): DownloadArtifactIdentity =
        requireNotNull(
            DownloadArtifactIdentity.create(
                repositoryId = repositoryId,
                immutableRevision = revision,
                relativePath = path,
                remoteObjectId = "sha256:${oid.toString().repeat(64)}",
                expectedBytes = expectedBytes,
            ),
        )

    fun file(artifact: DownloadArtifactIdentity): ModelFileIdentity = ModelFileIdentity(
        repositoryId = artifact.repositoryId,
        revision = artifact.immutableRevision,
        path = artifact.relativePath,
        sizeBytes = artifact.expectedBytes,
        gitOid = null,
        lfsOid = requireNotNull(artifact.remoteObjectId).removePrefix("sha256:"),
        xetHash = null,
        evidence = emptyList(),
    )

    fun descriptor(vararg artifacts: DownloadArtifactIdentity): LlmModelDescriptor = LlmModelDescriptor(
        repositoryId = repositoryId,
        revision = revision,
        files = artifacts.map(::file),
        architecture = "llama",
        quantization = QuantizationEvidence.Known("Q4_K_M"),
        parameterCount = 7_000_000_000L,
        contextLimit = 4_096,
        transformerShape = null,
        ggufVersion = 3,
        requiredEngineFeatures = emptyList(),
        evidence = emptyList(),
    )

    fun recommendation(
        descriptor: LlmModelDescriptor,
        variant: String,
    ): RecommendedModelUiState = RecommendedModelUiState(
        sourceModel = ListModelsResponse.Model(id = repositoryId),
        repositoryId = repositoryId,
        descriptorState = DescriptorState.ASSESSED,
        objectiveAssessment = null,
        personalizedResult = null,
        selectedVariantName = variant,
        stableModelId = repositoryId,
        sourceIndex = 0,
        selectedDescriptor = descriptor,
    )

    val activeArtifact = artifact("weights/model-q4-00001-of-00002.gguf", 'd', 4_294_967_296L)
    val idleArtifact = artifact("weights/model-q4-00002-of-00002.gguf", 'e', 4_294_967_296L)
    return GgufDownloadFixture(
        model = ModelDetailResponse(
            modelId = repositoryId,
            author = "Model author",
            downloads = 12_345,
            likes = 678,
        ),
        files = listOf(
            GgufFileUiState(
                path = activeArtifact.relativePath,
                filename = "model-q4-00001-of-00002.gguf",
                sizeBytes = activeArtifact.expectedBytes,
                isDownloaded = false,
                progress = null,
                artifact = activeArtifact,
            ),
            GgufFileUiState(
                path = idleArtifact.relativePath,
                filename = "model-q4-00002-of-00002.gguf",
                sizeBytes = idleArtifact.expectedBytes,
                isDownloaded = false,
                progress = null,
                artifact = idleArtifact,
            ),
        ),
        activeArtifact = activeArtifact,
        idleArtifact = idleArtifact,
        recommendation = recommendation(
            descriptor(activeArtifact, idleArtifact),
            "Q4_K_M (2 shards)",
        ),
    )
}

private fun realisticInstallFixture(): InstallFixture {
    val repositoryId = "org/model"
    val revision = "a".repeat(40)
    val objectId = "b".repeat(64)
    val primaryPath = "unet/model-q4.safetensors"
    val primaryArtifact = requireNotNull(
        DownloadArtifactIdentity.create(
            repositoryId = repositoryId,
            immutableRevision = revision,
            relativePath = primaryPath,
            remoteObjectId = "sha256:$objectId",
            expectedBytes = 4_294_967_296L,
        ),
    )
    val primaryFile = ModelFileIdentity(
        repositoryId = repositoryId,
        revision = revision,
        path = primaryPath,
        sizeBytes = primaryArtifact.expectedBytes,
        gitOid = null,
        lfsOid = objectId,
        xetHash = null,
        evidence = emptyList(),
    )
    val descriptor = DiffusionModelDescriptor(
        repositoryId = repositoryId,
        revision = revision,
        components = listOf(
            DiffusionComponentDescriptor(
                file = primaryFile,
                role = null,
                required = true,
                isPrimary = true,
            ),
        ),
        mode = DiffusionMode.IMAGE,
        family = "Flux",
        architecture = SdArchitecture.FLUX,
        quantizationDistribution = setOf("Q4"),
        requiredComponentsPresent = true,
        requiredEngineFeatures = emptySet(),
        evidence = emptyList(),
    )
    val variants = listOf(
        GgufFileUiState(
            path = primaryPath,
            filename = "model-q4.safetensors",
            sizeBytes = primaryArtifact.expectedBytes,
            isDownloaded = false,
            progress = null,
            artifact = primaryArtifact,
        ),
        GgufFileUiState(
            path = "unet/model-q8.safetensors",
            filename = "model-q8.safetensors",
            sizeBytes = 8_589_934_592L,
            isDownloaded = false,
            progress = null,
        ),
    )
    val installState = InstallBundleUiState(
        variants = variants,
        selectedVariantPath = primaryPath,
        components = listOf(
            setupComponent(ComponentRole.VAE, "ae.safetensors", "335 MB"),
            setupComponent(ComponentRole.CLIP_L, "clip_l.safetensors", "246 MB"),
            setupComponent(ComponentRole.T5XXL, "t5xxl_fp16.safetensors", "9.8 GB"),
        ),
        totalNewDownloadBytes = 14_495_514_624L,
        isSelfContained = false,
    )
    return InstallFixture(
        installState = installState,
        recommendation = RecommendedModelUiState(
            sourceModel = ListModelsResponse.Model(id = repositoryId),
            repositoryId = repositoryId,
            descriptorState = DescriptorState.ASSESSED,
            objectiveAssessment = null,
            personalizedResult = null,
            selectedVariantName = "Q4",
            stableModelId = repositoryId,
            sourceIndex = 0,
            selectedDescriptor = descriptor,
        ),
    )
}

private fun setupComponent(
    role: ComponentRole,
    filePath: String,
    sizeHint: String,
): SetupComponentUiState = SetupComponentUiState(
    role = role,
    repoId = "org/components",
    filePath = filePath,
    sizeHint = sizeHint,
    isDownloaded = false,
    progress = null,
    required = true,
)

private fun ImageBitmap.maximumContrastAgainst(background: Color): Float {
    val pixels = toPixelMap()
    var maximum = 1f
    for (y in 0 until pixels.height) {
        for (x in 0 until pixels.width) {
            val foreground = pixels[x, y]
            val lighter = maxOf(foreground.luminance(), background.luminance())
            val darker = minOf(foreground.luminance(), background.luminance())
            maximum = maxOf(maximum, (lighter + 0.05f) / (darker + 0.05f))
        }
    }
    return maximum
}

@Composable
private fun AtTwoHundredPercentFontScale(content: @Composable () -> Unit) {
    val current = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(current.density, fontScale = 2f),
        content = content,
    )
}
