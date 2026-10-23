package com.debanshu777.caraml.benchmark

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.widget.TextView
import android.os.*
import com.debanshu777.caraml.sdk.plugin.onnx.*
import com.debanshu777.caraml.sdk.plugin.llama.bridge.LlamaRunner
import com.debanshu777.caraml.sdk.plugin.llama.bridge.NativeRunnerConfig
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray

/** Debug-only shell harness. Models are confined to the application's benchmark directory. */
class OnnxGgufBenchmark : Instrumentation() {
    private var options = Bundle()
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        options = arguments ?: Bundle()
        start()
    }

    override fun onStart() = runBlocking {
        var foreground: Activity? = null
        val onnx = createOnnxTextRuntime()
        var llama: LlamaRunner? = null
        var resultCode = Activity.RESULT_CANCELED
        try {
            waitForIdleSync()
            require(options.keySet().all { it in setOf("model", "provider", "threads", "tokens", "runs", "profile", "gpu", "prompt", "portable", "basic") })
            fun number(key: String, default: Int, range: IntRange) = (options.getString(key)?.toInt() ?: default).also { require(it in range) }
            val threads = number("threads", 4, 1..8)
            val tokens = number("tokens", 64, 8..512)
            val runs = number("runs", 3, 1..10)
            val gpu = number("gpu", 0, -1..99)
            val root = File(targetContext.getExternalFilesDir(null), "benchmarks").canonicalFile
            val model = File(root, requireNotNull(options.getString("model"))).canonicalFile
            require(model.toPath().startsWith(root.toPath()) && model.isFile)
            foreground = startActivitySync(Intent(targetContext, PerformanceForegroundActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            runOnMainSync {
                foreground!!.window.attributes = foreground!!.window.attributes.apply { screenBrightness = 0.1f }
                foreground!!.setContentView(TextView(foreground).apply {
                    text = "CaraML ONNX / GGUF benchmark"
                    setTextColor(Color.LTGRAY); setBackgroundColor(Color.BLACK)
                })
            }
            val screen = foreground as PerformanceForegroundActivity
            withTimeout(10_000) {
                while (!screen.validationResumed || !screen.validationFocused) delay(50)
            }
            val power = targetContext.getSystemService(PowerManager::class.java)
            fun thermal() = power.currentThermalStatus
            fun batteryTemperature() = targetContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)?.let { it / 10.0 }
            fun pss() = Debug.MemoryInfo().also(Debug::getMemoryInfo).totalPss.toLong() * 1024
            fun report(value: JSONObject) = sendStatus(0, Bundle().apply { putString("stream", "CARAML_BENCH $value\n") })
            report(JSONObject().put("event", "environment").put("foreground_resumed", screen.validationResumed)
                .put("foreground_focused", screen.validationFocused).put("display_interactive", power.isInteractive)
                .put("battery_plugged", targetContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                    ?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)))
            val provider = OnnxExecutionProvider.valueOf(options.getString("provider") ?: "CPU")
            val prompt = options.getString("prompt") ?: "Explain why the sky is blue in clear, simple language."
            val isOnnx = model.extension == "onnx"
            val loadStart = SystemClock.elapsedRealtimeNanos()
            if (isOnnx) {
                onnx.load(model.path, OnnxRuntimeConfig(
                    contextTokens = 2048, threads = threads, provider = provider,
                    providerLibraryDirectory = targetContext.applicationInfo.nativeLibraryDir,
                    portableKernels = options.getString("portable") == "1",
                    basicOptimizations = options.getString("basic") == "1",
                    profilePath = if (options.getString("profile") == "1") File(root, "ort-profile").path else null,
                ))
            } else {
                llama = LlamaRunner().also { engine ->
                    engine.initialize(targetContext.applicationInfo.nativeLibraryDir)
                    check(engine.loadModel(model.path, NativeRunnerConfig(
                        nCtx = 2048, nThreads = threads, nThreadsBatch = threads,
                        nBatch = 256, nUbatch = 64, nGpuLayers = gpu,
                        temperature = 0f, offloadKqv = gpu != 0, autoFit = false,
                    ))) { "GGUF load failed" }
                }
            }
            val loadMs = (SystemClock.elapsedRealtimeNanos() - loadStart) / 1e6
            report(JSONObject().put("event", "load").put("model", model.relativeTo(root).path)
                .put("load_ms", loadMs).put("pss_bytes", pss()).put("thermal", thermal())
                .put("battery_temperature_c", batteryTemperature())
                .put("runtime", if (isOnnx) onnx.runtimeDescription else "llama.cpp gpuLayers=${llama!!.getGpuLayers()}; $threads threads"))
            if (isOnnx) report(JSONObject().put("event", "prompt_tokens").put("ids", JSONArray(onnx.tokenize(listOf(
                OnnxChatMessage("system", "You are a helpful assistant."), OnnxChatMessage("user", prompt),
            )).toList())))
            repeat(runs + 1) { iteration ->
                check(screen.validationResumed && screen.validationFocused) { "Benchmark lost foreground focus" }
                val startThermal = thermal()
                val startTemperature = batteryTemperature()
                val peakPss = AtomicLong(pss())
                val remainedForeground = AtomicBoolean(true)
                val sampler = launch(Dispatchers.IO) {
                    while (isActive) {
                        if (!screen.validationResumed || !screen.validationFocused || !power.isInteractive) remainedForeground.set(false)
                        peakPss.accumulateAndGet(pss(), ::maxOf)
                        delay(100)
                    }
                }
                var count = 0
                var firstNanos = 0L
                var lastNanos = 0L
                val text = StringBuilder()
                val start = SystemClock.elapsedRealtimeNanos()
                fun token(value: String) {
                    val now = SystemClock.elapsedRealtimeNanos()
                    if (count == 0) firstNanos = now
                    lastNanos = now
                    count++
                    text.append(value)
                }
                try {
                    withTimeout(180_000) {
                        if (isOnnx) {
                            onnx.generate(listOf(OnnxChatMessage("system", "You are a helpful assistant."), OnnxChatMessage("user", prompt)), tokens).collect { token(it.text) }
                        } else {
                            withContext(Dispatchers.Default) {
                                val engine = checkNotNull(llama)
                                engine.clearContext()
                                check(engine.processSystemPrompt("You are a helpful assistant.") == 0)
                                check(engine.processUserPrompt(prompt, tokens) == 0)
                                while (count < tokens) {
                                    ensureActive()
                                    val piece = engine.nextToken() ?: break
                                    token(piece)
                                }
                                engine.finalizeGeneration()
                            }
                        }
                    }
                } finally { sampler.cancelAndJoin() }
                val elapsed = SystemClock.elapsedRealtimeNanos() - start
                check(remainedForeground.get() && screen.validationResumed && screen.validationFocused && power.isInteractive) {
                    "Benchmark lost foreground focus during generation"
                }
                check(count > 0 && text.isNotBlank()) { "Model produced no text" }
                report(JSONObject().put("event", "generation").put("model", model.relativeTo(root).path)
                    .put("warmup", iteration == 0).put("iteration", iteration).put("tokens", count)
                    .put("ttft_ms", (firstNanos - start) / 1e6).put("elapsed_ms", elapsed / 1e6)
                    .put("decode_tokens_per_second", if (count > 1 && lastNanos > firstNanos) (count - 1) * 1e9 / (lastNanos - firstNanos) else 0)
                    .put("end_to_end_tokens_per_second", count * 1e9 / elapsed).put("peak_pss_bytes", peakPss.get())
                    .put("foreground_verified", true)
                    .put("thermal_start", startThermal).put("thermal_end", thermal()).put("text", text.toString()))
                report(JSONObject().put("event", "temperature").put("iteration", iteration)
                    .put("start_c", startTemperature).put("end_c", batteryTemperature()))
            }
            resultCode = Activity.RESULT_OK
        } catch (failure: Throwable) {
            android.util.Log.e("OnnxBenchmark", "Benchmark failed", failure)
            val cause = generateSequence(failure) { it.cause }.last()
            sendStatus(0, Bundle().apply { putString("stream", "CARAML_BENCH ${JSONObject().put("event", "error").put("type", failure.javaClass.simpleName).put("message", cause.toString())}\n") })
        } finally {
            onnx.unload()
            llama?.unloadModel()
            foreground?.let { activity -> runOnMainSync { activity.finish() } }
            finish(resultCode, Bundle())
        }
    }
}
