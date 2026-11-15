@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.debanshu777.caraml.features.modelhub.presentation.details

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.debanshu777.caraml.core.theme.AppTheme
import com.debanshu777.caraml.core.theme.CaraMLTheme
import com.debanshu777.caraml.core.theme.ThemeMode
import com.debanshu777.caraml.core.theme.ThemePreferences
import com.debanshu777.caraml.core.rating.SdArchitecture
import com.debanshu777.caraml.core.recommendation.DiffusionComponentDescriptor
import com.debanshu777.caraml.core.recommendation.AssessmentReason
import com.debanshu777.caraml.core.recommendation.BrowseFitEstimate
import com.debanshu777.caraml.core.recommendation.BrowseResourceFit
import com.debanshu777.caraml.core.recommendation.Compatibility
import com.debanshu777.caraml.core.recommendation.DiffusionMode
import com.debanshu777.caraml.core.recommendation.DiffusionModelDescriptor
import com.debanshu777.caraml.core.recommendation.LlmModelDescriptor
import com.debanshu777.caraml.core.recommendation.ModelFileIdentity
import com.debanshu777.caraml.core.recommendation.PerformanceEstimate
import com.debanshu777.caraml.core.recommendation.QuantizationEvidence
import com.debanshu777.caraml.features.modelhub.domain.DescriptorState
import com.debanshu777.caraml.features.modelhub.domain.BrowseVariantUiState
import com.debanshu777.caraml.features.modelhub.domain.RecommendedModelUiState
import com.debanshu777.caraml.features.modelhub.presentation.details.components.ModelDetailContent
import com.debanshu777.caraml.features.modelhub.presentation.details.components.ModelDetailsDownloadableListItem
import com.debanshu777.caraml.features.modelhub.presentation.search.GgufFileUiState
import com.debanshu777.caraml.features.modelhub.presentation.search.InstallBundleUiState
import com.debanshu777.caraml.core.download.transfer.DownloadArtifactIdentity
import com.debanshu777.caraml.core.download.transfer.DownloadMetadataDTO
import com.debanshu777.caraml.sdk.discovery.huggingface.model.ListModelsResponse
import com.debanshu777.caraml.sdk.discovery.huggingface.model.ModelDetailResponse
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModelDetailsWorkbenchUiTest {

    @Test
    fun productionDetailTitleKeepsTheBrandRoleAcrossWidths() =
        runComposeUiTest {
            var windowWidth by mutableStateOf(839.dp)
            setContent {
                MaterialTheme {
                    Box(Modifier.width(900.dp).height(560.dp)) {
                        ModelDetailContent(
                            model = ModelDetailResponse(modelId = "org/focal-artifact"),
                            ggufFiles = emptyList(),
                            isDownloading = false,
                            onDownloadClick = { _, _, _ -> },
                            windowWidth = windowWidth,
                        )
                    }
                }
            }

            textStyleFor("org/focal-artifact").let { style ->
                assertEquals(18.sp, style.fontSize)
                assertEquals(24.sp, style.lineHeight)
                assertEquals(FontWeight.SemiBold, style.fontWeight)
            }

            runOnIdle { windowWidth = 840.dp }
            waitForIdle()

            textStyleFor("org/focal-artifact").let { style ->
                assertEquals(18.sp, style.fontSize)
                assertEquals(24.sp, style.lineHeight)
                assertEquals(FontWeight.SemiBold, style.fontWeight)
            }
        }

    @Test
    fun compactDetailTitleWrapsWithoutClippingAtTwoHundredPercentText() = runComposeUiTest {
        val repositoryId =
            "org/a-very-long-artifact-name-that-needs-multiple-lines-at-large-text"
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 2f)) {
                MaterialTheme {
                    Box(Modifier.width(360.dp).height(640.dp)) {
                        ModelDetailContent(
                            model = ModelDetailResponse(modelId = repositoryId),
                            ggufFiles = emptyList(),
                            isDownloading = false,
                            onDownloadClick = { _, _, _ -> },
                            windowWidth = 360.dp,
                        )
                    }
                }
            }
        }

        val results = mutableListOf<TextLayoutResult>()
        onNodeWithText(repositoryId, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
                assertTrue(action(results))
            }
        val result = results.single()
        assertTrue(result.lineCount > 1, "Large detail titles should wrap")
        assertTrue(!result.didOverflowWidth, "Wrapped detail title must not overflow width")
        assertTrue(!result.hasVisualOverflow, "The full title is readable without an expansion control")
        onNodeWithText("Show full name").assertDoesNotExist()
        results.clear()
        onNodeWithText(repositoryId, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
                assertTrue(action(results))
            }
        assertTrue(!results.single().didOverflowHeight, "Expanded title must remain fully readable")
    }

    @Test
    fun compactDetailsKeepsVariantChoicesAboveTheFold() = runComposeUiTest {
        val fixture = workbenchGgufFixture()
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                MaterialTheme {
                    Box(Modifier.width(360.dp).height(740.dp)) {
                        ModelDetailContent(
                            model = workbenchMetadataModel(fixture.repositoryId),
                            ggufFiles = fixture.files,
                            isDownloading = false,
                            onDownloadClick = { _, _, _ -> },
                            recommendationState = fixture.recommendation,
                        )
                    }
                }
            }
        }
        onNodeWithTag("detail-overview").assertIsDisplayed()
        onAllNodes(hasText("Overview")).assertCountEquals(0)
        onNodeWithTag("detail-artifact:${fixture.files.first().path}").assertIsDisplayed()
        val overview = onNodeWithTag("detail-overview").fetchSemanticsNode().boundsInRoot
        val files = onNodeWithTag("detail-files").fetchSemanticsNode().boundsInRoot
        val tabs = onNodeWithTag("detail-tabs").fetchSemanticsNode().boundsInRoot
        assertTrue(tabs.top >= overview.bottom && files.top >= tabs.bottom)
        onNodeWithText("Technical details").performClick()
        onNodeWithTag("detail-metadata").scrollDetailsIntoView().assertIsDisplayed()
    }

    @Test
    fun compactHierarchyPutsDecisionContentAboveTheTwoTabs() =
        runComposeUiTest {
            val fixture = workbenchGgufFixture()
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    MaterialTheme {
                        Box(Modifier.width(412.dp).height(915.dp)) {
                            ModelDetailContent(
                                model = workbenchMetadataModel(fixture.repositoryId),
                                ggufFiles = fixture.files,
                                isDownloading = false,
                                onDownloadClick = { _, _, _ -> },
                                recommendationState = fixture.recommendation,
                            )
                        }
                    }
                }
            }

            onNodeWithText("Pipeline").assertDoesNotExist()
            onNodeWithText("Device fit").assertDoesNotExist()
            onNodeWithContentDescription("See device info").assertDoesNotExist()
            val deviceFit = onNodeWithTag("detail-fit").fetchSemanticsNode().boundsInRoot
            val overview = onNodeWithTag("detail-overview").fetchSemanticsNode().boundsInRoot
            assertTrue(deviceFit.top >= overview.top && deviceFit.bottom <= overview.bottom)
            val files = onNodeWithText("Variants").fetchSemanticsNode().boundsInRoot
            val technical = onNodeWithText("Technical details")
                .fetchSemanticsNode().boundsInRoot
            assertTrue(deviceFit.top < files.top, "Device decision should precede artifacts")
            assertTrue(abs(files.top - technical.top) < 1f, "Both tabs belong to the same row")

            onNodeWithText("Technical details").performClick()
            onNodeWithText("Pipeline").assertExists()
            onNodeWithText("License").assertExists()
        }

    @Test
    fun compactGgufRowGivesFilenameThenSizeAndActionTheirOwnBands() = runComposeUiTest {
        val filename = "Qwen3.8-27B-Uncensored-HauhauCS-Aggressive-FastMTP-32K.gguf"
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                MaterialTheme {
                    Box(Modifier.width(360.dp)) {
                        ModelDetailsDownloadableListItem(
                            filename = filename,
                            sizeBytes = 902_823_936L,
                            isDownloaded = false,
                            progress = null,
                            isDownloading = false,
                            onDownloadClick = {},
                            modifier = Modifier.testTag("compact-gguf-row"),
                        )
                    }
                }
            }
        }

        val layouts = mutableListOf<TextLayoutResult>()
        val filenameNode = onNodeWithText(filename, useUnmergedTree = true)
        filenameNode.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
            assertTrue(action(layouts))
        }
        assertTrue(!layouts.single().hasVisualOverflow, "GGUF filename must remain readable")
        onAllNodesWithText("Show full filename").assertCountEquals(0)
        val filenameBounds = filenameNode.fetchSemanticsNode().boundsInRoot
        val sizeBounds = onNodeWithText("861 MB", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        assertTrue(sizeBounds.top >= filenameBounds.bottom, "Size belongs below the filename")
    }

    @Test
    fun exactGgufFilenameRemainsFullyVisibleInDetails() = runComposeUiTest {
        val filename = "an-extremely-long-quantized-model-filename-with-exact-version-and-shard-00001-of-00002.gguf"
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 2f)) {
                MaterialTheme {
                    Box(Modifier.width(360.dp)) {
                        ModelDetailsDownloadableListItem(
                            filename = filename,
                            sizeBytes = 902_823_936L,
                            isDownloaded = false,
                            progress = null,
                            isDownloading = false,
                            onDownloadClick = {},
                        )
                    }
                }
            }
        }

        val layouts = mutableListOf<TextLayoutResult>()
        onNodeWithText(filename, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
                assertTrue(action(layouts))
            }
        assertTrue(!layouts.single().hasVisualOverflow, "Expanded exact filename must remain readable")
    }

    @Test
    fun longRepositoryNameKeepsTheOwnerInOneAnnotatedTitle() = runComposeUiTest {
        val owner = "research-collective"
        val name = "MiniCPM5-1B-Claude-Opus-Fable5-Very-Long-Thinking-GGUF"
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                MaterialTheme {
                    Box(Modifier.width(320.dp).height(420.dp)) {
                        ModelDetailContent(
                            model = ModelDetailResponse(
                                modelId = "$owner/$name",
                                author = owner,
                                pipelineTag = "text-generation",
                                libraryName = "gguf",
                            ),
                            ggufFiles = emptyList(),
                            isDownloading = false,
                            onDownloadClick = { _, _, _ -> },
                        )
                    }
                }
            }
        }

        onNodeWithTag("detail-overview").assertExists()
        onAllNodes(hasText("$owner/$name")).assertCountEquals(1)
        onAllNodes(hasText(owner)).assertCountEquals(0)
        val layouts = mutableListOf<TextLayoutResult>()
        onNodeWithTag("detail-title").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.single().lineCount > 1, "The combined title should wrap naturally")
        assertEquals(1, layouts.single().layoutInput.text.getLinkAnnotations(0, owner.length + name.length + 1).size)

    }

    @Test
    fun technicalTabShowsAllAvailableFacts() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                MaterialTheme {
                    Box(Modifier.width(420.dp).height(700.dp)) {
                        ModelDetailContent(
                            model = workbenchMetadataModel("org/metadata-model"),
                            ggufFiles = emptyList(),
                            isDownloading = false,
                            onDownloadClick = { _, _, _ -> },
                        )
                    }
                }
            }
        }

        onNodeWithText("Technical details").scrollDetailsIntoView().performClick()
        onNodeWithTag("detail-metadata").assertExists()
        onNodeWithText("Library").assertExists()
        onNodeWithText("Pipeline").assertExists()
        onNodeWithText("License").assertExists()
        onNodeWithText("Base model").assertExists()
        onNodeWithText("Model type").assertExists()
        onNodeWithText("3 Jul 2026").assertExists()
        onNodeWithText("Tags").assertExists()
        onNodeWithText("Show all").assertDoesNotExist()
        onNodeWithText("Variants").scrollDetailsIntoView().performClick()
        onNodeWithText("Model type").assertDoesNotExist()
    }

    @Test
    fun blankMetadataDoesNotCreateEmptyProperties() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                MaterialTheme {
                    Box(Modifier.width(420.dp).height(700.dp)) {
                        ModelDetailContent(
                            model = ModelDetailResponse(
                                modelId = "org/blank-metadata",
                                libraryName = "   ",
                                pipelineTag = "\n",
                                createdAt = "2026-07-03T09:24:41.000Z",
                                lastModified = "2026-07-13T14:56:34.000Z",
                                config = ModelDetailResponse.Config(
                                    modelType = "llama",
                                    architectures = listOf("", "LlamaForCausalLM"),
                                ),
                                cardData = ModelDetailResponse.CardData(
                                    baseModel = listOf(" "),
                                    license = "\t",
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

        onNodeWithText("Technical details").scrollDetailsIntoView().performClick()
        onNodeWithTag("detail-metadata").assertExists()
        onNodeWithText("Library").assertDoesNotExist()
        onNodeWithText("Pipeline").assertDoesNotExist()
        onNodeWithText("License").assertDoesNotExist()
        onNodeWithText("Base model").assertDoesNotExist()
        onNodeWithText("Model type").assertExists()
        onNodeWithText("Architectures").assertExists()
        onNodeWithText("Created").assertExists()
        onNodeWithText("Last modified").assertExists()
        onNodeWithText("Show all").assertDoesNotExist()
    }

    @Test
    fun evaluatedArtifactKeepsExplicitStateAndExactDownloadAction() = runComposeUiTest {
        val fixture = workbenchGgufFixture()
        val scheme = workbenchColorScheme()
        var requestedModelId = ""
        var requestedPath = ""
        var requestedMetadata: DownloadMetadataDTO? = null
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                MaterialTheme(colorScheme = scheme) {
                    Box(
                        Modifier
                            .width(420.dp)
                            .height(760.dp)
                            .background(scheme.surface),
                    ) {
                        ModelDetailContent(
                            model = ModelDetailResponse(modelId = fixture.repositoryId),
                            ggufFiles = fixture.files,
                            isDownloading = false,
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
        }

        onNodeWithTag("detail-files").scrollDetailsIntoView().assertIsDisplayed()
        onAllNodes(
            SemanticsMatcher.expectValue(SemanticsProperties.Selected, true) and
                SemanticsMatcher.expectValue(SemanticsProperties.Role, androidx.compose.ui.semantics.Role.RadioButton),
        ).assertCountEquals(0)
        onAllNodes(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                "Evaluated variant",
            ),
        ).assertCountEquals(1)

        val selectedRow = onNodeWithTag("detail-artifact:${fixture.primaryArtifact.relativePath}")
            .assert(
                SemanticsMatcher.expectValue(SemanticsProperties.Selected, false),
            )
        selectedRow.assertIsDisplayed()
        onNodeWithText("Evaluated variant").assertIsDisplayed()

        onNodeWithContentDescription("Download ${fixture.secondaryArtifact.relativePath}")
            .performClick()
        runOnIdle {
            assertEquals(fixture.repositoryId, requestedModelId)
            assertEquals(fixture.secondaryArtifact.relativePath, requestedPath)
            assertEquals(fixture.secondaryArtifact, requestedMetadata?.artifact)
        }
    }

    @Test
    fun compactShortLandscapeKeepsBottomActionReachableAtTwoHundredPercentText() =
        runComposeUiTest {
            val fixture = workbenchInstallFixture()
            var installClicks = 0
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 2f)) {
                    MaterialTheme {
                        Box(Modifier.width(420.dp).height(280.dp)) {
                            ModelDetailContent(
                                model = ModelDetailResponse(modelId = fixture.repositoryId),
                                ggufFiles = emptyList(),
                                isDownloading = false,
                                onDownloadClick = { _, _, _ -> },
                                installBundleState = fixture.installState,
                                onVariantSelected = {},
                                onSmartInstall = { installClicks += 1 },
                                showInstallBundle = true,
                                recommendationState = fixture.recommendation,
                            )
                        }
                    }
                }
            }

            onAllNodes(
                SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange),
            ).assertCountEquals(1)
            onNodeWithTag("detail-action").assertIsDisplayed()
            onNode(hasText("Install", substring = true) and hasClickAction())
                .assertIsDisplayed()
                .performClick()
            runOnIdle { assertEquals(1, installClicks) }
        }

    @Test
    fun compactLlmKeepsOneExactArtifactActionReachableAtTwoHundredPercentText() =
        runComposeUiTest {
            val fixture = workbenchGgufFixture()
            var isDownloading by androidx.compose.runtime.mutableStateOf(false)
            var activeArtifact by androidx.compose.runtime.mutableStateOf<DownloadArtifactIdentity?>(null)
            var requestedModelId = ""
            var requestedPath = ""
            var requestedMetadata: DownloadMetadataDTO? = null
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 2f)) {
                    MaterialTheme {
                        Box(Modifier.width(420.dp).height(280.dp)) {
                            ModelDetailContent(
                                model = ModelDetailResponse(modelId = fixture.repositoryId),
                                ggufFiles = fixture.files.map { file ->
                                    if (file.artifact == activeArtifact) file.copy(progress = 42f) else file
                                },
                                isDownloading = isDownloading,
                                activeDownloadArtifact = activeArtifact,
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
            }

            onAllNodes(
                SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange),
            ).assertCountEquals(1)
            onNodeWithTag("detail-action").assertDoesNotExist()
            onAllNodes(
                hasContentDescription("Download ${fixture.primaryArtifact.relativePath}") and
                    hasClickAction(),
            ).assertCountEquals(1)
            onNodeWithContentDescription("Download ${fixture.primaryArtifact.relativePath}")
                .scrollDetailsIntoView()
                .assertIsDisplayed()
                .assertIsEnabled()
                .performClick()
            runOnIdle {
                assertEquals(fixture.repositoryId, requestedModelId)
                assertEquals(fixture.primaryArtifact.relativePath, requestedPath)
                assertEquals(fixture.primaryArtifact, requestedMetadata?.artifact)
                isDownloading = true
                activeArtifact = fixture.secondaryArtifact
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
                ) and hasText("model-q4-00002-of-00002.gguf") and hasText("42%"),
            ).assertExists()
            onNodeWithContentDescription("Download ${fixture.primaryArtifact.relativePath}")
                .assertIsNotEnabled()
            onNodeWithContentDescription("Download ${fixture.secondaryArtifact.relativePath}")
                .assertIsNotEnabled()
        }

    @Test
    fun groupedShardIconSubmitsEveryExactArtifactWithoutFooter() = runComposeUiTest {
        val fixture = workbenchGgufFixture()
        val descriptor = fixture.recommendation.selectedDescriptor as LlmModelDescriptor
        val variant = BrowseVariantUiState(
            stableIdentity = "exact-shards",
            displayName = "Q4 two parts",
            filePaths = descriptor.files.map { it.path },
            fileIdentities = descriptor.files,
            estimate = BrowseFitEstimate(
                compatibility = Compatibility.Unknown(emptyList()),
                memoryFit = BrowseResourceFit.UNKNOWN,
                storageFit = BrowseResourceFit.UNKNOWN,
                memory = null,
                storageBytes = null,
                downloadBytes = descriptor.files.sumOf { it.sizeBytes },
                performance = PerformanceEstimate.Unknown(AssessmentReason.SPEED_NOT_VERIFIED),
                resourceSnapshotFresh = false,
                resourceTimestampEpochMs = 0L,
                reasons = emptyList(),
                evidence = emptyList(),
            ),
        )
        var submitted: List<DownloadMetadataDTO>? = null
        var memoryFit by mutableStateOf(BrowseResourceFit.UNKNOWN)
        var selectedPath by mutableStateOf<String?>(null)
        var theme by mutableStateOf(ThemeMode.LIGHT)
        setContent {
            CaraMLTheme(ThemePreferences(themeMode = theme, reduceMotion = true)) {
                Box(Modifier.width(420.dp).height(800.dp).testTag("details-viewport")) {
                  androidx.compose.material3.Surface {
                   com.debanshu777.caraml.core.ui.layout.ResponsiveContentPane(
                       kind = com.debanshu777.caraml.core.ui.layout.AppContentKind.Details,
                   ) {
                    ModelDetailContent(
                        model = ModelDetailResponse(modelId = fixture.repositoryId),
                        ggufFiles = fixture.files,
                        isDownloading = false,
                        onDownloadClick = { _, _, _ -> error("Shard icon must submit the exact group") },
                        onDownloadGroupClick = { _, metadata -> submitted = metadata },
                        installBundleState = InstallBundleUiState(selectedVariantPath = selectedPath),
                        onVariantSelected = { selectedPath = it },
                        recommendationState = fixture.recommendation.copy(
                            descriptorState = DescriptorState.NEEDS_INFORMATION,
                            selectedDescriptor = null,
                            stableModelId = variant.stableIdentity,
                            browseVariants = listOf(variant.copy(estimate = variant.estimate.copy(
                                memoryFit = memoryFit,
                                resourceSnapshotFresh = memoryFit != BrowseResourceFit.UNKNOWN,
                            ))),
                        ),
                    )
                   }
                  }
                }
            }
        }
        onNodeWithTag("detail-action").assertDoesNotExist()
        onNodeWithText("Selected artifact").assertDoesNotExist()
        onNodeWithText("Technical details").scrollDetailsIntoView().performClick()
        onNodeWithText("Fit unknown · support unverified")
            .scrollDetailsIntoView().assertIsDisplayed()
        runOnIdle { memoryFit = BrowseResourceFit.TIGHT_FIT }
        onNodeWithText("Tight fit").scrollDetailsIntoView().assertIsDisplayed()
        onNodeWithText("Memory estimate · compatibility unverified")
            .scrollDetailsIntoView().assertIsDisplayed()
        onNodeWithText("Fit unknown · support unverified")
            .assertDoesNotExist()
        onNodeWithText("Variants").scrollDetailsIntoView().performClick()
        onNodeWithText("Suggested · support unverified · Part 1 of 2").assertExists()
        onNodeWithText("Suggested · support unverified · Part 2 of 2").assertExists()
        runOnIdle {
            selectedPath = fixture.files.first().path
            assertEquals(null, submitted, "Displaying a variant must not download it")
        }
        onNodeWithTag("detail-artifact:${fixture.files.first().path}").scrollDetailsIntoView()
        val row = onNodeWithTag("detail-artifact:${fixture.files.first().path}").fetchSemanticsNode().boundsInRoot
        val viewport = onNodeWithTag("details-viewport").fetchSemanticsNode().boundsInRoot
        assertTrue(row.left > viewport.left, "Selected row must retain the leading page gutter")
        val section = onNodeWithTag("detail-section-divider").fetchSemanticsNode().positionInRoot
        assertTrue(abs(row.right - (section.x + onNodeWithTag("detail-section-divider").fetchSemanticsNode().size.width)) < 1f, "Row divider extends to the content edge")
        val filename = onNodeWithText(fixture.files.first().filename).fetchSemanticsNode().boundsInRoot
        val divider = onNodeWithTag("detail-divider:${fixture.files.first().path}").fetchSemanticsNode().boundsInRoot
        assertTrue(abs(divider.left - filename.left) < 1f, "Divider must start at the filename column")
        assertTrue(abs(divider.right - row.right) < 1f, "Divider must reach the row edge")
        onAllNodes(hasContentDescription("Download all 2 parts")).assertCountEquals(2)
        onAllNodes(hasContentDescription("Download all 2 parts"))[0].scrollDetailsIntoView().performClick()
        runOnIdle {
            assertEquals(
                listOf(fixture.primaryArtifact, fixture.secondaryArtifact),
                submitted?.map { it.artifact },
            )
        }
    }

    @Test
    fun expandedDetailsHasOneScrollOwnerAndFullWidthTabs() = runComposeUiTest {
        val fixture = workbenchInstallFixture()
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                MaterialTheme {
                    Box(Modifier.width(900.dp).height(500.dp)) {
                        ModelDetailContent(
                            model = workbenchMetadataModel(fixture.repositoryId),
                            ggufFiles = emptyList(),
                            isDownloading = false,
                            onDownloadClick = { _, _, _ -> },
                            installBundleState = fixture.installState,
                            onVariantSelected = {},
                            onSmartInstall = {},
                            showInstallBundle = true,
                            recommendationState = fixture.recommendation,
                            windowWidth = 900.dp,
                        )
                    }
                }
            }
        }

        onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange),
        ).assertCountEquals(1)
        onNodeWithText("Technical details").scrollDetailsIntoView().performClick()
        val overview = onNodeWithTag("detail-overview").fetchSemanticsNode().boundsInRoot
        val support = onNodeWithTag("detail-support").fetchSemanticsNode().boundsInRoot
        assertTrue(abs(support.left - overview.left) < 1f)
        assertTrue(abs(support.width - overview.width) < 1f, "Technical tab uses the content width")

    }
}

private fun ComposeUiTest.textStyleFor(text: String) =
    mutableListOf<TextLayoutResult>().also { results ->
        onNodeWithText(text, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
                assertTrue(action(results), "Expected a text layout result for $text")
            }
        assertEquals(1, results.size)
    }.single().layoutInput.style

private data class WorkbenchGgufFixture(
    val repositoryId: String,
    val files: List<GgufFileUiState>,
    val primaryArtifact: DownloadArtifactIdentity,
    val secondaryArtifact: DownloadArtifactIdentity,
    val recommendation: RecommendedModelUiState,
)

private data class WorkbenchInstallFixture(
    val repositoryId: String,
    val installState: InstallBundleUiState,
    val recommendation: RecommendedModelUiState,
)

private fun workbenchGgufFixture(): WorkbenchGgufFixture {
    val repositoryId = "org/decision-model"
    val revision = "c".repeat(40)
    fun artifact(path: String, oid: Char, bytes: Long) = requireNotNull(
        DownloadArtifactIdentity.create(
            repositoryId = repositoryId,
            immutableRevision = revision,
            relativePath = path,
            remoteObjectId = "sha256:${oid.toString().repeat(64)}",
            expectedBytes = bytes,
        ),
    )
    fun file(artifact: DownloadArtifactIdentity) = ModelFileIdentity(
        repositoryId = artifact.repositoryId,
        revision = artifact.immutableRevision,
        path = artifact.relativePath,
        sizeBytes = artifact.expectedBytes,
        gitOid = null,
        lfsOid = requireNotNull(artifact.remoteObjectId).removePrefix("sha256:"),
        xetHash = null,
        evidence = emptyList(),
    )

    val primary = artifact("weights/model-q4-00001-of-00002.gguf", 'd', 4_294_967_296L)
    val secondary = artifact("weights/model-q4-00002-of-00002.gguf", 'e', 4_294_967_296L)
    val descriptor = LlmModelDescriptor(
        repositoryId = repositoryId,
        revision = revision,
        files = listOf(file(primary), file(secondary)),
        architecture = "llama",
        quantization = QuantizationEvidence.Known("Q4_K_M"),
        parameterCount = 7_000_000_000L,
        contextLimit = 4_096,
        transformerShape = null,
        ggufVersion = 3,
        requiredEngineFeatures = emptyList(),
        evidence = emptyList(),
    )
    return WorkbenchGgufFixture(
        repositoryId = repositoryId,
        files = listOf(primary, secondary).map { artifact ->
            GgufFileUiState(
                path = artifact.relativePath,
                filename = artifact.relativePath.substringAfterLast('/'),
                sizeBytes = artifact.expectedBytes,
                isDownloaded = false,
                progress = null,
                artifact = artifact,
            )
        },
        primaryArtifact = primary,
        secondaryArtifact = secondary,
        recommendation = workbenchRecommendation(repositoryId, descriptor, "Q4_K_M · 2 shards"),
    )
}

private fun workbenchInstallFixture(): WorkbenchInstallFixture {
    val repositoryId = "org/diffusion-model"
    val revision = "a".repeat(40)
    val objectId = "b".repeat(64)
    val path = "unet/model-q4.safetensors"
    val artifact = requireNotNull(
        DownloadArtifactIdentity.create(
            repositoryId = repositoryId,
            immutableRevision = revision,
            relativePath = path,
            remoteObjectId = "sha256:$objectId",
            expectedBytes = 4_294_967_296L,
        ),
    )
    val file = ModelFileIdentity(
        repositoryId = repositoryId,
        revision = revision,
        path = path,
        sizeBytes = artifact.expectedBytes,
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
                file = file,
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
    return WorkbenchInstallFixture(
        repositoryId = repositoryId,
        installState = InstallBundleUiState(
            variants = listOf(
                GgufFileUiState(
                    path = path,
                    filename = "model-q4.safetensors",
                    sizeBytes = artifact.expectedBytes,
                    isDownloaded = false,
                    progress = null,
                    artifact = artifact,
                ),
            ),
            selectedVariantPath = path,
            totalNewDownloadBytes = artifact.expectedBytes,
            isSelfContained = true,
        ),
        recommendation = workbenchRecommendation(repositoryId, descriptor, "Q4"),
    )
}

private fun workbenchRecommendation(
    repositoryId: String,
    descriptor: com.debanshu777.caraml.core.recommendation.ModelDescriptor,
    variant: String,
) = RecommendedModelUiState(
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

private fun workbenchMetadataModel(repositoryId: String) = ModelDetailResponse(
    modelId = repositoryId,
    author = repositoryId.substringBefore('/'),
    libraryName = "gguf",
    pipelineTag = "text-generation",
    downloads = 12_345,
    likes = 678,
    createdAt = "2026-07-03T09:24:41.000Z",
    lastModified = "2026-07-13T14:56:34.000Z",
    config = ModelDetailResponse.Config(
        modelType = "llama",
        architectures = listOf("LlamaForCausalLM"),
    ),
    cardData = ModelDetailResponse.CardData(
        baseModel = listOf("base/model"),
        license = "apache-2.0",
    ),
    tags = listOf(
        "gguf",
        "text-generation",
        "on-device",
        "quantized",
        "base_model:base/model",
    ),
)

private fun workbenchColorScheme() = darkColorScheme(
    surface = Color(0xFF101217),
    surfaceContainer = Color(0xFF1E222B),
    surfaceContainerHigh = Color(0xFF2B303A),
    onSurface = Color(0xFFF2F3F7),
    onSurfaceVariant = Color(0xFFC6CBD6),
    primary = Color(0xFF4F83FF),
    secondary = Color(0xFF00D59C),
    tertiary = Color(0xFFFF5AA5),
    outlineVariant = Color(0xFF7A8190),
)

private fun colorsNear(first: Color, second: Color, tolerance: Float = 0.03f): Boolean =
    first.colorDistance(second) <= tolerance

private fun Color.colorDistance(other: Color): Float =
    abs(red - other.red) + abs(green - other.green) + abs(blue - other.blue)
