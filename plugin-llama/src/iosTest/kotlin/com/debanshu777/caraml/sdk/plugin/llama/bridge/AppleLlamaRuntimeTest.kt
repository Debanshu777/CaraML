@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.debanshu777.caraml.sdk.plugin.llama.bridge

import kotlinx.cinterop.toKString
import platform.Foundation.NSFileManager
import platform.posix.getenv
import kotlin.io.encoding.Base64
import kotlin.test.*
import kotlin.time.TimeSource

/** Opt-in real checkpoints: SIMCTL_CHILD_CARAML_GGUF_FIXTURE_DIR=<build/benchmarks>. */
class AppleLlamaRuntimeTest {
    @Test fun fiveCheckpointsExerciseCpuAndMetalLifecycle() {
        val root = getenv("CARAML_GGUF_FIXTURE_DIR")?.toKString() ?: return
        val runner = LlamaRunner()
        runner.initialize("")
        try {
            assertTrue(runner.backendCapabilities().any { it.kind == NativeBackendKind.METAL },
                "The linked Apple GPU must be recognized as Metal")
            for (model in listOf("smollm2-135m", "smollm2-360m", "qwen25-05b", "qwen3-06b", "llama32-1b")) {
                val directory = "$root/$model-gguf"
                val filename = NSFileManager.defaultManager.contentsOfDirectoryAtPath(directory, null)
                    ?.filterIsInstance<String>()?.single { it.endsWith(".gguf") }
                assertNotNull(filename, "Missing fixture: $directory")
                for (gpu in listOf(0, 99)) {
                    val loadStart = TimeSource.Monotonic.markNow()
                    assertTrue(runner.loadModel("$directory/$filename", NativeRunnerConfig(
                        nCtx = 2048, nThreads = 4, nThreadsBatch = 4, nBatch = 256, nUbatch = 64,
                        temperature = 0f, nGpuLayers = gpu, offloadKqv = gpu != 0, autoFit = false,
                    )), "$model failed to load, requested GPU=$gpu")
                    val loadMs = loadStart.elapsedNow().inWholeNanoseconds / 1e6
                    assertEquals(2048, runner.getContextLimit())
                    if (gpu == 0) assertEquals(0, runner.getGpuLayers())
                    else assertTrue(runner.getGpuLayers() > 0, "$model requested Metal but no layers were offloaded")
                    repeat(4) { iteration ->
                        runner.clearContext()
                        val start = TimeSource.Monotonic.markNow()
                        assertEquals(0, runner.processSystemPrompt("You are a helpful assistant."))
                        assertEquals(0, runner.processUserPrompt("Explain why the sky is blue in clear, simple language.", 64))
                        var firstMs = 0.0
                        var lastMs = 0.0
                        var count = 0
                        while (count < 64 && runner.nextToken() != null) {
                            count++
                            lastMs = start.elapsedNow().inWholeNanoseconds / 1e6
                            if (count == 1) firstMs = lastMs
                        }
                        runner.finalizeGeneration()
                        val output = runner.getContent() + runner.getReasoning()
                        assertTrue(output.isNotBlank(), "$model returned no content or reasoning")
                        assertTrue(count > 0 && runner.getContextUsed() > 0)
                        val encoded = Base64.encode(output.encodeToByteArray())
                        val speed = if (count > 1) (count - 1) * 1000 / (lastMs - firstMs) else 0.0
                        println("CARAML_APPLE_GGUF {\"model\":\"$model\",\"requestedGpuLayers\":$gpu,\"actualGpuLayers\":${runner.getGpuLayers()},\"iteration\":$iteration,\"tokens\":$count,\"loadMs\":$loadMs,\"ttftMs\":$firstMs,\"decodeTokensPerSecond\":$speed,\"outputBase64\":\"$encoded\",\"engine\":\"${runner.engineVersion()}\"}")
                    }
                    runner.clearContext()
                    runner.processUserPrompt("Hello", 128)
                    runner.cancelGenerate()
                    runner.nextToken()
                    runner.finalizeGeneration()
                    runner.clearContext()
                    assertEquals(0, runner.getContextUsed())
                    // Shutdown must own final model/context cleanup, including Metal buffers.
                    runner.shutdown()
                    assertEquals(0, runner.getContextLimit())
                    runner.initialize("")
                }
            }
        } finally { runner.shutdown() }
    }
}
