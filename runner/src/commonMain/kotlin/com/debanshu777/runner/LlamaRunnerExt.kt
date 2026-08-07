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
 * Streams cumulative [InferenceChunk] snapshots assembled in Kotlin from native
 * O(n) deltas. Native returns only the appended tail per token; a delta prefixed
 * with '' is a resync payload (parser retroactively reclassified tail bytes)
 * whose remainder replaces the accumulated stream.
 */
fun LlamaRunner.generateStructuredChunks(): Flow<InferenceChunk> =
    structuredChunkFlow(
        nextToken = { nextToken() },
        reasoningDelta = { getReasoningDelta() },
        contentDelta = { getContentDelta() },
    )

private const val RESYNC_SENTINEL = ''

/** Testable core loop — no native dependency. Accumulates deltas into cumulative chunks. */
internal fun structuredChunkFlow(
    nextToken: () -> String?,
    reasoningDelta: () -> String,
    contentDelta: () -> String,
): Flow<InferenceChunk> = flow {
    val reasoning = StringBuilder()
    val content = StringBuilder()
    while (true) {
        nextToken() ?: break
        applyDelta(reasoning, reasoningDelta())
        applyDelta(content, contentDelta())
        emit(InferenceChunk(reasoning = reasoning.toString(), content = content.toString()))
    }
}

private fun applyDelta(acc: StringBuilder, delta: String) {
    if (delta.isEmpty()) return
    if (delta[0] == RESYNC_SENTINEL) {
        acc.setLength(0)
        acc.append(delta, 1, delta.length)
    } else {
        acc.append(delta)
    }
}
