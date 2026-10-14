package com.debanshu777.huggingfacemanager.download

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DownloadPolicyTest {
    @Test
    fun acceptsRepositoryIdsAndNestedModelPaths() {
        val request = validateDownloadRequest(
            modelId = "stabilityai/stable-diffusion-xl-base-1.0",
            path = "text_encoder/model.fp16.safetensors",
        )

        assertEquals("stabilityai/stable-diffusion-xl-base-1.0", request.modelId)
        assertEquals("text_encoder/model.fp16.safetensors", request.relativePath)
    }

    @Test
    fun rejectsPathsThatCanEscapeOrChangePlatformMeaning() {
        val unsafePaths = listOf(
            "",
            "../model.gguf",
            "weights/../../model.gguf",
            "/tmp/model.gguf",
            "weights\\model.gguf",
            "weights//model.gguf",
            "weights/./model.gguf",
            "C:/model.gguf",
            "weights/model\u0000.gguf",
        )

        unsafePaths.forEach { path ->
            assertFailsWith<IllegalArgumentException>(path) {
                validateDownloadRequest("org/model", path)
            }
        }
    }

    @Test
    fun rejectsUnsafeRepositoryIds() {
        val unsafeIds = listOf(
            "",
            "../model",
            "org/../model",
            "/model",
            "org/model/extra",
            "org\\model",
            "org/model?revision=bad",
            "org/model\u0000",
        )

        unsafeIds.forEach { modelId ->
            assertFailsWith<IllegalArgumentException>(modelId) {
                validateDownloadRequest(modelId, "model.gguf")
            }
        }
    }

    @Test
    fun knownLengthProgressSuppressesDuplicatePercentageWithinHeartbeatInterval() {
        val tracker = DownloadProgressTracker(contentLength = 1_000L)

        val first = tracker.next(bytesReceived = 1L)
        val duplicateBucket = tracker.next(bytesReceived = 9L)
        val nextBucket = tracker.next(bytesReceived = 10L)
        val preCommitComplete = tracker.next(bytesReceived = 1_000L)

        assertNotNull(first)
        assertEquals(0f, first.percentage)
        assertNull(duplicateBucket)
        assertNotNull(nextBucket)
        assertEquals(1f, nextBucket.percentage)
        assertNull(preCommitComplete)
    }

    @Test
    fun slowContinuousBytesPublishHeartbeatWithoutAChangedPercentage() {
        var now = 0L
        val tracker = DownloadProgressTracker(contentLength = 1_000_000_000L, clockMillis = { now })
        assertNotNull(tracker.next(1L))
        now = 999L
        assertNull(tracker.next(2L))
        now = 1_000L
        assertEquals(3L, assertNotNull(tracker.next(3L)).bytesReceived)
        now = 2_000L
        assertEquals(4L, assertNotNull(tracker.next(4L)).bytesReceived)
    }

    @Test
    fun unknownLengthProgressPublishesPerMebibyte() {
        val tracker = DownloadProgressTracker(contentLength = null)

        assertNull(tracker.next(bytesReceived = 1_048_575L))
        val first = tracker.next(bytesReceived = 1_048_576L)
        assertNotNull(first)
        assertEquals(-1f, first.percentage)
        assertNull(tracker.next(bytesReceived = 2_000_000L))
        assertNotNull(tracker.next(bytesReceived = 2_097_152L))
    }
}
