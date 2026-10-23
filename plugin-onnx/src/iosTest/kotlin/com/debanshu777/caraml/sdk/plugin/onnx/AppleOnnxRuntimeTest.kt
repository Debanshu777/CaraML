@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.debanshu777.caraml.sdk.plugin.onnx

import kotlinx.cinterop.*
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.posix.*
import kotlin.io.encoding.Base64
import kotlin.test.*
import kotlin.time.TimeSource

class AppleOnnxRuntimeTest {
    @Test fun canonicalNormalizationComposesWithoutLosingOtherUnicode() {
        assertEquals("é café 🌍 नमस्ते", normalizeOnnxText("e\u0301 café 🌍 नमस्ते"))
    }
    @Test fun optionalFiveModelCpuMeasurementsOnApple() = runBlocking {
        val base = getenv("CARAML_ONNX_FIXTURE_DIR")?.toKString() ?: return@runBlocking
        val runtime = createOnnxTextRuntime()
        try {
            for (name in listOf("smollm2-135m", "smollm2-360m", "qwen25-05b", "qwen3-06b", "llama32-1b-q4")) {
                val graph = if (name.endsWith("q4")) "model_q4.onnx" else "model_int8.onnx"
                for (threads in listOf(1, 4)) {
                    val loadStart = TimeSource.Monotonic.markNow()
                    runtime.load("$base/$name-onnx/onnx/$graph", OnnxRuntimeConfig(threads = threads, provider = OnnxExecutionProvider.CPU))
                    val loadMs = loadStart.elapsedNow().inWholeNanoseconds / 1e6
                    repeat(4) { iteration ->
                        val start = TimeSource.Monotonic.markNow()
                        var firstMs = 0.0
                        var lastMs = 0.0
                        var count = 0
                        val text = StringBuilder()
                        runtime.generate(listOf(OnnxChatMessage("system", "You are a helpful assistant."),
                            OnnxChatMessage("user", "Explain why the sky is blue in clear, simple language.")), 64).collect { token ->
                            lastMs = start.elapsedNow().inWholeNanoseconds / 1e6
                            if (count++ == 0) firstMs = lastMs
                            text.append(token.text)
                        }
                        assertTrue(count > 0 && text.isNotBlank(), "$name returned no text")
                        val speed = if (count > 1) (count - 1) * 1000 / (lastMs - firstMs) else 0.0
                        println("CARAML_APPLE_ONNX " + buildJsonObject {
                            put("model", name); put("threads", threads); put("iteration", iteration); put("tokens", count)
                            put("loadMs", loadMs); put("ttftMs", firstMs); put("decodeTokensPerSecond", speed)
                            put("text", text.toString()); put("runtime", runtime.runtimeDescription)
                        })
                    }
                    runtime.unload()
                }
            }
        } finally { runtime.unload() }
    }

    @Test fun cpuPrefillCancellationAndReloadOwnTheirNativeResources() = runBlocking {
        val root = NSTemporaryDirectory() + "onnx-" + NSUUID().UUIDString
        check(mkdir(root, 448u) == 0)
        for ((name, encoded) in appleOnnxFixtures) {
            val bytes = Base64.decode(encoded)
            val file = checkNotNull(fopen("$root/$name", "wb"))
            try { bytes.usePinned { check(fwrite(it.addressOf(0), 1u, bytes.size.toULong(), file).toInt() == bytes.size) } }
            finally { fclose(file) }
        }
        val runtime = createOnnxTextRuntime()
        try {
            runtime.load("$root/model.onnx", OnnxRuntimeConfig(threads = 1, provider = OnnxExecutionProvider.CPU))
            assertEquals("abc", runtime.generate(listOf(OnnxChatMessage("user", "x".repeat(299) + "`")), 3).toList().joinToString("") { it.text })
            assertEquals(303, runtime.contextUsed)
            runtime.generate(listOf(OnnxChatMessage("user", "`")), 100).onEach { runtime.cancel() }.toList()
            assertEquals(OnnxStopReason.CANCELLED, runtime.stopReason)
            runtime.unload()
            assertEquals(0, runtime.contextLimit)
            runtime.load("$root/model.onnx", OnnxRuntimeConfig(threads = 1, provider = OnnxExecutionProvider.COREML))
            assertEquals("a", runtime.generate(listOf(OnnxChatMessage("user", "`")), 1).toList().single().text)
        } finally {
            runtime.unload()
            NSFileManager.defaultManager.removeItemAtPath(root, null)
        }
    }

    @Test fun optionalRealModelReferencesMatchOnApple() = runBlocking {
        val base = getenv("CARAML_ONNX_FIXTURE_DIR")?.toKString() ?: return@runBlocking
        for (name in listOf("smollm2-135m", "smollm2-360m", "qwen25-05b", "qwen3-06b", "llama32-1b")) {
            val root = "$base/$name-onnx"
            val codec = HuggingFaceBpeTokenizer(readOnnxMetadata("$root/tokenizer.json"))
            val cases = Json.parseToJsonElement(readOnnxMetadata("$root/tokenizer-reference.json")).jsonArray
            for (case in cases) {
                val value = case.jsonObject
                assertContentEquals(value.getValue("ids").jsonArray.map { it.jsonPrimitive.int }.toIntArray(),
                    codec.encode(value.getValue("text").jsonPrimitive.content), name)
            }
        }
        val runtime = createOnnxTextRuntime()
        try {
            val root = "$base/smollm2-135m-onnx"
            val expected = Json.parseToJsonElement(readOnnxMetadata("$root/reference.json")).jsonObject
            for (provider in listOf(OnnxExecutionProvider.CPU, OnnxExecutionProvider.COREML)) {
                runtime.load("$root/onnx/model_int8.onnx", OnnxRuntimeConfig(threads = 1, provider = provider))
                val tokens = runtime.generate(listOf(OnnxChatMessage("system", "You are a helpful assistant."),
                    OnnxChatMessage("user", "Explain why the sky is blue in clear, simple language.")), 32).toList()
                assertEquals(expected.getValue("generated_ids").jsonArray.map { it.jsonPrimitive.int }, tokens.map { it.id }, runtime.runtimeDescription)
                println("Apple ONNX reference passed: ${runtime.runtimeDescription}")
                runtime.unload()
            }
        } finally { runtime.unload() }
    }

}
