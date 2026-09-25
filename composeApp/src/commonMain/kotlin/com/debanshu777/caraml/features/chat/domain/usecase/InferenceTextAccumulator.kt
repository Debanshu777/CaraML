package com.debanshu777.caraml.features.chat.domain.usecase

import com.debanshu777.runner.InferenceChunk

internal data class InferenceTextSnapshot(
    val reasoning: String,
    val content: String,
)

/**
 * Applies native parser deltas without rebuilding the full response for every
 * token. Immutable snapshots are materialized only at the UI update cadence.
 */
internal class InferenceTextAccumulator(
    private val updateIntervalMs: Long,
) {
    private val reasoning = StringBuilder()
    private val content = StringBuilder()
    private var dirty = false
    private var lastSnapshotAtMs: Long? = null

    init {
        require(updateIntervalMs >= 0L) { "Update interval must not be negative" }
    }

    fun apply(chunk: InferenceChunk) {
        dirty = applyDelta(reasoning, chunk.reasoningDelta, chunk.reasoningResync) || dirty
        dirty = applyDelta(content, chunk.contentDelta, chunk.contentResync) || dirty
    }

    fun snapshotIfDue(nowMs: Long): InferenceTextSnapshot? {
        if (!dirty) return null
        val previous = lastSnapshotAtMs
        if (previous != null && nowMs - previous < updateIntervalMs) return null
        lastSnapshotAtMs = nowMs
        return consumeSnapshot()
    }

    fun finalSnapshot(): InferenceTextSnapshot? =
        if (dirty) consumeSnapshot() else null

    private fun consumeSnapshot(): InferenceTextSnapshot {
        dirty = false
        return InferenceTextSnapshot(
            reasoning = reasoning.toString(),
            content = content.toString(),
        )
    }

    private fun applyDelta(
        destination: StringBuilder,
        delta: String,
        isResync: Boolean,
    ): Boolean {
        if (isResync) {
            destination.clear()
            destination.append(delta)
            return true
        }
        if (delta.isEmpty()) return false
        destination.append(delta)
        return true
    }
}
