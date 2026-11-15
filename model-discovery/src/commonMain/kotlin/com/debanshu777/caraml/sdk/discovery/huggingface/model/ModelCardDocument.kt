package com.debanshu777.caraml.sdk.discovery.huggingface.model

/** Bounded README content at the same immutable revision as the model's files. */
data class ModelCardDocument(
    val repositoryId: String,
    val revision: String,
    val markdown: String,
    val excerpt: String?,
) {
    val sourceUrl: String get() = "https://huggingface.co/$repositoryId/blob/$revision/README.md"
}

/** YAML is repository metadata, not part of either the article or its excerpt. */
internal fun parseModelCardDocument(repositoryId: String, revision: String, markdown: String): ModelCardDocument {
    val normalized = markdown.removePrefix("\uFEFF").replace("\r\n", "\n")
    val lines = normalized.lines()
    val body = if (lines.firstOrNull()?.trim() == "---") {
        val end = lines.drop(1).indexOfFirst { it.trim() == "---" || it.trim() == "..." }
        // Preserve an unterminated document for display without mistaking metadata for prose.
        if (end < 0) return ModelCardDocument(repositoryId, revision, normalized, excerpt = null)
        lines.drop(end + 2).joinToString("\n").trimStart()
    } else normalized
    return ModelCardDocument(repositoryId, revision, body, modelCardExcerpt(body))
}
