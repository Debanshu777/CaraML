@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.debanshu777.caraml.features.modelhub.presentation.details

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.ui.motion.LocalAuroraMotionPolicy
import com.debanshu777.caraml.core.ui.motion.auroraMotionPolicy
import com.debanshu777.caraml.features.modelhub.presentation.details.components.ModelDetailContent
import com.debanshu777.caraml.features.modelhub.presentation.details.components.GgufFileListItem
import com.debanshu777.caraml.features.modelhub.presentation.details.components.InstallBundleCard
import com.debanshu777.caraml.features.modelhub.presentation.search.InstallBundleUiState
import com.debanshu777.huggingfacemanager.model.ModelDetailResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModelDetailsAuroraUiTest {

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

        onNodeWithContentDescription("Download weights/model-q4.gguf").performClick()
        runOnIdle { assertEquals("weights/model-q4.gguf", requested) }
    }

    @Test
    fun detailsMoveInstallSummaryIntoSupportingPaneAtExpandedWidth() = runComposeUiTest {
        var windowWidth by mutableStateOf(839.dp)
        setContent {
            MaterialTheme {
                Box(Modifier.width(windowWidth - 48.dp).height(720.dp)) {
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
                        windowWidth = windowWidth,
                    )
                }
            }
        }

        val compactOverview = onNodeWithText("Overview")
            .fetchSemanticsNode().positionInRoot
        val compactInstall = onNodeWithText("Install summary")
            .fetchSemanticsNode().positionInRoot
        assertTrue(compactInstall.y > compactOverview.y)

        runOnIdle { windowWidth = 840.dp }

        val expandedOverview = onNodeWithText("Overview")
            .fetchSemanticsNode().positionInRoot
        val expandedInstall = onNodeWithText("Install summary")
            .fetchSemanticsNode().positionInRoot
        assertTrue(expandedInstall.x > expandedOverview.x)
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
