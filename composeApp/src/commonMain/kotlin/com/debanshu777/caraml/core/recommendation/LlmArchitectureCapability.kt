package com.debanshu777.caraml.core.recommendation

private val CPU_ONLY_LLM_ARCHITECTURES = setOf(
    "qwen3next",
    "qwen35",
    "jamba",
    "mamba",
    "ssm",
    "recurrent_gemma",
    "granite_hybrid",
)

internal fun String?.requiresCpuOnlyLlmExecution(): Boolean =
    this?.trim()?.lowercase() in CPU_ONLY_LLM_ARCHITECTURES
