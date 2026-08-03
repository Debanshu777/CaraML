package com.debanshu777.runner

/** Cumulative parsed model output for the current turn. */
data class InferenceChunk(
    val reasoning: String,
    val content: String,
)
