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
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
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
import com.debanshu777.caraml.core.rating.SdArchitecture
import com.debanshu777.caraml.core.recommendation.DiffusionComponentDescriptor
import com.debanshu777.caraml.core.recommendation.DiffusionMode
import com.debanshu777.caraml.core.recommendation.DiffusionModelDescriptor
import com.debanshu777.caraml.core.recommendation.LlmModelDescriptor
import com.debanshu777.caraml.core.recommendation.ModelFileIdentity
import com.debanshu777.caraml.core.recommendation.QuantizationEvidence
import com.debanshu777.caraml.features.modelhub.domain.DescriptorState
import com.debanshu777.caraml.features.modelhub.domain.RecommendedModelUiState
import com.debanshu777.caraml.features.modelhub.presentation.details.components.ModelDetailContent
import com.debanshu777.caraml.features.modelhub.presentation.search.GgufFileUiState
import com.debanshu777.caraml.features.modelhub.presentation.search.InstallBundleUiState
import com.debanshu777.huggingfacemanager.download.DownloadArtifactIdentity
import com.debanshu777.huggingfacemanager.download.DownloadMetadataDTO
import com.debanshu777.huggingfacemanager.model.ListModelsResponse
import com.debanshu777.huggingfacemanager.model.ModelDetailResponse
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModelDetailsWorkbenchUiTest {

    @Test
    fun productionDetailTitleUsesCompactAndExpandedSemanticRolesAtTheExactBreakpoint() =
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

            textStyleFor("focal-artifact").let { style ->
                assertEquals(24.sp, style.fontSize)
                assertEquals(30.sp, style.lineHeight)
                assertEquals(FontWeight.SemiBold, style.fontWeight)
            }

            runOnIdle { windowWidth = 840.dp }
            waitForIdle()

            textStyleFor("focal-artifact").let { style ->
                assertEquals(32.sp, style.fontSize)
                assertEquals(38.sp, style.lineHeight)
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
        onNodeWithText(repositoryId.substringAfter('/'), useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
                assertTrue(action(results))
            }
        val result = results.single()
        assertTrue(result.lineCount > 1, "Large detail titles should wrap")
        assertTrue(!result.didOverflowWidth, "Wrapped detail title must not overflow width")
        assertTrue(!result.didOverflowHeight, "Wrapped detail title must not be clipped")
    }

    @Test
    fun compactDetailsUsesSectionsAndDividersInsteadOfStackedOutlinedCards() =
        runComposeUiTest {
            val fixture = workbenchGgufFixture()
            val scheme = workbenchColorScheme()
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    MaterialTheme(colorScheme = scheme) {
                        Box(
                            Modifier
                                .width(360.dp)
                                .height(900.dp)
                                .background(scheme.surface),
                        ) {
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

            val overviewNode = onNodeWithTag("detail-overview").assertIsDisplayed()
            val overviewPixels = overviewNode
                .captureToImage()
                .toPixelMap()
            val overviewLeading = overviewPixels[8, 8]
            val overviewTrailing = overviewPixels[overviewPixels.width - 9, 8]
            assertTrue(
                overviewLeading.colorDistance(scheme.surface) in 0.01f..0.22f,
                "Overview should own a restrained contextual field, got $overviewLeading",
            )
            assertTrue(
                overviewLeading.colorDistance(overviewTrailing) >= 0.01f,
                "Overview field should retain two distinct seed-derived endpoints",
            )

            val metadataNode = onNodeWithTag("detail-metadata")
                .performScrollTo()
                .assertIsDisplayed()
            val metadataPixels = metadataNode
                .captureToImage()
                .toPixelMap()
            val metadataEdge = metadataPixels[metadataPixels.width - 4, 4]
            assertTrue(
                metadataEdge.colorDistance(scheme.surface) <= 0.025f,
                "Metadata should sit on the page canvas instead of an independent card",
            )
            onNodeWithTag("detail-files").performScrollTo().assertIsDisplayed()
        }

    @Test
    fun longRepositoryNameWrapsBelowOwnerWithoutRepeatingOwner() = runComposeUiTest {
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
        onAllNodes(hasText(owner)).assertCountEquals(1)
        onNodeWithText("$owner/$name").assertDoesNotExist()
        val ownerBounds = onNodeWithText(owner).fetchSemanticsNode().boundsInRoot
        val nameBounds = onNodeWithText(name).fetchSemanticsNode().boundsInRoot
        assertTrue(nameBounds.top >= ownerBounds.bottom)
        assertTrue(abs(nameBounds.left - ownerBounds.left) < 1f)
        assertTrue(nameBounds.height > 40f, "The long repository name should wrap naturally")
    }

    @Test
    fun metadataShowsFourPrimaryRowsBeforeSaveableShowAll() = runComposeUiTest {
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

        onNodeWithTag("detail-metadata").performScrollTo().assertIsDisplayed()
        onNodeWithText("Library").assertExists()
        onNodeWithText("Pipeline").assertExists()
        onNodeWithText("License").assertExists()
        onNodeWithText("Base model").assertExists()
        onNodeWithText("Model type").assertDoesNotExist()
        onNodeWithText("3 Jul 2026").assertDoesNotExist()
        onNodeWithContentDescription("Tag: on-device").assertDoesNotExist()

        onNodeWithText("Show all").performClick()
        onNodeWithText("Model type").assertExists()
        onNodeWithText("3 Jul 2026").assertExists()
        onNodeWithContentDescription("Tag: on-device").assertExists()

        onNodeWithText("Show less").performScrollTo().assertIsDisplayed().performClick()
        onNodeWithText("Model type").assertDoesNotExist()
    }

    @Test
    fun blankMetadataDoesNotConsumeTheFourPrimaryRows() = runComposeUiTest {
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

        onNodeWithTag("detail-metadata").performScrollTo().assertIsDisplayed()
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
    fun selectedArtifactUsesOneSignalRailAndExactDownloadAction() = runComposeUiTest {
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

        onNodeWithTag("detail-files").performScrollTo().assertIsDisplayed()
        onAllNodes(
            SemanticsMatcher.expectValue(SemanticsProperties.Selected, true),
        ).assertCountEquals(1)
        onAllNodes(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                "Recommended artifact",
            ),
        ).assertCountEquals(1)

        val selectedRow = onNodeWithTag("detail-artifact:${fixture.primaryArtifact.relativePath}")
            .assert(
                SemanticsMatcher.expectValue(SemanticsProperties.Selected, true),
            )
        val pixels = selectedRow.captureToImage().toPixelMap()
        val middleY = pixels.height / 2
        assertTrue(colorsNear(pixels[0, middleY], scheme.primary))
        assertTrue(colorsNear(pixels[1, middleY], scheme.primary))
        assertTrue(colorsNear(pixels[2, middleY], scheme.primary))
        assertTrue(
            !colorsNear(pixels[3, middleY], scheme.primary),
            "Selected artifact must use one 3dp signal rail",
        )

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
            onNodeWithTag("detail-action").assertIsDisplayed()
            onAllNodes(
                hasContentDescription("Download ${fixture.primaryArtifact.relativePath}") and
                    hasClickAction(),
            ).assertCountEquals(1)
            onNodeWithContentDescription("Download ${fixture.primaryArtifact.relativePath}")
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
    fun expandedDetailsHasOneScrollOwnerAndA320To360DpSupportPane() = runComposeUiTest {
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
        val overview = onNodeWithTag("detail-overview").fetchSemanticsNode().boundsInRoot
        val support = onNodeWithTag("detail-support").fetchSemanticsNode().boundsInRoot
        assertTrue(support.left > overview.left)
        assertTrue(
            support.width in 320f..360f,
            "Support pane must remain 320–360dp; it was ${support.width}dp",
        )
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
    tertiary = Color(0xFFFF5AA5),
    outlineVariant = Color(0xFF7A8190),
)

private fun colorsNear(first: Color, second: Color, tolerance: Float = 0.03f): Boolean =
    first.colorDistance(second) <= tolerance

private fun Color.colorDistance(other: Color): Float =
    abs(red - other.red) + abs(green - other.green) + abs(blue - other.blue)
