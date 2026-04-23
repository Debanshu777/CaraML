package com.debanshu777.caraml.features.chat.domain

/**
 * Heuristic identifier for reasoning-style chat models that emit a separate
 * thinking phase (typically wrapped in `<think>...</think>` or similar tags).
 *
 * This is a name-based hint — used by UI/wiring decisions like whether to
 * default-expand the Thoughts panel — and is NOT load-bearing for correctness.
 * The structured-output grammar runs unconditionally; this just lets us
 * surface an inline badge or adjust default disclosure behavior.
 */
object ReasoningModelClassifier {

    private val patterns: List<Regex> = listOf(
        Regex("""deepseek-?r1""", RegexOption.IGNORE_CASE),
        Regex("""qwen.*thinking""", RegexOption.IGNORE_CASE),
        Regex("""qwq""", RegexOption.IGNORE_CASE),
        Regex("""(^|[^a-z])o1[-_]""", RegexOption.IGNORE_CASE),
        Regex("""gpt-?oss-?thinking""", RegexOption.IGNORE_CASE),
    )

    /** True if [modelName] looks like a known reasoning model. */
    fun isReasoningModel(modelName: String): Boolean {
        if (modelName.isBlank()) return false
        return patterns.any { it.containsMatchIn(modelName) }
    }
}
