package com.debanshu777.caraml.benchmark

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import com.debanshu777.caraml.sdk.plugin.diffusion.bridge.DiffusionModelConfig
import com.debanshu777.caraml.sdk.plugin.diffusion.bridge.DiffusionPreflightResult
import com.debanshu777.caraml.sdk.plugin.diffusion.bridge.DiffusionRunner
import com.debanshu777.caraml.sdk.plugin.diffusion.bridge.DiffusionRuntimeBackend
import com.debanshu777.caraml.sdk.plugin.diffusion.bridge.ImageGenParams
import com.debanshu777.caraml.sdk.plugin.diffusion.bridge.SampleMethod
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Debug-only, shell-invoked measurement through the production diffusion JNI bridge. */
class DiffusionGenerationBenchmark : Instrumentation() {
    private var options = Bundle()

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        options = arguments ?: Bundle()
        start()
    }

    override fun onStart() {
        var foreground: Activity? = null
        val runner = DiffusionRunner()
        var result = Activity.RESULT_CANCELED
        try {
            waitForIdleSync()
            require(options.keySet().all { it in setOf(
                "model", "backend", "threads", "steps", "runs", "size", "flash", "mmap",
                "conv", "tiling", "wtype", "offload", "sampler", "cfg", "tag", "timeout", "prediction", "scheduler", "vae_cpu", "quantized", "preview",
                "max_vram", "segmented", "prefetch",
            ) })
            fun number(key: String, default: Int, range: IntRange): Int =
                (options.getString(key)?.toInt() ?: default).also { require(it in range) }
            val model = options.getString("model") ?: "sd-turbo"
            require(model in setOf("sd15", "sd-turbo", "sdxl-base", "sdxl-turbo", "z-image-turbo"))
            val quantized = number("quantized", 0, 0..1) == 1
            require(!quantized || model == "sdxl-turbo")
            val preview = number("preview", 0, 0..1) == 1
            require(!preview || model in setOf("sd15", "sd-turbo"))
            val tag = (options.getString("tag") ?: "trial").also { require(it.matches(Regex("[A-Za-z0-9_-]{1,64}"))) }
            val backend = DiffusionRuntimeBackend.valueOf(options.getString("backend") ?: "CPU")
            require(backend == DiffusionRuntimeBackend.CPU || backend == DiffusionRuntimeBackend.VULKAN)
            val threads = number("threads", -1, -1..8).also { require(it != 0) }
            val size = number("size", 512, 128..1024).also { require(it % 64 == 0) }
            val steps = number("steps", if (model == "z-image-turbo") 8 else if ("turbo" in model) 4 else 20, 1..50)
            val runs = number("runs", 1, 1..5)
            val timeout = number("timeout", 900, 30..1800)
            val cfg = (options.getString("cfg")?.toFloat() ?: if ("turbo" in model) 1f else 7f)
                .also { require(it.isFinite() && it in 1f..15f) }
            val sampler = number("sampler", if (model == "z-image-turbo") 0 else 1, 0..11)
            val root = File(targetContext.filesDir, "diffusion-benchmark").canonicalFile
            fun path(name: String) = File(root, name).canonicalFile.also {
                require(it.parentFile == root && it.isFile)
            }.path
            val config = DiffusionModelConfig(
                modelPath = path(when (model) {
                    "sdxl-turbo" -> if (quantized) "sdxl-turbo-q4_0.gguf" else "sdxl-turbo.safetensors"
                    else -> "$model.gguf"
                }),
                vaePath = when (model) {
                    "z-image-turbo" -> path("flux-vae.safetensors")
                    else -> ""
                },
                llmPath = if (model == "z-image-turbo") path("qwen3-4b.gguf") else "",
                taesdPath = if (preview) path("taesd.safetensors") else "",
                runtimeBackend = backend,
                offloadToCpu = number("offload", 1, 0..1) == 1,
                keepClipOnCpu = true,
                keepVaeOnCpu = number("vae_cpu", 1, 0..1) == 1,
                diffusionFlashAttn = number("flash", 1, 0..1) == 1,
                enableMmap = number("mmap", 0, 0..1) == 1,
                diffusionConvDirect = number("conv", 0, 0..1) == 1,
                vaeTiling = number("tiling", 0, 0..1) == 1,
                wtype = number("wtype", -1, -1..8),
                prediction = number("prediction", if (model == "sd-turbo") 0 else -1, -1..4),
                samplingScheduler = number("scheduler", -1, -1..6),
                maxVram = options.getString("max_vram")?.also {
                    require(it.toDouble().let { value -> value.isFinite() && value > 0 && value <= 32 })
                }.orEmpty(),
                segmentedCompute = number("segmented", 0, 0..1) == 1,
                prefetch = number("prefetch", 0, 0..1) == 1,
                nThreads = threads,
            )
            foreground = startActivitySync(Intent(targetContext, PerformanceForegroundActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            runOnMainSync { foreground!!.window.attributes = foreground!!.window.attributes.apply { screenBrightness = 0.1f } }
            val screen = foreground as PerformanceForegroundActivity
            val foregroundDeadline = SystemClock.elapsedRealtime() + 10_000
            while (!screen.validationResumed || !screen.validationFocused) {
                check(SystemClock.elapsedRealtime() < foregroundDeadline)
                Thread.sleep(50)
            }
            val power = targetContext.getSystemService(PowerManager::class.java)
            fun thermal() = power.currentThermalStatus
            report(JSONObject().put("event", "config").put("model", model).put("tag", tag)
                .put("backend", backend).put("threads", threads).put("size", size).put("steps", steps)
                .put("cfg", cfg).put("sampler", sampler).put("flash", config.diffusionFlashAttn)
                .put("mmap", config.enableMmap).put("conv", config.diffusionConvDirect)
                .put("offload", config.offloadToCpu).put("tiling", config.vaeTiling).put("wtype", config.wtype)
                .put("prediction", config.prediction)
                .put("scheduler", config.samplingScheduler)
                .put("vae_cpu", config.keepVaeOnCpu)
                .put("quantized", quantized)
                .put("preview", preview)
                .put("max_vram", config.maxVram)
                .put("segmented", config.segmentedCompute).put("prefetch", config.prefetch)
                .put("thermal", thermal()).put("foreground", screen.validationFocused))
            runner.initialize(targetContext.applicationInfo.nativeLibraryDir)
            runner.backendCapabilities().forEach {
                report(JSONObject().put("event", "backend_available").put("kind", it.kind)
                    .put("identity", it.deviceIdentity).put("free_bytes", it.freeBytes).put("total_bytes", it.totalBytes))
            }
            val fit = runner.preflightModel(config)
            check(fit is DiffusionPreflightResult.Fit) { "Preflight: ${fit::class.simpleName}" }
            report(JSONObject().put("event", "preflight").put("architecture", fit.report.architecture)
                .put("components", JSONArray().apply {
                    fit.report.components.forEach { component -> put(JSONObject()
                        .put("role", component.subdivisionRole).put("bytes", component.parameterBytes)
                        .put("runtime", component.runtimePlacement).put("backend_mask", component.runtimeBackendMask)
                        .put("parameters", component.parameterPlacement)) }
                }))
            val loadStart = SystemClock.elapsedRealtimeNanos()
            check(runner.loadModel(config)) { "Native model load failed" }
            report(JSONObject().put("event", "load").put("ms", elapsedMs(loadStart))
                .put("version", runner.modelVersion()).put("thermal", thermal()))
            repeat(runs) { run ->
                val start = SystemClock.elapsedRealtimeNanos()
                val active = AtomicBoolean(true)
                val timedOut = AtomicBoolean(false)
                val progress = JSONArray()
                val thermalStart = thermal()
                val initialProgress = runner.getStepProgress()[0]
                val monitor = thread(name = "diffusion-benchmark-progress", isDaemon = true) {
                    var previous = initialProgress
                    while (active.get()) {
                        val state = runner.getStepProgress()
                        if (state[0] < previous) previous = state[0]
                        if (state[0] > previous && state[1] > 0) {
                            previous = state[0]
                            val observation = JSONObject().put("step", state[0]).put("total", state[1])
                                .put("elapsed_ms", elapsedMs(start)).put("thermal", thermal())
                            synchronized(progress) { progress.put(observation) }
                            report(JSONObject(observation.toString()).put("event", "progress").put("run", run))
                        }
                        if (elapsedMs(start) > timeout * 1000L) {
                            timedOut.set(true)
                            runner.cancelGeneration()
                        }
                        Thread.sleep(100)
                    }
                }
                var generationMs: Double
                val image = try {
                    runner.txt2Img(ImageGenParams(
                        prompt = "A red ceramic teapot on a wooden table by a window, soft morning light, detailed photograph",
                        width = size, height = size, steps = steps, cfgScale = cfg, seed = 42,
                        sampleMethod = SampleMethod.fromValue(sampler),
                    )).also { generationMs = elapsedMs(start) }
                } finally {
                    active.set(false)
                    monitor.join()
                }
                if (timedOut.get() || image == null) {
                    report(JSONObject().put("event", "generation_failed").put("run", run)
                        .put("elapsed_ms", generationMs).put("timed_out", timedOut.get()).put("progress", progress))
                }
                check(!timedOut.get()) { "Generation timed out" }
                check(image != null) { "Native generation failed" }
                val bitmap = checkNotNull(BitmapFactory.decodeByteArray(image, 0, image.size))
                check(bitmap.width == size && bitmap.height == size)
                bitmap.recycle()
                val output = File(root, "$tag-$run.png")
                output.writeBytes(image)
                val hash = MessageDigest.getInstance("SHA-256").digest(image).joinToString("") { "%02x".format(it) }
                report(JSONObject().put("event", "generation").put("run", run).put("ms", generationMs)
                    .put("steps", steps).put("progress", progress).put("thermal_start", thermalStart)
                    .put("thermal_end", thermal()).put("png_bytes", image.size).put("sha256", hash)
                    .put("output", output.name).put("pss_bytes", Debug.MemoryInfo().also(Debug::getMemoryInfo).totalPss.toLong() * 1024))
            }
            result = Activity.RESULT_OK
        } catch (failure: Exception) {
            report(JSONObject().put("event", "error").put("type", failure.javaClass.simpleName)
                .put("message", failure.message?.take(240)))
        } finally {
            runCatching { runner.release() }
            foreground?.let { runOnMainSync { it.finish() } }
            finish(result, Bundle())
        }
    }

    private fun elapsedMs(start: Long) = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
    private fun report(value: JSONObject) = sendStatus(0, Bundle().apply { putString("stream", "CARAML_DIFFUSION $value\n") })
}
