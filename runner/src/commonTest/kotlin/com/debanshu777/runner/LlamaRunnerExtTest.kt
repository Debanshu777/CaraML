package com.debanshu777.runner

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class LlamaRunnerExtTest {
    @Test
    fun deltaFlow_accumulates_appends() = runTest {
        val tokens = ArrayDeque(listOf("a", "b", "c"))
        val reasoningDeltas = ArrayDeque(listOf("Th", "ink", ""))
        val contentDeltas = ArrayDeque(listOf("", "", "Hi"))
        val chunks = structuredChunkFlow(
            nextToken = { tokens.removeFirstOrNull() },
            reasoningDelta = { reasoningDeltas.removeFirstOrNull() ?: "" },
            contentDelta = { contentDeltas.removeFirstOrNull() ?: "" },
        ).toList()
        assertEquals("Think", chunks.last().reasoning)
        assertEquals("Hi", chunks.last().content)
    }

    @Test
    fun deltaFlow_resync_sentinel_replaces() = runTest {
        val tokens = ArrayDeque(listOf("a", "b"))
        // second reasoning delta starts with resync sentinel + new full text
        val reasoningDeltas = ArrayDeque(listOf("Thinking", "Think"))
        val contentDeltas = ArrayDeque(listOf("", "ing done"))
        val chunks = structuredChunkFlow(
            nextToken = { tokens.removeFirstOrNull() },
            reasoningDelta = { reasoningDeltas.removeFirstOrNull() ?: "" },
            contentDelta = { contentDeltas.removeFirstOrNull() ?: "" },
        ).toList()
        assertEquals("Think", chunks.last().reasoning)
        assertEquals("ing done", chunks.last().content)
    }
}
