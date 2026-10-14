package com.debanshu777.caraml.sdk.plugin.llama.bridge

/** Lossless parser deltas for one native token event. */
data class InferenceChunk(
    val reasoningDelta: String,
    val contentDelta: String,
    val reasoningResync: Boolean = false,
    val contentResync: Boolean = false,
    val isTokenEvent: Boolean = true,
    val nativeDecodeNanoseconds: Long = 0L,
)
