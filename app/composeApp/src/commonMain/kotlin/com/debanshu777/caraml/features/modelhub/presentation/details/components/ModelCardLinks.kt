package com.debanshu777.caraml.features.modelhub.presentation.details.components

import com.debanshu777.caraml.sdk.discovery.huggingface.model.ModelCardDocument
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.appendPathSegments
import io.ktor.http.decodeURLPart

/** Resolve README links without depending on the mutable `main` branch or the app's URL. */
internal fun ModelCardDocument.resolveCardUrl(destination: String, image: Boolean = false): String? {
    // The Markdown parser preserves angle brackets in inline/reference destinations.
    val value = destination.trim().removeSurrounding("<", ">").trim()
    if (value.isEmpty() || value.any { it.isISOControl() } || '\\' in value) return null
    val scheme = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")
    if (scheme.containsMatchIn(value) || value.startsWith("//")) {
        val url = runCatching { Url(if (value.startsWith("//")) "https:$value" else value) }.getOrNull() ?: return null
        return url.toString().takeIf { url.protocol.name in setOf("http", "https") && url.host.isNotBlank() }
    }
    if (value.startsWith('/')) return resolveCardUrl("https://huggingface.co$value", image)
    if (value.startsWith('#') || value.startsWith('?')) return "$sourceUrl$value"
    val suffixStart = value.indexOfAny(charArrayOf('?', '#')).takeIf { it >= 0 } ?: value.length
    val path = mutableListOf<String>()
    for (encoded in value.substring(0, suffixStart).split('/')) {
        val segment = runCatching { encoded.decodeURLPart() }.getOrNull() ?: return null
        when (segment) {
            "", "." -> Unit
            ".." -> if (path.isEmpty()) return null else path.removeAt(path.lastIndex)
            else -> {
                if ('/' in segment || '\\' in segment || segment.any { it.isISOControl() }) return null
                path += segment
            }
        }
    }
    val url = URLBuilder("https://huggingface.co").apply {
        appendPathSegments(repositoryId.split('/') + listOf(if (image) "resolve" else "blob", revision) + path, encodeSlash = true)
    }.buildString()
    return url + value.substring(suffixStart)
}

/** Normalize HTML image/layout spans only; Markdown code remains byte-for-byte unchanged. */
internal fun modelCardDisplayMarkdown(markdown: String): String {
    val root = org.intellij.markdown.parser.MarkdownParser(org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor())
        .buildMarkdownTreeFromString(markdown)
    val edits = mutableListOf<Triple<Int, Int, String>>()
    fun visit(node: org.intellij.markdown.ast.ASTNode) {
        when (node.type) {
            org.intellij.markdown.MarkdownElementTypes.CODE_FENCE,
            org.intellij.markdown.MarkdownElementTypes.CODE_BLOCK,
            org.intellij.markdown.MarkdownElementTypes.CODE_SPAN -> return
            org.intellij.markdown.MarkdownElementTypes.INLINE_LINK -> {
                // GFM classifies <https://...> as AUTOLINK here; the renderer expects a
                // LINK_DESTINATION. Normalize only this destination, never code/text.
                node.children.forEach { child ->
                    val source = markdown.substring(child.startOffset, child.endOffset)
                    if (child.type in setOf(org.intellij.markdown.MarkdownElementTypes.AUTOLINK, org.intellij.markdown.MarkdownElementTypes.LINK_DESTINATION) && source.startsWith('<') && source.endsWith('>')) {
                        edits += Triple(child.startOffset, child.endOffset, markdownDestination(source.removeSurrounding("<", ">")))
                    } else visit(child)
                }
            }
            org.intellij.markdown.MarkdownElementTypes.HTML_BLOCK,
            org.intellij.markdown.MarkdownTokenTypes.HTML_TAG -> {
                val source = markdown.substring(node.startOffset, node.endOffset)
                edits += Triple(node.startOffset, node.endOffset, normalizeCardHtml(source))
            }
            else -> node.children.forEach(::visit)
        }
    }
    visit(root)
    return StringBuilder(markdown).apply {
        edits.sortedByDescending { it.first }.forEach { (start, end, replacement) -> setRange(start, end, replacement) }
    }.toString()
}

private fun normalizeCardHtml(html: String): String {
    if (Regex("<(?i:script|style|pre)\\b").containsMatchIn(html)) return html
    fun String.attribute(name: String): String? = Regex("\\b$name\\s*=\\s*[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE)
        .find(this)?.groupValues?.get(1)?.replace("&amp;", "&")
    fun image(source: String): String {
        val url = source.attribute("src") ?: return source
        val alt = source.attribute("alt").orEmpty().replace("[", "\\[").replace("]", "\\]")
        return "![$alt](${markdownDestination(url)})"
    }
    val images = Regex("<img\\b[^>]*>", RegexOption.IGNORE_CASE)
    var content = Regex("<a\\b[^>]*>.*?</a\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).replace(html) { match ->
        val href = match.value.substringBefore('>').attribute("href")
        val inner = match.value.substringAfter('>').substringBeforeLast('<')
        val label = images.replace(inner) { image(it.value) }.trim()
        if (href == null) label else "[$label](${markdownDestination(href)})"
    }
    content = images.replace(content) { image(it.value) }
    content = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE).replace(content, "  \n")
    content = Regex("</?(?:div|p|center|figure|figcaption|details|summary)\\b[^>]*>", RegexOption.IGNORE_CASE).replace(content, "\n\n")
    return Regex("</?(?:span|a)\\b[^>]*>", RegexOption.IGNORE_CASE).replace(content, "")
}

private fun markdownDestination(value: String): String = value.replace(" ", "%20")
    .replace("(", "%28").replace(")", "%29").replace("<", "%3C").replace(">", "%3E")
