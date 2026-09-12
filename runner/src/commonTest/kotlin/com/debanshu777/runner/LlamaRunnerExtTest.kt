package com.debanshu777.runner

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class LlamaRunnerExtTest {
    @Test
    fun deltaFlow_emitsOnlyNewTextForEachNativeToken() = runTest {
        val tokens = ArrayDeque(listOf("a", "b", "c"))
        val reasoningDeltas = ArrayDeque(listOf("Th", "ink", ""))
        val contentDeltas = ArrayDeque(listOf("", "", "Hi"))
        val chunks = structuredChunkFlow(
            nextToken = { tokens.removeFirstOrNull() },
            reasoningDelta = { reasoningDeltas.removeFirstOrNull() ?: "" },
            contentDelta = { contentDeltas.removeFirstOrNull() ?: "" },
        ).toList()
        assertEquals(listOf("Th", "ink", ""), chunks.map { it.reasoningDelta })
        assertEquals(listOf("", "", "Hi"), chunks.map { it.contentDelta })
        assertEquals(listOf(false, false, false), chunks.map { it.reasoningResync })
        assertEquals(listOf(true, true, true), chunks.map { it.isTokenEvent })
    }

    @Test
    fun deltaFlow_marksResyncWithoutPassingSentinelToConsumers() = runTest {
        val tokens = ArrayDeque(listOf("a", "b"))
        // second reasoning delta starts with resync sentinel + new full text
        val reasoningDeltas = ArrayDeque(listOf("Thinking", "Think"))
        val contentDeltas = ArrayDeque(listOf("", "ing done"))
        val chunks = structuredChunkFlow(
            nextToken = { tokens.removeFirstOrNull() },
            reasoningDelta = { reasoningDeltas.removeFirstOrNull() ?: "" },
            contentDelta = { contentDeltas.removeFirstOrNull() ?: "" },
        ).toList()
        assertEquals("Think", chunks.last().reasoningDelta)
        assertEquals("ing done", chunks.last().contentDelta)
        assertEquals(true, chunks.last().reasoningResync)
        assertEquals(false, chunks.last().contentResync)
    }

    @Test
    fun deltaFlow_emitsFinalParserResyncAfterEndOfGeneration() = runTest {
        val tokens = ArrayDeque<String?>(listOf("a", null))
        val reasoningDeltas = ArrayDeque(listOf("draft", "\u0001final"))
        val contentDeltas = ArrayDeque(listOf("", "answer"))

        val chunks = structuredChunkFlow(
            nextToken = { tokens.removeFirstOrNull() },
            reasoningDelta = { reasoningDeltas.removeFirstOrNull() ?: "" },
            contentDelta = { contentDeltas.removeFirstOrNull() ?: "" },
        ).toList()

        assertEquals(2, chunks.size)
        assertEquals("final", chunks.last().reasoningDelta)
        assertEquals("answer", chunks.last().contentDelta)
        assertEquals(true, chunks.last().reasoningResync)
        assertEquals(false, chunks.last().isTokenEvent)
    }
}
