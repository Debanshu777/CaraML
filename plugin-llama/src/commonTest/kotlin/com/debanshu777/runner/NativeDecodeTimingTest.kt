package com.debanshu777.runner

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class NativeDecodeTimingTest {
    @Test
    fun chunkCarriesOnlyTimeSpentInNativeTokenDecode() = runTest {
        val tokens = ArrayDeque(listOf("a", "b", null))
        val times = ArrayDeque(listOf(0L, 10L, 1_000L, 1_030L, 2_000L, 2_001L))

        val chunks = structuredChunkFlow(
            nextToken = { tokens.removeFirst() },
            reasoningDelta = { "" },
            contentDelta = { "x" },
            monotonicNanos = { times.removeFirst() },
        ).toList()

        assertEquals(
            listOf(10L, 30L),
            chunks.filter(InferenceChunk::isTokenEvent).map { it.nativeDecodeNanoseconds },
        )
        assertEquals(0L, chunks.last().nativeDecodeNanoseconds)
    }
}
