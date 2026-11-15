package com.debanshu777.caraml.sdk.discovery.huggingface.model

/** A bounded publisher excerpt from the normalized article, with front matter already removed. */
internal fun modelCardExcerpt(body: String): String? {
    if (body.trimStart().startsWith('{')) return null
    var fence: String? = null
    var comment = false
    val paragraph = StringBuilder()
    fun excerpt(): String? {
        val text = paragraph.toString()
            .replace(Regex("!\\[[^\\]]*]\\([^)]*\\)"), "")
            .replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1")
            .replace(Regex("<[^>]*>"), "")
            .replace(Regex("[`*_~]"), "")
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace(Regex("\\s+"), " ").trim()
        paragraph.clear()
        if (text.length < 60 || text.count { it.isLetter() } < 40) return null
        // Link-only navigation and badges are not useful model descriptions.
        if (text.split(' ').all { it.startsWith("http") || it == "|" }) return null
        if (text.length <= 600) return text
        return text.take(597).substringBeforeLast(' ', text.take(597)).trimEnd() + "…"
    }
    for (line in body.lineSequence()) {
        val trimmed = line.trim()
        if (trimmed.startsWith("<!--")) comment = true
        if (comment) {
            if (trimmed.contains("-->")) comment = false
            continue
        }
        if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
            val marker = trimmed.take(3)
            if (fence == null) {
                excerpt()?.let { return it }
                fence = marker
            } else if (fence == marker) fence = null
            continue
        }
        if (fence != null) continue
        val structural = trimmed.isEmpty() || trimmed.startsWith('#') || trimmed.startsWith('<') ||
            trimmed.startsWith("![") || trimmed.startsWith('|') || trimmed.startsWith("[![") ||
            trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith(">") ||
            Regex("^\\d+\\. ").containsMatchIn(trimmed) || Regex("^\\[[^]]+]:").containsMatchIn(trimmed)
        if (structural) {
            excerpt()?.let { return it }
        } else {
            paragraph.append(trimmed).append(' ')
        }
    }
    return excerpt()
}
