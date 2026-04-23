package com.debanshu777.runner

/**
 * Streaming parser for the `<think>...</think><output>...</output>` token stream
 * produced under [STRICT_THINKING_OUTPUT_GRAMMAR].
 *
 * The parser maintains a tiny state machine and is fed one token (or chunk)
 * at a time via [accept]. It tolerates token splits that land in the middle
 * of a tag (e.g. `<thi` then `nking>`) by buffering up to [TAG_BUFFER_LIMIT]
 * characters at tag boundaries.
 *
 * If no recognized opener appears within the first [FALLBACK_PROBE_LIMIT]
 * characters, the parser falls back to treating the entire stream as
 * `output` — this makes us resilient to non-conforming or older models.
 *
 * [accept] returns a cumulative [Snapshot] each call; the caller does not need
 * to maintain its own [StringBuilder].
 */
class StructuredOutputParser {

    data class Snapshot(val thinking: String, val output: String)

    private enum class Phase { PRE_OPEN, THINKING, BETWEEN_SECTIONS, OUTPUT, DONE, FALLBACK }

    private var phase: Phase = Phase.PRE_OPEN
    private val thinking = StringBuilder()
    private val output = StringBuilder()

    /** Bytes seen but not yet committed — held while we wait to disambiguate a tag. */
    private val pending = StringBuilder()

    /** Total raw chars seen across all phases — used for fallback heuristic. */
    private var rawSeen: Int = 0

    /**
     * True after we've abandoned the structured-output contract (no recognized
     * opener arrived within [FALLBACK_PROBE_LIMIT] chars, or a malformed stream
     * pushed us into FALLBACK). Useful for callers wanting to log a warning.
     */
    val isInFallback: Boolean get() = phase == Phase.FALLBACK

    fun accept(token: String): Snapshot {
        if (token.isNotEmpty()) {
            rawSeen += token.length
            pending.append(token)
            drain()
        }
        return snapshot()
    }

    /** Flush any remaining buffered text into the active section. */
    fun finish(): Snapshot {
        if (pending.isNotEmpty()) {
            when (phase) {
                Phase.PRE_OPEN, Phase.FALLBACK -> {
                    output.append(pending)
                    pending.clear()
                    phase = Phase.FALLBACK
                }
                Phase.THINKING -> {
                    thinking.append(pending)
                    pending.clear()
                }
                Phase.BETWEEN_SECTIONS -> {
                    pending.clear()
                }
                Phase.OUTPUT -> {
                    // Strip any trailing fragment that is a non-empty proper
                    // prefix of "</output>" so we never leak a half-emitted
                    // close tag into the user-visible answer.
                    val safeLen = stripTrailingTagPrefix(pending, OUTPUT_CLOSE)
                    if (safeLen > 0) {
                        output.append(pending, 0, safeLen)
                    }
                    pending.clear()
                }
                Phase.DONE -> {
                    pending.clear()
                }
            }
        }
        return snapshot()
    }

    private fun snapshot(): Snapshot = Snapshot(thinking.toString(), output.toString())

    private fun drain() {
        var progressed = true
        while (progressed) {
            progressed = false
            when (phase) {
                Phase.PRE_OPEN -> progressed = handlePreOpen()
                Phase.THINKING -> progressed = handleInsideBlock(
                    closeTags = listOf(THINK_CLOSE, THINKING_CLOSE),
                    sink = thinking,
                    onClose = { phase = Phase.BETWEEN_SECTIONS },
                )
                Phase.BETWEEN_SECTIONS -> progressed = handleBetweenSections()
                Phase.OUTPUT -> progressed = handleInsideBlock(
                    closeTags = listOf(OUTPUT_CLOSE),
                    sink = output,
                    onClose = { phase = Phase.DONE },
                )
                Phase.FALLBACK -> {
                    if (pending.isNotEmpty()) {
                        output.append(pending)
                        pending.clear()
                        progressed = true
                    }
                }
                Phase.DONE -> {
                    // Discard any tail (model may emit EOS-related noise).
                    if (pending.isNotEmpty()) {
                        pending.clear()
                        progressed = true
                    }
                }
            }
        }
    }

    private fun handlePreOpen(): Boolean {
        val openers = listOf(THINK_OPEN, THINKING_OPEN, OUTPUT_OPEN)
        // Skip leading whitespace before any opener.
        var stripped = 0
        while (stripped < pending.length && pending[stripped].isWhitespace()) stripped++
        if (stripped > 0) {
            pending.deleteRange(0, stripped)
        }

        // Whitespace-only buffer: nothing to decide yet, wait for more.
        if (pending.isEmpty()) return false

        // Look for a known opener.
        for (tag in openers) {
            val idx = pending.indexOf(tag)
            if (idx == 0) {
                pending.deleteRange(0, tag.length)
                phase = when (tag) {
                    THINK_OPEN, THINKING_OPEN -> Phase.THINKING
                    OUTPUT_OPEN -> Phase.OUTPUT
                    else -> Phase.FALLBACK
                }
                return true
            }
        }

        // If pending could still be the start of any opener, wait for more.
        if (couldStartAny(pending, openers)) {
            // Fallback if we've seen too much without an opener.
            if (rawSeen >= FALLBACK_PROBE_LIMIT) {
                phase = Phase.FALLBACK
                return true
            }
            return false
        }

        // Pending starts with non-tag content and we have no opener: fallback.
        phase = Phase.FALLBACK
        return true
    }

    private fun handleInsideBlock(
        closeTags: List<String>,
        sink: StringBuilder,
        onClose: () -> Unit,
    ): Boolean {
        if (pending.isEmpty()) return false

        // Find earliest occurrence of any close tag.
        var earliestIdx = -1
        var earliestTag: String? = null
        for (tag in closeTags) {
            val idx = pending.indexOf(tag)
            if (idx >= 0 && (earliestIdx < 0 || idx < earliestIdx)) {
                earliestIdx = idx
                earliestTag = tag
            }
        }

        if (earliestIdx >= 0 && earliestTag != null) {
            if (earliestIdx > 0) sink.append(pending, 0, earliestIdx)
            pending.deleteRange(0, earliestIdx + earliestTag.length)
            onClose()
            return true
        }

        // No close tag fully present. Emit everything except a trailing fragment
        // that might be the start of a close tag.
        val safeEmitEnd = safeEmitEnd(pending, closeTags)
        if (safeEmitEnd > 0) {
            sink.append(pending, 0, safeEmitEnd)
            pending.deleteRange(0, safeEmitEnd)
            return true
        }
        return false
    }

    private fun handleBetweenSections(): Boolean {
        // Skip whitespace; accept only `<output>` next.
        var stripped = 0
        while (stripped < pending.length && pending[stripped].isWhitespace()) stripped++
        if (stripped > 0) {
            pending.deleteRange(0, stripped)
        }
        // Whitespace-only buffer: wait for more before deciding.
        if (pending.isEmpty()) return false

        val idx = pending.indexOf(OUTPUT_OPEN)
        if (idx == 0) {
            pending.deleteRange(0, OUTPUT_OPEN.length)
            phase = Phase.OUTPUT
            return true
        }
        if (couldStartAny(pending, listOf(OUTPUT_OPEN))) {
            if (rawSeen >= FALLBACK_PROBE_LIMIT) {
                // Treat the rest as output anyway.
                phase = Phase.OUTPUT
                return true
            }
            return false
        }
        // Garbage before <output>: jump straight into OUTPUT and emit it.
        phase = Phase.OUTPUT
        return true
    }

    /**
     * Returns the largest prefix length we can emit safely: anything beyond
     * that might still be the start of one of [tags].
     */
    private fun safeEmitEnd(buf: StringBuilder, tags: List<String>): Int {
        val maxTail = tags.maxOf { it.length } - 1
        val limit = (buf.length - maxTail).coerceAtLeast(0)
        if (limit == buf.length) return limit
        // Walk from `limit` to `buf.length` and find the earliest position
        // where the suffix could match a tag prefix.
        for (i in limit..buf.length - 1) {
            for (tag in tags) {
                if (sliceMatchesPrefix(buf, i, tag)) {
                    return i
                }
            }
        }
        return buf.length
    }

    /** True if buf[start..end] is a non-empty prefix of [tag] (and end == buf.length). */
    private fun sliceMatchesPrefix(buf: StringBuilder, start: Int, tag: String): Boolean {
        val len = buf.length - start
        if (len <= 0 || len >= tag.length) return false
        for (k in 0 until len) {
            if (buf[start + k] != tag[k]) return false
        }
        return true
    }

    /** True if [buf]'s entire content is a non-empty proper prefix of any tag. */
    private fun couldStartAny(buf: StringBuilder, tags: List<String>): Boolean {
        if (buf.isEmpty()) return false
        for (tag in tags) {
            if (buf.length < tag.length) {
                var ok = true
                for (k in 0 until buf.length) {
                    if (buf[k] != tag[k]) { ok = false; break }
                }
                if (ok) return true
            }
        }
        // Also consider that the buffer might contain a partial tag NOT at
        // index 0 (e.g. text + "<thi"). Search for any '<' that begins a
        // possible tag prefix.
        val openIdx = buf.indexOf('<')
        if (openIdx in 0 until buf.length) {
            val tail = buf.substring(openIdx)
            for (tag in tags) {
                if (tail.length < tag.length && tag.startsWith(tail)) return true
            }
        }
        return false
    }

    /**
     * Returns the largest prefix length of [buf] that does not end in a
     * non-empty proper prefix of [tag]. Used by `finish()` for the OUTPUT
     * phase so a stream cut off mid-`</output>` doesn't leak the partial
     * tag into the user-visible answer.
     */
    private fun stripTrailingTagPrefix(buf: StringBuilder, tag: String): Int {
        if (buf.isEmpty()) return 0
        // Try the longest possible prefix of tag first; if buf ends with it,
        // strip it. Walk down to length 1.
        val maxCheck = minOf(buf.length, tag.length - 1)
        for (n in maxCheck downTo 1) {
            var match = true
            for (k in 0 until n) {
                if (buf[buf.length - n + k] != tag[k]) { match = false; break }
            }
            if (match) return buf.length - n
        }
        return buf.length
    }

    companion object {
        private const val THINK_OPEN = "<think>"
        private const val THINK_CLOSE = "</think>"
        private const val THINKING_OPEN = "<thinking>"
        private const val THINKING_CLOSE = "</thinking>"
        private const val OUTPUT_OPEN = "<output>"
        private const val OUTPUT_CLOSE = "</output>"

        private const val TAG_BUFFER_LIMIT = 16
        private const val FALLBACK_PROBE_LIMIT = 128
    }
}
