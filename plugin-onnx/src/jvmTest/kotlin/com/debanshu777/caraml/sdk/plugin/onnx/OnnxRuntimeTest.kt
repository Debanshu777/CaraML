package com.debanshu777.caraml.sdk.plugin.onnx

import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.*

class OnnxRuntimeTest {
    private fun fixture() = File(checkNotNull(javaClass.getResource("/tiny-onnx/model.onnx")).toURI())
    private fun prompt(text: String) = listOf(OnnxChatMessage("user", text))

    @Test fun prefillDecodeCancellationAndReloadReleaseNativeResources() = runBlocking {
        val engine = createOnnxTextRuntime()
        try {
            engine.load(fixture().path, OnnxRuntimeConfig(threads = 1))
            assertEquals(512, engine.contextLimit)
            assertEquals("abc", engine.generate(prompt("`"), 3).toList().joinToString("") { it.text })
            assertEquals(OnnxStopReason.TOKEN_LIMIT, engine.stopReason)
            // Cross the 128-token prefill boundary and consume logits from the last chunk.
            assertEquals("abc", engine.generate(prompt("x".repeat(299) + "`"), 3).toList().joinToString("") { it.text })
            assertEquals(303, engine.contextUsed)
            engine.generate(prompt("`"), 100).onEach { engine.cancel() }.toList()
            assertEquals(OnnxStopReason.CANCELLED, engine.stopReason)
            assertEquals("a", engine.generate(prompt("`"), 1).toList().single().text)
            assertFailsWith<IllegalArgumentException> { engine.generate(prompt("a".repeat(512)), 1).toList() }
            engine.unload()
            assertEquals(0, engine.contextLimit)
            assertEquals(0, engine.contextUsed)
            engine.load(fixture().path, OnnxRuntimeConfig(provider = OnnxExecutionProvider.COREML))
            assertTrue(engine.runtimeDescription.contains("; CPU;"))
            assertEquals("a", engine.generate(prompt("`"), 1).toList().single().text)
        } finally { engine.unload() }
    }

    @Test fun missingSidecarsAndMalformedGraphsFailBeforeInference() = runBlocking {
        val root = kotlin.io.path.createTempDirectory("onnx-invalid").toFile()
        val engine = createOnnxTextRuntime()
        try {
            fixture().copyTo(File(root, "model.onnx"))
            assertFailsWith<IllegalStateException> { engine.load(File(root, "model.onnx").path, OnnxRuntimeConfig()) }
            File(root, "model.onnx").writeBytes(byteArrayOf(0x3a, 0x7f)) // truncated graph field
            assertFails { engine.load(File(root, "model.onnx").path, OnnxRuntimeConfig()) }
            assertEquals(0, engine.contextLimit)
        } finally { engine.unload(); root.deleteRecursively() }
    }

    @Test fun utf8CharactersCanSpanTokensWithoutReplacementCharacters() {
        val tokenizer = HuggingFaceBpeTokenizer(File(fixture().parentFile, "tokenizer.json").readText())
        val ids = tokenizer.encode("🌍")
        assertEquals("", tokenizer.decode(ids.take(1)))
        assertEquals("", tokenizer.decode(ids.take(3)))
        assertEquals("🌍", tokenizer.decode(ids.toList()))
    }

    @Test fun externalDataCannotEscapeOrDependOnAnUnownedFile() {
        val root = kotlin.io.path.createTempDirectory("onnx-external").toFile()
        fun field(tag: Int, data: ByteArray): ByteArray {
            require(data.size < 128)
            return byteArrayOf((tag * 8 + 2).toByte(), data.size.toByte()) + data
        }
        fun graph(key: String, value: String) = field(7, field(5, field(13,
            field(1, key.toByteArray()) + field(2, value.toByteArray()))))
        val file = File(root, "model.onnx")
        try {
            for (path in listOf("../secret", "/absolute", "weights.bin", "model.onnx_data")) {
                file.writeBytes(graph("location", path))
                assertFails { validateOnnxExternalData(file) }
            }
            File(root, "model.onnx_data").writeBytes(byteArrayOf(1))
            file.writeBytes(graph("location", "model.onnx_data"))
            validateOnnxExternalData(file)
            file.writeBytes(graph("basepath", "/elsewhere"))
            assertFails { validateOnnxExternalData(file) }
        } finally { root.deleteRecursively() }
    }
}
