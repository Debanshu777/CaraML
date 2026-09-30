@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.debanshu777.caraml.features.modelhub.presentation.search

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Surface
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelHubFilterPanel
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
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/** Optional rendered evidence; run with CARAML_VISUAL_EVIDENCE_DIR to save PNGs. */
class ModelHubVisualEvidenceTest {
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
        onNodeWithTag("model-primary-results").performScrollToIndex(5)
        onNodeWithText("Load more models").assertDoesNotExist()
        saveModelHubEvidence("compact-200-results-end.png")
    }

    @Test
    fun downloadRowAtDoubleTextScale() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                Surface(Modifier.requiredSize(360.dp, 480.dp).testTag("visual-root")) {
                    ModelDownloadQueueLargeTextPreview()
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
                Surface(Modifier.requiredSize(360.dp, 800.dp).testTag("visual-root")) {
                    ModelHubFilterPanel(
                        mode = ModelHubBrowseMode.LanguageModels,
                        ordering = ModelOrdering.Server(ModelSort.TRENDING),
                        minParams = ParameterRange.ZERO,
                        maxParams = ParameterRange.SIX_B,
                        onApply = { _, _, _, _ -> },
                    )
                }
            }
        }
        onNodeWithText("Size").performClick()
        onNodeWithText("View models").assertIsDisplayed()
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
