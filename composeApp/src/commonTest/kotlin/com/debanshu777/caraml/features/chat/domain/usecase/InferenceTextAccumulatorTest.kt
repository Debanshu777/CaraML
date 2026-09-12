package com.debanshu777.caraml.features.chat.domain.usecase

import com.debanshu777.runner.InferenceChunk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class InferenceTextAccumulatorTest {
    @Test
    fun appendAndResyncAreAppliedIndependently() {
        val accumulator = InferenceTextAccumulator(updateIntervalMs = 50L)

        accumulator.apply(InferenceChunk(reasoningDelta = "think", contentDelta = "ans"))
        accumulator.apply(InferenceChunk(reasoningDelta = "ing", contentDelta = "wer"))
        accumulator.apply(
            InferenceChunk(
                reasoningDelta = "revised",
                contentDelta = "!",
                reasoningResync = true,
            )
        )

        assertEquals(
            InferenceTextSnapshot(reasoning = "revised", content = "answer!"),
            accumulator.finalSnapshot(),
        )
    }

    @Test
    fun firstSnapshotIsImmediateAndLaterSnapshotsAreThrottled() {
        val accumulator = InferenceTextAccumulator(updateIntervalMs = 50L)

        accumulator.apply(InferenceChunk(reasoningDelta = "", contentDelta = "a"))
        assertNotNull(accumulator.snapshotIfDue(nowMs = 1_000L))

        accumulator.apply(InferenceChunk(reasoningDelta = "", contentDelta = "b"))
        assertNull(accumulator.snapshotIfDue(nowMs = 1_049L))
        assertEquals(
            InferenceTextSnapshot(reasoning = "", content = "ab"),
            accumulator.snapshotIfDue(nowMs = 1_050L),
        )
    }

    @Test
    fun finalSnapshotFlushesTextThatWasStillInsideThrottleWindow() {
        val accumulator = InferenceTextAccumulator(updateIntervalMs = 50L)
        accumulator.apply(InferenceChunk(reasoningDelta = "", contentDelta = "a"))
        accumulator.snapshotIfDue(nowMs = 1_000L)
        accumulator.apply(InferenceChunk(reasoningDelta = "", contentDelta = "b"))

        assertEquals(
            InferenceTextSnapshot(reasoning = "", content = "ab"),
            accumulator.finalSnapshot(),
        )
        assertNull(accumulator.finalSnapshot())
    }
}
