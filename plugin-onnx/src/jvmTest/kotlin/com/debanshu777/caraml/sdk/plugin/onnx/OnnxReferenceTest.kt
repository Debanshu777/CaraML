package com.debanshu777.caraml.sdk.plugin.onnx

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.*
import java.io.File
import kotlin.test.*

/** Opt-in real-model parity against Hugging Face tokenizers and Python ONNX Runtime. */
class OnnxReferenceTest {
    private fun fixtures(): File? = System.getProperty("caraml.onnx.fixture.dir")?.let { path ->
        File(path).let { if (File(it, "benchmark-manifest.json").isFile) it.parentFile else it }
    }
    private fun referencePromptDate(prompt: String): String? =
        Regex("(?m)^Today Date: ([^\\r\\n]+)$").find(prompt)?.groupValues?.get(1)

    @Test fun llamaInt8MatchesPythonRuntime() = runBlocking {
        val root = File(fixtures() ?: return@runBlocking, "llama32-1b-onnx")
        val reference = Json.parseToJsonElement(File(root, "quality-reference.json").readText()).jsonObject
        // The Python quality reference used the same dated prompt as the recorded chat fixtures.
        val referenceDates = Json.parseToJsonElement(File(root, "chat-reference.json").readText()).jsonArray.map {
            checkNotNull(referencePromptDate(it.jsonObject.getValue("prompt").jsonPrimitive.content)) {
                "The Llama reference must record its prompt date"
            }
        }.distinct()
        val referenceDate = referenceDates.single()
        val engine = OrtTextRuntime(promptDate = { referenceDate })
        try {
            engine.load(File(root, "onnx/model_int8.onnx").path, OnnxRuntimeConfig(threads = 1))
            val ids = engine.generate(listOf(OnnxChatMessage("system", "You are a helpful assistant."),
                OnnxChatMessage("user", "Explain why the sky is blue in clear, simple language.")), 48).toList().map { it.id }
            assertEquals(reference.getValue("ids").jsonArray.map { it.jsonPrimitive.int }, ids)
        } finally { engine.unload() }
    }
    @Test fun chatProfilesMatchOfficialJinjaTemplates() = runBlocking {
        val base = fixtures() ?: return@runBlocking
        val roots = base.listFiles()!!.filter { File(it, "chat-reference.json").isFile && File(it, "onnx/model_int8.onnx").isFile }
        assertTrue(roots.size >= 5, "Generate references for all five ONNX fixtures first")
        roots.forEach { root ->
            var referenceDate: String? = null
            val engine = OrtTextRuntime(promptDate = {
                checkNotNull(referenceDate) { "A dated template must have a date in its recorded reference prompt" }
            })
            try {
                engine.load(File(root, "onnx/model_int8.onnx").path, OnnxRuntimeConfig(threads = 1))
                Json.parseToJsonElement(File(root, "chat-reference.json").readText()).jsonArray.forEach { case ->
                    val value = case.jsonObject
                    referenceDate = referencePromptDate(value.getValue("prompt").jsonPrimitive.content)
                    val messages = value.getValue("messages").jsonArray.map { message ->
                        OnnxChatMessage(message.jsonObject.getValue("role").jsonPrimitive.content, message.jsonObject.getValue("content").jsonPrimitive.content)
                    }
                    assertContentEquals(value.getValue("ids").jsonArray.map { it.jsonPrimitive.int }.toIntArray(), engine.tokenize(messages), root.name)
                }
            } finally { engine.unload() }
        }
    }
    @Test fun allDownloadedTokenizerReferencesMatch() {
        val base = fixtures() ?: return
        val roots = base.listFiles()!!.filter { File(it, "tokenizer-reference.json").isFile }
        assertTrue(roots.size >= 5, "Generate references for all five ONNX fixtures first")
        roots.forEach { root ->
            val tokenizer = HuggingFaceBpeTokenizer(File(root, "tokenizer.json").readText())
            Json.parseToJsonElement(File(root, "tokenizer-reference.json").readText()).jsonArray.forEach { case ->
                val value = case.jsonObject
                assertContentEquals(value.getValue("ids").jsonArray.map { it.jsonPrimitive.int }.toIntArray(),
                    tokenizer.encode(value.getValue("text").jsonPrimitive.content), root.name + ": " + value["text"])
            }
        }
    }
    @Test fun tokenizerAndGreedyGenerationMatchReference() = runBlocking {
        val root = File(fixtures() ?: return@runBlocking, "smollm2-135m-onnx")
        val reference = Json.parseToJsonElement(File(root, "reference.json").readText()).jsonObject
        val tokenizer = HuggingFaceBpeTokenizer(File(root, "tokenizer.json").readText())
        reference.getValue("cases").jsonArray.forEach { case ->
            val value = case.jsonObject
            assertContentEquals(value.getValue("ids").jsonArray.map { it.jsonPrimitive.int }.toIntArray(),
                tokenizer.encode(value.getValue("text").jsonPrimitive.content))
        }
        val engine = createOnnxTextRuntime()
        try {
            engine.load(File(root, "onnx/model_int8.onnx").path, OnnxRuntimeConfig())
            val tokens = engine.generate(listOf(
                OnnxChatMessage("system", "You are a helpful assistant."),
                OnnxChatMessage("user", "Explain why the sky is blue in clear, simple language."),
            ), 32).toList()
            assertEquals(reference.getValue("generated_ids").jsonArray.map { it.jsonPrimitive.int }, tokens.map { it.id })
            assertEquals(reference.getValue("text").jsonPrimitive.content, tokens.joinToString("") { it.text })
        } finally { engine.unload() }
    }
}
