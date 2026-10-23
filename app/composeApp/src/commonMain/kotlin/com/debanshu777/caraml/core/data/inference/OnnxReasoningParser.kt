package com.debanshu777.caraml.core.data.inference

import com.debanshu777.caraml.sdk.plugin.llama.bridge.InferenceChunk

/** Qwen3 emits the opening marker itself. Preserve markers split across token boundaries. */
internal class OnnxReasoningParser {
    private var pending = ""
    private var mode = 0 // opening, reasoning, content
    fun push(text: String): InferenceChunk {
        pending += text
        if (mode == 0) {
            if (pending != "<think>" && "<think>".startsWith(pending)) return InferenceChunk("", "")
            if (pending.startsWith("<think>")) { pending = pending.removePrefix("<think>"); mode = 1 }
            else mode = 2
        }
        if (mode == 2) return InferenceChunk("", pending.also { pending = "" })
        val end = pending.indexOf("</think>")
        if (end >= 0) {
            val chunk = InferenceChunk(pending.substring(0, end), pending.substring(end + 8))
            pending = ""; mode = 2
            return chunk
        }
        val retained = (1..minOf(7, pending.length)).lastOrNull { pending.endsWith("</think>".take(it)) } ?: 0
        val ready = pending.dropLast(retained)
        pending = pending.takeLast(retained)
        return InferenceChunk(ready, "")
    }
    fun finish() = if (mode == 1) InferenceChunk(pending.also { pending = "" }, "")
        else InferenceChunk("", pending.also { pending = "" })
}
