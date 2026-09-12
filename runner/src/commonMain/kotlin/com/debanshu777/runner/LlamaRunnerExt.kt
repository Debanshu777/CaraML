package com.debanshu777.runner

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.time.TimeSource

fun LlamaRunner.generateFlowTokens(): Flow<String> = flow {
    while (true) {
        val token = nextToken() ?: break
        if (token.isNotEmpty()) {
            emit(token)
        }
    }
}

/**
 * Streams lossless [InferenceChunk] deltas from native without rebuilding the
 * cumulative response per token. A delta prefixed
 * with '' is a resync payload (parser retroactively reclassified tail bytes)
 * whose remainder replaces the consumer's accumulated stream.
 */
fun LlamaRunner.generateStructuredChunks(): Flow<InferenceChunk> =
    structuredChunkFlow(
        nextToken = { nextToken() },
        reasoningDelta = { getReasoningDelta() },
        contentDelta = { getContentDelta() },
    )

private const val RESYNC_SENTINEL = ''

/** Testable core loop with no native dependency. */
internal fun structuredChunkFlow(
    nextToken: () -> String?,
    reasoningDelta: () -> String,
    contentDelta: () -> String,
    monotonicNanos: () -> Long = defaultMonotonicNanos(),
): Flow<InferenceChunk> = flow {
    while (true) {
        val started = monotonicNanos()
        val token = nextToken()
        val nativeDecodeNanoseconds = monotonicNanos() - started
        val reasoning = parseDelta(reasoningDelta())
        val content = parseDelta(contentDelta())
        if (token == null) {
            if (reasoning.hasUpdate || content.hasUpdate) {
                emit(reasoning.toChunk(content, isTokenEvent = false, nativeDecodeNanoseconds = 0L))
            }
            break
        }
        emit(reasoning.toChunk(content, nativeDecodeNanoseconds = nativeDecodeNanoseconds))
    }
}

private data class ParsedDelta(
    val text: String,
    val isResync: Boolean,
) {
    val hasUpdate: Boolean get() = isResync || text.isNotEmpty()
}

private fun parseDelta(delta: String): ParsedDelta =
    if (delta.startsWith(RESYNC_SENTINEL)) {
        ParsedDelta(text = delta.substring(1), isResync = true)
    } else {
        ParsedDelta(text = delta, isResync = false)
    }

private fun ParsedDelta.toChunk(
    content: ParsedDelta,
    isTokenEvent: Boolean = true,
    nativeDecodeNanoseconds: Long = 0L,
): InferenceChunk =
    InferenceChunk(
        reasoningDelta = text,
        contentDelta = content.text,
        reasoningResync = isResync,
        contentResync = content.isResync,
        isTokenEvent = isTokenEvent,
        nativeDecodeNanoseconds = nativeDecodeNanoseconds.takeIf { it > 0L } ?: 0L,
    )

private fun defaultMonotonicNanos(): () -> Long {
    val origin = TimeSource.Monotonic.markNow()
    return { origin.elapsedNow().inWholeNanoseconds }
}
