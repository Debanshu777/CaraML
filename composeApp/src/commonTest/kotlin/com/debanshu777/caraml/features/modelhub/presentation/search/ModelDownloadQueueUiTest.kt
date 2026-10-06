@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.debanshu777.caraml.features.modelhub.presentation.search

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import com.debanshu777.caraml.core.download.DownloadArtifactRequest
import com.debanshu777.caraml.core.download.DownloadArtifactSnapshot
import com.debanshu777.caraml.core.download.DownloadArtifactState
import com.debanshu777.caraml.core.download.DownloadBatchSnapshot
import com.debanshu777.caraml.core.download.DownloadBatchState
import com.debanshu777.caraml.core.download.DownloadUserIntent
import com.debanshu777.caraml.core.recommendation.storage.EncodedModelEvidence
import com.debanshu777.caraml.core.recommendation.storage.InstalledEvidenceState
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelDownloadQueueRow
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelDownloadBatchControls
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelDownloadQueueEntry
import com.debanshu777.huggingfacemanager.download.DownloadArtifactIdentity
import com.debanshu777.huggingfacemanager.download.DownloadMetadataDTO
import kotlin.test.Test
import kotlin.test.assertEquals

class ModelDownloadQueueUiTest {
    @Test
    fun activeQueueAppearsInlineBelowFiltersAndOpensQueue() = runComposeUiTest {
        val (batch, _) = queueSnapshot(DownloadBatchState.RUNNING, DownloadArtifactState.RUNNING)
        var opens = 0
        setContent {
            MaterialTheme {
                Box(Modifier.width(420.dp).height(800.dp)) {
                    ModelHubScreenLayout(
                        selectedTabIndex = 0,
                        onTabSelected = {},
                        sharedContext = { Text("Device profile") },
                        discoverContent = {
                            ModelHubTabLayout(context = {}, toolbar = {}, results = {}, summary = {
                                Text("Filters")
                                ModelDownloadQueueEntry(listOf(batch), onClick = { opens++ })
                            })
                        },
                        libraryContent = {},
                    )
                }
            }
        }
        val profile = onNodeWithText("Device profile").fetchSemanticsNode().boundsInRoot
        val queue = onNodeWithTag("model-download-queue-entry").fetchSemanticsNode().boundsInRoot
        val tabs = onNodeWithTag("model-tabs").fetchSemanticsNode().boundsInRoot
        val filters = onNodeWithText("Filters").fetchSemanticsNode().boundsInRoot
        kotlin.test.assertTrue(tabs.bottom <= profile.top)
        kotlin.test.assertTrue(tabs.bottom <= filters.top)
        kotlin.test.assertTrue(filters.bottom <= queue.top)
        onNodeWithText("Downloading").assertIsDisplayed()
        onNodeWithText("Download details").performScrollTo().performClick()
        runOnIdle { assertEquals(1, opens) }
    }

    @Test
    fun unknownTransferTotalIsIndeterminateAndCanBePaused() = runComposeUiTest {
        var pauses = 0
        val (batch, artifact) = queueSnapshot(DownloadBatchState.RUNNING, DownloadArtifactState.RUNNING)
        setContent {
            MaterialTheme {
                androidx.compose.foundation.layout.Column {
                    ModelDownloadQueueRow(batch, artifact)
                    ModelDownloadBatchControls(batch, { pauses++ }, {}, {}, {})
                }
            }
        }
        onNodeWithText("Downloading").assertIsDisplayed()
        onNodeWithText("Progress unavailable").assertIsDisplayed()
        onNodeWithText("0%").assertDoesNotExist()
        onNodeWithText("Pause download").performClick()
        runOnIdle { assertEquals(1, pauses) }
    }

    @Test
    fun pausedAndFailedRowsExposeOnlyValidRecovery() = runComposeUiTest {
        val (pausedBatch, pausedArtifact) = queueSnapshot(DownloadBatchState.PAUSED, DownloadArtifactState.PAUSED)
        val (failedBatch, failedArtifact) = queueSnapshot(DownloadBatchState.FAILED_RETRYABLE, DownloadArtifactState.FAILED_RETRYABLE)
        setContent {
            MaterialTheme {
                androidx.compose.foundation.layout.Column {
                    ModelDownloadQueueRow(pausedBatch, pausedArtifact)
                    ModelDownloadBatchControls(pausedBatch, {}, {}, {}, {})
                    ModelDownloadQueueRow(failedBatch, failedArtifact)
                    ModelDownloadBatchControls(failedBatch, {}, {}, {}, {})
                }
            }
        }
        onNodeWithText("Resume download").assertIsDisplayed()
        onNodeWithText("Retry download").assertIsDisplayed()
        onNodeWithText("Download failed · retry available").assertIsDisplayed()
        onNodeWithText("Pause download").assertDoesNotExist()
    }
}

private fun queueSnapshot(
    batchState: DownloadBatchState,
    artifactState: DownloadArtifactState,
): Pair<DownloadBatchSnapshot, DownloadArtifactSnapshot> {
    val identity = requireNotNull(DownloadArtifactIdentity.create(
        repositoryId = "sample/model",
        immutableRevision = "a".repeat(40),
        relativePath = "model.gguf",
        remoteObjectId = null,
        expectedBytes = 1_000_000L,
    ))
    val artifact = DownloadArtifactSnapshot(
        artifactId = "artifact-$artifactState",
        batchId = "batch-$batchState",
        request = DownloadArtifactRequest(
            metadata = DownloadMetadataDTO(identity, "model", identity.expectedBytes, null, null, null),
            primary = true,
        ),
        state = artifactState,
        userIntent = DownloadUserIntent.RUN,
        bytesReceived = 128L,
        expectedBytes = 0L,
    )
    val batch = DownloadBatchSnapshot(
        batchId = artifact.batchId,
        ownerModelId = identity.repositoryId,
        modelType = "LLM",
        displayName = "Sample model",
        state = batchState,
        userIntent = DownloadUserIntent.RUN,
        artifacts = listOf(artifact),
        evidence = EncodedModelEvidence(InstalledEvidenceState.REQUIRES_ENRICHMENT, 1, "", ""),
    )
    return batch to artifact
}
