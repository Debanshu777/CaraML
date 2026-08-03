package com.debanshu777.runner

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class LlamaRunnerExtTest {
    @Test
    fun emitsCumulativeSnapshotsPerToken() = runTest {
        val tokens = ArrayDeque(listOf("<think>", "hi", "</think>", "Answer"))
        // reasoning/content mirror what native would return cumulatively
        val reasoning = ArrayDeque(listOf("", "hi", "hi", "hi"))
        val content = ArrayDeque(listOf("", "", "", "Answer"))
        val chunks = structuredChunkFlow(
            nextToken = { tokens.removeFirstOrNull() },
            reasoning = { reasoning.removeFirst() },
            content = { content.removeFirst() },
        ).toList()
        assertEquals(4, chunks.size)
        assertEquals(InferenceChunk("hi", "Answer"), chunks.last())
    }
}
