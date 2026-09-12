package com.debanshu777.runner

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class NativeDecodeTimingTest {
    @Test
    fun structuredChunksMeasureOnlyNativeTokenCalls() = runTest {
        val tokens = listOf<String?>("a", "b", null)
        var tokenIndex = 0
        val timestamps = ArrayDeque(listOf(10L, 30L, 40L, 90L, 100L, 110L))

        val chunks = structuredChunkFlow(
            nextToken = { tokens[tokenIndex++] },
            reasoningDelta = { "" },
            contentDelta = { "token" },
            monotonicNanos = { timestamps.removeFirst() },
        ).toList()

        assertEquals(listOf(20L, 50L), chunks.map { it.nativeDecodeNanoseconds })
    }
}
