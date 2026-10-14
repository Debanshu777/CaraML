@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.debanshu777.caraml.features.modelhub.presentation.search

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelHubInlineFilterPanel
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelHubInlineFilters
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelHubDeviceInfo
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelDownloadQueueEntry
import com.debanshu777.caraml.core.download.DownloadArtifactRequest
import com.debanshu777.caraml.core.download.DownloadArtifactSnapshot
import com.debanshu777.caraml.core.download.DownloadBatchSnapshot
import com.debanshu777.caraml.core.download.DownloadUserIntent
import com.debanshu777.caraml.core.recommendation.storage.EncodedModelEvidence
import com.debanshu777.caraml.core.recommendation.storage.InstalledEvidenceState
import com.debanshu777.huggingfacemanager.download.DownloadMetadataDTO
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import com.debanshu777.caraml.features.modelhub.presentation.details.components.ModelDetailsDevicePreview
import com.debanshu777.caraml.features.modelhub.presentation.details.components.ModelDetailContent
import com.debanshu777.caraml.core.recommendation.LlmModelDescriptor
import com.debanshu777.caraml.core.recommendation.ModelFileIdentity
import com.debanshu777.caraml.core.recommendation.QuantizationEvidence
import com.debanshu777.caraml.core.download.DownloadArtifactState
import com.debanshu777.caraml.core.download.DownloadBatchState
import com.debanshu777.caraml.features.modelhub.domain.DescriptorState
import com.debanshu777.caraml.features.modelhub.domain.RecommendedModelUiState
import com.debanshu777.caraml.features.modelhub.presentation.search.DurableDownloadControlUiState
import com.debanshu777.caraml.features.modelhub.presentation.search.GgufFileUiState
import com.debanshu777.huggingfacemanager.download.DownloadArtifactIdentity
import com.debanshu777.huggingfacemanager.model.ListModelsResponse
import com.debanshu777.huggingfacemanager.model.ModelDetailResponse
import com.debanshu777.huggingfacemanager.model.ModelSort
import com.debanshu777.huggingfacemanager.model.ParameterRange
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import com.debanshu777.caraml.core.drawer.LocalNavigationMenuAction
import com.debanshu777.caraml.core.recommendation.RecommendationProfile
import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import com.debanshu777.caraml.core.theme.CaraMLTheme
import com.debanshu777.caraml.core.theme.ThemePreferences
import com.debanshu777.caraml.core.ui.components.AuroraBackdrop
import com.debanshu777.caraml.features.modelhub.presentation.downloaded.components.DownloadedListItem
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelHubContextStrip
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelHubStateKind
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelHubStateView
import com.debanshu777.caraml.features.modelhub.presentation.search.components.SearchBar
import com.debanshu777.caraml.features.modelhub.presentation.search.components.SearchListItem

/** Optional rendered evidence; run with CARAML_VISUAL_EVIDENCE_DIR to save PNGs. */
class ModelHubVisualEvidenceTest {
    @Test
    fun landscapeModelsSpacing() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                Box(Modifier.requiredSize(820.dp, 360.dp).testTag("visual-root")) {
                    BrandedModelHubEvidence(BrandEvidenceScene.Unknown)
                }
            }
        }
        saveModelHubEvidence("models-820x360-landscape.png")
    }

    @Test
    fun brandedLibraryUnknownAndErrorAt390() = runComposeUiTest {
        var scene by mutableStateOf(BrandEvidenceScene.Library)
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                Box(Modifier.requiredSize(390.dp, 740.dp).testTag("visual-root")) {
                    BrandedModelHubEvidence(scene)
                }
            }
        }
        saveModelHubEvidence("brand-390-library.png")
        onNodeWithText("Finish setup").performScrollTo().assertIsDisplayed()
        onNodeWithTag("model-primary-results").performScrollToKey("Audio-encoder")
        onNodeWithText("Unsupported", useUnmergedTree = true).assertIsDisplayed()
        saveModelHubEvidence("brand-390-library-unsupported.png")
        runOnIdle { scene = BrandEvidenceScene.Unknown }
        onNodeWithText("Needs information", useUnmergedTree = true).assertIsDisplayed()
        saveModelHubEvidence("brand-390-unknown.png")
        runOnIdle { scene = BrandEvidenceScene.Error }
        onNodeWithText("Retry").assertIsDisplayed()
        saveModelHubEvidence("brand-390-error.png")
        onNodeWithText("Open library").performScrollTo().assertIsDisplayed()
        runOnIdle { scene = BrandEvidenceScene.Loading }
        onNodeWithText("Loading models").assertIsDisplayed()
        saveModelHubEvidence("brand-390-loading.png")
        runOnIdle { scene = BrandEvidenceScene.EmptyLibrary }
        onNodeWithText("Explore models").performScrollTo().assertIsDisplayed()
        saveModelHubEvidence("brand-390-empty-library.png")
    }

    @Test
    fun brandedLibraryUnknownAndErrorAt320DoubleTextScale() = runComposeUiTest {
        var scene by mutableStateOf(BrandEvidenceScene.Library)
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                Box(Modifier.requiredSize(320.dp, 740.dp).testTag("visual-root")) {
                    BrandedModelHubEvidence(scene)
                }
            }
        }
        saveModelHubEvidence("brand-320-200-library.png")
        onNodeWithTag("model-primary-results").performScrollToKey("Stable-Diffusion")
        onNodeWithText("Finish setup").performScrollTo().assertIsDisplayed()
        saveModelHubEvidence("brand-320-200-library-repair.png")
        runOnIdle { scene = BrandEvidenceScene.Unknown }
        onNodeWithText("Needs information", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        saveModelHubEvidence("brand-320-200-unknown.png")
        runOnIdle { scene = BrandEvidenceScene.Error }
        onNodeWithText("Retry").performScrollTo().assertIsDisplayed()
        saveModelHubEvidence("brand-320-200-error.png")
        onNodeWithText("Open library").performScrollTo().assertIsDisplayed()
        runOnIdle { scene = BrandEvidenceScene.Loading }
        onNodeWithText("Loading models").assertIsDisplayed()
        saveModelHubEvidence("brand-320-200-loading.png")
        runOnIdle { scene = BrandEvidenceScene.EmptyLibrary }
        onNodeWithText("Explore models").performScrollTo().assertIsDisplayed()
        saveModelHubEvidence("brand-320-200-empty-library.png")
    }

    @Test
    fun deviceReadingsAndUnavailableRecoveryAt390() = runComposeUiTest {
        var available by mutableStateOf(true)
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                Box(Modifier.requiredSize(390.dp, 740.dp).testTag("visual-root")) {
                    CaraMLTheme(ThemePreferences(reduceMotion = true, themeMode = com.debanshu777.caraml.core.theme.ThemeMode.LIGHT)) {
                        AuroraBackdrop {
                            ModelHubDeviceInfo(
                                storageInfo = if (available) StorageInfoUiState(
                                    totalDeviceBytes = 128L * 1024 * 1024 * 1024,
                                    availableDeviceBytes = 49L * 1024 * 1024 * 1024,
                                    usedByModelsBytes = 6L * 1024 * 1024 * 1024,
                                    deviceHints = com.debanshu777.caraml.core.platform.DeviceHints(4, 8, 3072, true),
                                    hasSampled = true,
                                ) else StorageInfoUiState(hasSampled = true),
                                profile = null, onBack = {}, onRefresh = {}, onOpenProfile = null,
                            )
                        }
                    }
                }
            }
        }
        onNodeWithText("Storage").assertIsDisplayed()
        saveModelHubEvidence("brand-390-device.png")
        runOnIdle { available = false }
        onNodeWithText("Check again").performScrollTo().assertIsDisplayed()
        saveModelHubEvidence("brand-390-device-unavailable.png")
    }

    @Test
    fun inlineDownloadsExposeRunningPausedWaitingAndRetryStates() = runComposeUiTest {
        var state by mutableStateOf(DownloadArtifactState.RUNNING)
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                Box(Modifier.requiredSize(390.dp, 740.dp).testTag("visual-root")) {
                    CaraMLTheme(ThemePreferences(reduceMotion = true, themeMode = com.debanshu777.caraml.core.theme.ThemeMode.LIGHT)) {
                        AuroraBackdrop {
                            Column(Modifier.verticalScroll(rememberScrollState())) {
                                ModelDownloadQueueEntry(listOf(queueEvidenceBatch(state)), {},
                                    { _, _ -> }, { _, _ -> }, { _, _ -> }, { _, _ -> })
                            }
                        }
                    }
                }
            }
        }
        onNodeWithText("Pause download").assertIsDisplayed()
        saveModelHubEvidence("brand-390-download-running.png")
        runOnIdle { state = DownloadArtifactState.PAUSED }
        onNodeWithText("Resume download").assertIsDisplayed()
        saveModelHubEvidence("brand-390-download-paused.png")
        runOnIdle { state = DownloadArtifactState.FAILED_RETRYABLE }
        onNodeWithText("Retry download").assertIsDisplayed()
        onNodeWithText("Cancel download").assertIsDisplayed()
        saveModelHubEvidence("brand-390-download-retry.png")
        runOnIdle { state = DownloadArtifactState.WAITING_FOR_NETWORK }
        onNodeWithText("Waiting for network").assertIsDisplayed()
        saveModelHubEvidence("brand-390-download-waiting.png")
    }

    @Test
    fun compactEvaluatedAndActiveFileActionsAtDoubleTextScale() = runComposeUiTest {
        val repositoryId = "sample/compact-model"
        val filename = "compact-model-Q4_K_M.gguf"
        val artifact = requireNotNull(DownloadArtifactIdentity.create(
            repositoryId = repositoryId,
            immutableRevision = "a".repeat(40),
            relativePath = filename,
            remoteObjectId = "sha256:${"b".repeat(64)}",
            expectedBytes = 2_000_000_000L,
        ))
        val fileIdentity = ModelFileIdentity(
            repositoryId = repositoryId,
            revision = artifact.immutableRevision,
            path = filename,
            sizeBytes = artifact.expectedBytes,
            gitOid = null,
            lfsOid = "b".repeat(64),
            xetHash = null,
            evidence = emptyList(),
        )
        val recommendation = RecommendedModelUiState(
            sourceModel = ListModelsResponse.Model(id = repositoryId),
            repositoryId = repositoryId,
            descriptorState = DescriptorState.ASSESSED,
            objectiveAssessment = null,
            personalizedResult = null,
            selectedVariantName = filename,
            stableModelId = repositoryId,
            sourceIndex = 0,
            selectedDescriptor = LlmModelDescriptor(
                repositoryId = repositoryId,
                revision = artifact.immutableRevision,
                file = fileIdentity,
                architecture = "llama",
                quantization = QuantizationEvidence.Known("Q4_K_M"),
                parameterCount = 3_000_000_000L,
                contextLimit = 4_096,
                transformerShape = null,
                ggufVersion = 3,
                requiredEngineFeatures = emptyList(),
                evidence = emptyList(),
            ),
        )
        var active by mutableStateOf(false)
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                Surface(Modifier.requiredSize(360.dp, 800.dp).testTag("visual-root")) {
                    ModelDetailContent(
                        model = ModelDetailResponse(modelId = repositoryId),
                        ggufFiles = listOf(GgufFileUiState(
                            path = filename,
                            filename = filename,
                            sizeBytes = artifact.expectedBytes,
                            isDownloaded = false,
                            progress = if (active) 42f else null,
                            artifact = artifact,
                            durableControl = if (active) DurableDownloadControlUiState(
                                batchId = "batch", artifactId = "artifact",
                                batchState = DownloadBatchState.RUNNING,
                                artifactState = DownloadArtifactState.RUNNING,
                            ) else null,
                        )),
                        isDownloading = active,
                        activeDownloadArtifact = if (active) artifact else null,
                        onDownloadClick = { _, _, _ -> },
                        recommendationState = recommendation,
                    )
                }
            }
        }
        onNodeWithTag("detail-files").performScrollTo()
        onNodeWithText("Evaluated variant").assertIsDisplayed()
        saveModelHubEvidence("compact-200-file-evaluated.png")
        runOnIdle { active = true }
        onNodeWithContentDescription("Pause download $filename").assertIsDisplayed()
        saveModelHubEvidence("compact-200-file-active.png")
    }

    @Test
    fun compactResultsEndAtDoubleTextScale() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                Box(Modifier.requiredSize(360.dp, 800.dp).testTag("visual-root")) {
                    ModelHubDevicePreview(populated = true)
                }
            }
        }
        saveModelHubEvidence("compact-200-results-top.png")
        onNodeWithTag("model-primary-results").performScrollToKey("tencent/Hy-MT2-1.8B-GGUF")
        onNodeWithText("Load more models").assertDoesNotExist()
        saveModelHubEvidence("compact-200-results-end.png")
    }

    @Test
    fun downloadRowAtDoubleTextScale() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                Surface(Modifier.requiredSize(360.dp, 480.dp).testTag("visual-root")) {
                    Column(Modifier.verticalScroll(rememberScrollState())) { ModelDownloadQueueLargeTextPreview() }
                }
            }
        }
        onNodeWithText("Progress unavailable").assertIsDisplayed()
        saveModelHubEvidence("compact-200-queue.png")
    }

    @Test
    fun compactFilterSizeAtDoubleTextScale() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                Surface(Modifier.requiredSize(320.dp, 740.dp).testTag("visual-root")) {
                  Column(Modifier.verticalScroll(rememberScrollState())) {
                    ModelHubInlineFilterPanel(
                        mode = ModelHubBrowseMode.LanguageModels,
                        ordering = ModelOrdering.Server(ModelSort.TRENDING),
                        minParams = ParameterRange.ZERO,
                        maxParams = ParameterRange.SIX_B,
                        onApply = { _, _, _, _ -> },
                    )
                  }
                }
            }
        }
        onNodeWithText("Apply filters").performScrollTo().assertIsDisplayed()
        saveModelHubEvidence("compact-200-filters-size.png")
    }

    @Test
    fun detailAtNormalAndDoubleTextScale() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                Box(Modifier.requiredSize(360.dp, 800.dp).testTag("visual-root")) {
                    ModelDetailsDevicePreview()
                }
            }
        }
        onNodeWithText("Show full name").assertIsDisplayed()
        saveModelHubEvidence("compact-200-detail.png")
    }

}

private enum class BrandEvidenceScene { Library, Unknown, Error, Loading, EmptyLibrary }

@Composable
private fun BrandedModelHubEvidence(scene: BrandEvidenceScene) {
    CaraMLTheme(ThemePreferences(reduceMotion = true, themeMode = com.debanshu777.caraml.core.theme.ThemeMode.LIGHT)) {
        CompositionLocalProvider(LocalNavigationMenuAction provides {}) {
            AuroraBackdrop {
                val results: @Composable () -> Unit = {
                    ModelHubTabLayout(
                        modifier = Modifier.fillMaxSize(),
                        scrollResetKey = scene,
                        context = {},
                        toolbar = {},
                        summary = {
                            ModelHubInlineFilters("Preview models", ModelHubBrowseMode.LanguageModels,
                                ModelOrdering.Server(ModelSort.TRENDING), ParameterRange.ZERO, ParameterRange.SIX_B,
                                onApply = { _, _, _, _ -> })
                        },
                        command = { SearchBar(query = "", onQueryChange = {}, onSearch = {},
                            placeholder = if (scene == BrandEvidenceScene.Library || scene == BrandEvidenceScene.EmptyLibrary) "Search your library" else "Find your next little brain") },
                        results = {
                            when (scene) {
                                BrandEvidenceScene.Library -> listOf(
                                    Triple("Qwen3-4B", "Qwen3-4B-Q4_K_M.gguf", LocalModelEntity.STATUS_READY),
                                    Triple("Stable-Diffusion", "model.safetensors", LocalModelEntity.STATUS_PARTIAL),
                                    Triple("Audio-encoder", "audio-encoder.onnx", LocalModelEntity.STATUS_READY),
                                ).forEachIndexed { index, (name, filename, status) ->
                                    item(key = name) {
                                        DownloadedListItem(
                                            model = LocalModelEntity(
                                                id = index.toLong(), modelId = "sample/$name", filename = filename,
                                                localPath = "/preview/$filename", sizeBytes = 2_600_000_000L,
                                                downloadedAt = 0L, author = "sample", libraryName = null,
                                                pipelineTag = when(index) { 1 -> "text-to-image"; 2 -> "audio-classification"; else -> "text-generation" },
                                                componentStatus = status,
                                            ),
                                            selectionMode = false, isSelected = false,
                                            onOpenModel = {}, onToggleSelect = {}, onLongPress = {},
                                            onFixComponents = if (status == LocalModelEntity.STATUS_PARTIAL) ({}) else null,
                                        )
                                    }
                                }
                                BrandEvidenceScene.Unknown -> item(key = "unknown") {
                                    SearchListItem(
                                        model = ListModelsResponse.Model(
                                            id = "sample/Stable-Diffusion", author = "sample", pipelineTag = "text-to-image",
                                        ),
                                        onClick = {},
                                    )
                                }
                                BrandEvidenceScene.Error -> item(key = "error") {
                                    ModelHubStateView(
                                        kind = ModelHubStateKind.Error,
                                        message = "Couldn't reach the model hub. Your local models are still available.",
                                        actionLabel = "Retry", onAction = {},
                                        secondaryActionLabel = "Open library", onSecondaryAction = {},
                                    )
                                }
                                BrandEvidenceScene.Loading -> item(key = "loading") {
                                    ModelHubStateView(ModelHubStateKind.Loading, "Loading models")
                                }
                                BrandEvidenceScene.EmptyLibrary -> item(key = "empty-library") {
                                    ModelHubStateView(ModelHubStateKind.Empty, "Download a model to start creating. Your saved models will appear here.",
                                        title = "Your library is empty", actionLabel = "Explore models", onAction = {})
                                }
                            }
                        },
                    )
                }
                ModelHubScreenLayout(
                    selectedTabIndex = if (scene == BrandEvidenceScene.Library || scene == BrandEvidenceScene.EmptyLibrary) 1 else 0,
                    onTabSelected = {},
                    sharedContext = {
                        ModelHubContextStrip(
                            StorageInfoUiState(
                                totalDeviceBytes = 128L * 1024 * 1024 * 1024,
                                availableDeviceBytes = 49L * 1024 * 1024 * 1024,
                                usedByModelsBytes = 6L * 1024 * 1024 * 1024,
                            ),
                            profile = RecommendationProfile(),
                            onOpenProfile = {},
                            onOpenDevice = {},
                        )
                    },
                    discoverContent = results,
                    libraryContent = results,
                )
            }
        }
    }
}

internal fun androidx.compose.ui.test.ComposeUiTest.saveModelHubEvidence(name: String) {
        val directory = System.getenv("CARAML_VISUAL_EVIDENCE_DIR")?.takeIf(String::isNotBlank)
            ?: return
        val pixels = onNodeWithTag("visual-root").captureToImage().toPixelMap()
        val image = BufferedImage(pixels.width, pixels.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until pixels.height) {
            for (x in 0 until pixels.width) {
                val color = pixels[x, y]
                fun channel(value: Float) = (value.coerceIn(0f, 1f) * 255f).toInt()
                val argb = (channel(color.alpha) shl 24) or
                    (channel(color.red) shl 16) or
                    (channel(color.green) shl 8) or
                    channel(color.blue)
                image.setRGB(x, y, argb)
            }
        }
        val target = File(directory, name)
        target.parentFile.mkdirs()
        ImageIO.write(image, "png", target)
    }

private fun queueEvidenceBatch(state: DownloadArtifactState): DownloadBatchSnapshot {
    val identity = requireNotNull(DownloadArtifactIdentity.create(
        repositoryId = "sample/Qwen3-4B-GGUF", immutableRevision = "a".repeat(40),
        relativePath = "Qwen3-4B-Q4_K_M.gguf", remoteObjectId = null, expectedBytes = 2_600_000_000L,
    ))
    val artifact = DownloadArtifactSnapshot(
        artifactId = "evidence-artifact", batchId = "evidence-batch",
        request = DownloadArtifactRequest(DownloadMetadataDTO(identity, "model", identity.expectedBytes, null, null, null), primary = true),
        state = state, userIntent = DownloadUserIntent.RUN, bytesReceived = 1_560_000_000L, expectedBytes = identity.expectedBytes,
    )
    return DownloadBatchSnapshot(
        batchId = "evidence-batch", ownerModelId = identity.repositoryId, modelType = "LLM",
        displayName = "Qwen3-4B", state = when (state) {
            DownloadArtifactState.PAUSED -> DownloadBatchState.PAUSED
            DownloadArtifactState.FAILED_RETRYABLE -> DownloadBatchState.FAILED_RETRYABLE
            DownloadArtifactState.WAITING_FOR_NETWORK -> DownloadBatchState.WAITING_FOR_NETWORK
            else -> DownloadBatchState.RUNNING
        }, userIntent = DownloadUserIntent.RUN, artifacts = listOf(artifact),
        evidence = EncodedModelEvidence(InstalledEvidenceState.REQUIRES_ENRICHMENT, 1, "", ""),
    )
}
