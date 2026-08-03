package com.debanshu777.runner

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

fun LlamaRunner.generateFlowTokens(): Flow<String> = flow {
    while (true) {
        val token = nextToken() ?: break
        if (token.isNotEmpty()) {
            emit(token)
        }
    }
}

/**
 * Streams cumulative [InferenceChunk] snapshots. After each generated token the
 * native layer has already re-parsed the assistant buffer into reasoning vs
 * content (see llama_runner_core reparse), so we simply read both accumulators.
 */
fun LlamaRunner.generateStructuredChunks(): Flow<InferenceChunk> =
    structuredChunkFlow(
        nextToken = { nextToken() },
        reasoning = { getReasoning() },
        content = { getContent() },
    )

/** Testable core loop — no native dependency. */
internal fun structuredChunkFlow(
    nextToken: () -> String?,
    reasoning: () -> String,
    content: () -> String,
): Flow<InferenceChunk> = flow {
    while (true) {
        nextToken() ?: break
        emit(InferenceChunk(reasoning = reasoning(), content = content()))
    }
}
