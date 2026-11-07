package com.debanshu777.caraml.sdk.plugin.onnx

import kotlinx.serialization.json.*
import java.io.File
import kotlin.test.*

class OnnxChatTemplateTest {
    private val messages = listOf(OnnxChatMessage("system", "Be helpful."), OnnxChatMessage("user", "Hello"))
    private val datedTemplate = "Cutting Knowledge Date: December 2023"

    @Test fun datedLlamaPromptUsesInjectedDateAndDefaultStillUsesCurrentDate() {
        val codec = codecWith("<|start_header_id|>")
        var calls = 0
        val fixed = formatOnnxPrompt(messages, codec, datedTemplate, "llama") { calls++; "23 Oct 2026" }
        assertEquals(1, calls)
        assertTrue(fixed.contains("Today Date: 23 Oct 2026\n\nBe helpful.<|eot_id|>"))
        val nextDay = formatOnnxPrompt(messages, codec, datedTemplate, "llama") { "24 Oct 2026" }
        assertEquals(fixed.replace("Today Date: 23 Oct 2026", "Today Date: 24 Oct 2026"), nextDay)

        val before = onnxPromptDate()
        val current = formatOnnxPrompt(messages, codec, datedTemplate, "llama")
        val after = onnxPromptDate()
        assertTrue(listOf(before, after).any { current.contains("Today Date: $it\n") })
    }

    @Test fun undatedTemplatesNeverReadTheDateProvider() {
        val forbiddenDate = { error("Undated prompt must not access the date") }
        val llama = formatOnnxPrompt(messages, codecWith("<|start_header_id|>"), null, "llama", forbiddenDate)
        assertFalse(llama.contains("Today Date:"))
        val chatMl = formatOnnxPrompt(messages, codecWith("<|im_start|>"), datedTemplate, "qwen2", forbiddenDate)
        assertTrue(chatMl.startsWith("<|im_start|>system\nBe helpful."))
        assertFalse(chatMl.contains("Today Date:"))
    }

    private fun codecWith(marker: String): HuggingFaceBpeTokenizer {
        val file = File(checkNotNull(javaClass.getResource("/tiny-onnx/tokenizer.json")).toURI())
        val root = Json.parseToJsonElement(file.readText()).jsonObject
        val model = root.getValue("model").jsonObject
        val vocabulary = JsonObject(model.getValue("vocab").jsonObject + (marker to JsonPrimitive(256)))
        val updatedModel = JsonObject(model + ("vocab" to vocabulary))
        return HuggingFaceBpeTokenizer(JsonObject(root + ("model" to updatedModel)).toString())
    }
}
