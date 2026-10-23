@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.debanshu777.caraml.benchmark

import com.debanshu777.caraml.sdk.plugin.llama.bridge.*
import com.debanshu777.caraml.sdk.plugin.onnx.*
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import platform.Foundation.*
import platform.posix.*
import kotlin.time.TimeSource

/** Compiled only with -PiosBenchmark=true. Inputs address fixed fixtures in this app's Documents. */
fun startAppleBenchmark(specification: String, completion: (Boolean) -> Unit) {
    CoroutineScope(Dispatchers.Default).launch {
        val success = runCatching {
            delay(2000) // Let the foreground scene settle before loading.
            val spec = Json.parseToJsonElement(specification).jsonObject
            val name = spec.getValue("model").jsonPrimitive.content
            require(name in listOf("smollm2-135m", "smollm2-360m", "qwen25-05b", "qwen3-06b", "llama32-1b"))
            val mode = spec.getValue("mode").jsonPrimitive.content
            require(mode == "onnx" || mode == "gguf")
            val threads = spec["threads"]?.jsonPrimitive?.int ?: 2
            require(threads in 1..4)
            val gpu = spec["gpu"]?.jsonPrimitive?.int ?: 0
            require(gpu == 0 || gpu == 99)
            val provider = OnnxExecutionProvider.valueOf(spec["provider"]?.jsonPrimitive?.content ?: "CPU")
            val lowMemory = spec["lowMemory"]?.jsonPrimitive?.booleanOrNull ?: false
            require(provider in listOf(OnnxExecutionProvider.CPU, OnnxExecutionProvider.COREML))
            val root = (NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).first() as String) + "/benchmarks"
            val output = "$root/result.jsonl"
            val file = checkNotNull(fopen(output, "w"))
            fclose(file)
            fun record(event: String, extra: JsonObjectBuilder.() -> Unit = {}) {
                val json = buildJsonObject {
                    put("event", event); put("model", name); put("mode", mode); put("threads", threads)
                    put("requestedGpuLayers", gpu); put("requestedProvider", provider.name)
                    put("lowMemory", lowMemory)
                    put("thermalState", NSProcessInfo.processInfo.thermalState.value.toLong())
                    put("physicalMemoryBytes", NSProcessInfo.processInfo.physicalMemory.toLong())
                    memScoped {
                        val usage = alloc<rusage>()
                        if (getrusage(RUSAGE_SELF, usage.ptr) == 0) put("peakResidentBytes", usage.ru_maxrss)
                    }
                    extra()
                }
                println("CARAML_APPLE_DEVICE $json")
                val bytes = "$json\n".encodeToByteArray()
                val sink = checkNotNull(fopen(output, "a"))
                try { bytes.usePinned { check(fwrite(it.addressOf(0), 1u, bytes.size.toULong(), sink).toInt() == bytes.size) } }
                finally { fclose(sink) }
            }
            record("START")
            // Nominal thermal state is required before each configuration, with bounded cooling.
            var attempts = 0
            while (NSProcessInfo.processInfo.thermalState != NSProcessInfoThermalState.NSProcessInfoThermalStateNominal && attempts++ < 180) delay(5000)
            check(NSProcessInfo.processInfo.thermalState == NSProcessInfoThermalState.NSProcessInfoThermalStateNominal) { "Device did not cool to nominal" }
            val system = "You are a helpful assistant."
            val prompt = "Explain why the sky is blue in clear, simple language."
            if (mode == "onnx") {
                val runtime = createOnnxTextRuntime()
                val suffix = if (name == "llama32-1b") "-q4" else ""
                val graph = if (suffix.isEmpty()) "model_int8.onnx" else "model_q4.onnx"
                val directory = "$root/$name$suffix-onnx"
                val path = "$directory/onnx/$graph"
                val config = OnnxRuntimeConfig(threads = threads, provider = provider, lowMemory = lowMemory)
                val messages = listOf(OnnxChatMessage("system", system), OnnxChatMessage("user", prompt))
                try {
                    val loadStart = TimeSource.Monotonic.markNow()
                    runtime.load(path, config)
                    val loadMs = loadStart.elapsedNow().inWholeNanoseconds / 1e6
                    record("LOAD") { put("loadMs", loadMs); put("runtime", runtime.runtimeDescription) }
                    var firstIds = emptyList<Int>()
                    repeat(4) { iteration ->
                        val start = TimeSource.Monotonic.markNow()
                        var first = 0.0; var last = 0.0
                        val tokens = runtime.generate(messages, 64).onEach {
                            last = start.elapsedNow().inWholeNanoseconds / 1e6
                            if (first == 0.0) first = last
                        }.toList()
                        check(tokens.isNotEmpty() && tokens.joinToString("") { it.text }.isNotBlank())
                        if (iteration == 0) firstIds = tokens.map { it.id }
                        check(tokens.map { it.id } == firstIds) { "Greedy runs differ" }
                        record("RUN") {
                            put("iteration", iteration); put("tokens", tokens.size); put("ttftMs", first)
                            put("decodeTokensPerSecond", (tokens.size - 1) * 1000 / (last - first))
                            put("text", tokens.joinToString("") { it.text }); put("tokenIds", JsonArray(tokens.map { JsonPrimitive(it.id) }))
                            put("runtime", runtime.runtimeDescription)
                        }
                    }
                    runtime.generate(messages, 128).onEach { runtime.cancel() }.toList()
                    check(runtime.stopReason == OnnxStopReason.CANCELLED)
                    runtime.unload()
                    check(runtime.contextLimit == 0)
                    runtime.load(path, config)
                    check(runtime.generate(messages, 3).toList().map { it.id } == firstIds.take(3))
                    record("LIFECYCLE_OK")
                } finally { runtime.unload() }
            } else {
                val runner = LlamaRunner()
                runner.initialize("")
                try {
                    check(runner.backendCapabilities().any { it.kind == NativeBackendKind.METAL }) { "Metal capability missing" }
                    val directory = "$root/$name-gguf"
                    val filename = NSFileManager.defaultManager.contentsOfDirectoryAtPath(directory, null)
                        ?.filterIsInstance<String>()?.single { it.endsWith(".gguf") }
                    checkNotNull(filename)
                    val path = "$directory/$filename"
                    val config = NativeRunnerConfig(nCtx = 2048, nThreads = threads, nThreadsBatch = threads,
                        nBatch = 256, nUbatch = 64, temperature = 0f, nGpuLayers = gpu, offloadKqv = gpu != 0, autoFit = false)
                    val loadStart = TimeSource.Monotonic.markNow()
                    check(runner.loadModel(path, config)) { "GGUF load failed" }
                    check((gpu == 0 && runner.getGpuLayers() == 0) || (gpu > 0 && runner.getGpuLayers() > 0))
                    record("LOAD") { put("loadMs", loadStart.elapsedNow().inWholeNanoseconds / 1e6)
                        put("actualGpuLayers", runner.getGpuLayers()); put("runtime", runner.engineVersion()) }
                    repeat(4) { iteration ->
                        runner.clearContext()
                        val start = TimeSource.Monotonic.markNow()
                        check(runner.processSystemPrompt(system) == 0)
                        check(runner.processUserPrompt(prompt, 64) == 0)
                        var first = 0.0; var last = 0.0; var count = 0
                        while (count < 64 && runner.nextToken() != null) {
                            last = start.elapsedNow().inWholeNanoseconds / 1e6
                            if (count++ == 0) first = last
                        }
                        runner.finalizeGeneration()
                        val text = runner.getContent() + runner.getReasoning()
                        check(count > 0 && text.isNotBlank())
                        record("RUN") {
                            put("iteration", iteration); put("tokens", count); put("ttftMs", first)
                            put("decodeTokensPerSecond", (count - 1) * 1000 / (last - first))
                            put("text", text); put("actualGpuLayers", runner.getGpuLayers())
                        }
                    }
                    runner.clearContext()
                    check(runner.processUserPrompt("Hello", 128) == 0)
                    runner.cancelGenerate(); runner.nextToken(); runner.finalizeGeneration(); runner.clearContext()
                    check(runner.getContextUsed() == 0)
                    runner.unloadModel()
                    check(runner.loadModel(path, config))
                    check(runner.processUserPrompt("Hello", 3) == 0)
                    check(runner.nextToken() != null)
                    runner.finalizeGeneration()
                    record("LIFECYCLE_OK")
                } finally { runner.shutdown() }
            }
            record("COMPLETE")
        }.onFailure { println("CARAML_APPLE_DEVICE_ERROR ${it.stackTraceToString()}") }.isSuccess
        withContext(Dispatchers.Main) { completion(success) }
    }
}
