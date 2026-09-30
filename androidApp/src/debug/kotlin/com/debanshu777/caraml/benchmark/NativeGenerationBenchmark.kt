package com.debanshu777.caraml.benchmark

import android.app.Activity
import android.app.Instrumentation
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import com.debanshu777.runner.LlamaRunner
import com.debanshu777.runner.NativeRunnerConfig
import com.debanshu777.runner.LlamaPreflightResult
import com.debanshu777.runner.LlamaLazyMode
import com.debanshu777.caraml.core.data.settings.SettingsRepository
import com.debanshu777.caraml.core.settings.initPreferencesDataStore
import com.debanshu777.caraml.core.recommendation.DeviceSnapshotProvider
import com.debanshu777.caraml.core.recommendation.InstalledModelLoadPreparation
import com.debanshu777.caraml.core.recommendation.InstalledModelLoadRequestResolver
import com.debanshu777.caraml.core.recommendation.InstalledModelWorkloadFactory
import com.debanshu777.caraml.core.recommendation.ModelAssessmentRepository
import com.debanshu777.caraml.core.recommendation.LlmRunPlan
import com.debanshu777.caraml.core.storage.localmodel.LocalModelRepository
import com.debanshu777.caraml.features.chat.domain.GenerationMode
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.koin.mp.KoinPlatform
import java.io.File
import java.security.MessageDigest

/** Shell-invoked, debug-only benchmark of the installed MiniCPM model and shipping JNI path. */
class NativeGenerationBenchmark : Instrumentation() {
    private var options = Bundle()

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        options = arguments ?: Bundle()
        start()
    }

    override fun onStart() {
        waitForIdleSync()
        val runner = LlamaRunner()
        try {
            val allowed = setOf("gpu", "threads", "tokens", "flash", "kv", "mask", "diagnose",
                "batch", "ubatch", "ctx", "outputs", "mmap", "batchThreads", "preflight", "autoFit", "verifyTurns")
            require(options.keySet().all { it in allowed })
            if (options.getString("diagnose") == "1") {
                diagnose()
                finish(Activity.RESULT_OK, Bundle())
                return
            }
            fun number(key: String, default: Int, range: IntRange): Int =
                (options.getString(key)?.toIntOrNull() ?: if (options.containsKey(key)) {
                    error("Invalid benchmark option")
                } else default).also { require(it in range) }
            val gpu = number("gpu", 0, -1..99)
            val threads = number("threads", 4, 1..8)
            val tokens = number("tokens", 768, 64..1024)
            val flash = number("flash", -1, -1..1)
            val kv = number("kv", 1, 1..8).also { require(it == 1 || it == 8) }
            val mask = options.getString("mask").orEmpty().also {
                require(it in setOf("", "4-7", "4-6", "7"))
            }
            val batch = number("batch", 256, 32..512)
            val ubatch = number("ubatch", 64, 32..batch)
            val context = number("ctx", 4096, 2048..8192)
            val outputs = number("outputs", 0, 0..1)
            val mmap = number("mmap", 1, 0..1) == 1
            val batchThreads = number("batchThreads", threads, 1..8)
            val preflight = number("preflight", 0, 0..1) == 1
            val autoFit = number("autoFit", if (gpu == -1) 1 else 0, 0..1) == 1
            val verifyTurns = number("verifyTurns", 0, 0..1) == 1
            val root = requireNotNull(targetContext.getExternalFilesDir(null)).canonicalFile
            val artifacts = File(root, "models/openbmb/MiniCPM5-2B-GGUF/.caraml-artifacts")
            val model = artifacts.listFiles().orEmpty().map {
                File(it, "MiniCPM5-2B-Q4_K_M.gguf")
            }.single { it.isFile }.canonicalFile
            require(model.toPath().startsWith(root.toPath()))
            require(model.length() == 1_561_318_368L)
            val power = targetContext.getSystemService(PowerManager::class.java)
            fun thermal() = if (Build.VERSION.SDK_INT >= 29) power.currentThermalStatus else -1
            fun report(value: String) = sendStatus(0, Bundle().apply { putString("stream", "$value\n") })
            val config = NativeRunnerConfig(
                nCtx = context, nThreads = threads, nThreadsBatch = batchThreads,
                nBatch = batch, nUbatch = ubatch, nGpuLayers = gpu,
                nOutputsMaxPerSequence = outputs, useMmap = mmap,
                lazyMode = if (mmap) LlamaLazyMode.AUTO else LlamaLazyMode.OFF,
                offloadKqv = gpu != 0, flashAttn = flash, typeK = kv, typeV = kv,
                temperature = 0f, autoFit = autoFit, cpuMask = mask, cpuMaskBatch = mask,
            )
            report("START gpu=$gpu threads=$threads batchThreads=$batchThreads flash=$flash kv=$kv mask=$mask ctx=$context batch=$batch ubatch=$ubatch outputs=$outputs mmap=$mmap thermal=${thermal()}")
            runner.initialize(targetContext.applicationInfo.nativeLibraryDir)
            if (preflight) {
                val fit = runner.preflightModel(model.path, config)
                check(fit is LlamaPreflightResult.Fit)
                fit.report.memoryPools.forEach { pool ->
                    report("MEMORY kind=${pool.kind} model=${pool.modelBytes} context=${pool.contextBytes} compute=${pool.computeBytes}")
                }
            }
            val loadStart = SystemClock.elapsedRealtimeNanos()
            check(runner.loadModel(model.path, config))
            report("LOAD ms=${(SystemClock.elapsedRealtimeNanos() - loadStart) / 1_000_000} gpu=${runner.getGpuLayers()} ctx=${runner.getContextLimit()}")
            check(runner.processSystemPrompt("You are a helpful assistant.") == 0)
            val prefillStart = SystemClock.elapsedRealtimeNanos()
            check(runner.processUserPrompt(
                "Explain dark matter in detail. Cover the observational evidence, proposed particles, alternatives, detection experiments, and open questions. Give a thorough explanation of each topic.",
                tokens,
            ) == 0)
            report("PREFILL ms=${(SystemClock.elapsedRealtimeNanos() - prefillStart) / 1_000_000}")
            val allocatedBefore = Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull() ?: 0L
            val gcBefore = Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull() ?: 0L
            val digest = MessageDigest.getInstance("SHA-256")
            var count = 0
            var nativeNanos = 0L
            val start = SystemClock.elapsedRealtimeNanos()
            var windowStart = start
            while (count < tokens) {
                val tick = SystemClock.elapsedRealtimeNanos()
                val token = runner.nextToken() ?: break
                nativeNanos += SystemClock.elapsedRealtimeNanos() - tick
                digest.update(token.toByteArray(Charsets.UTF_8))
                runner.getReasoningDelta()
                runner.getContentDelta()
                count++
                if (count % 64 == 0) {
                    val now = SystemClock.elapsedRealtimeNanos()
                    report("WINDOW tokens=$count tps=${64e9 / (now - windowStart)} avg=${count * 1e9 / (now - start)} thermal=${thermal()}")
                    windowStart = now
                }
            }
            val elapsed = SystemClock.elapsedRealtimeNanos() - start
            runner.finalizeGeneration()
            val allocated = (Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull() ?: 0L) - allocatedBefore
            val gcs = (Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull() ?: 0L) - gcBefore
            report("RESULT tokens=$count tps=${count * 1e9 / elapsed} nativeTps=${count * 1e9 / nativeNanos} allocated=$allocated gcs=$gcs stop=${runner.getStopReason()} thermal=${thermal()} sha256=${digest.digest().joinToString("") { "%02x".format(it) }}")
            if (verifyTurns) {
                check(runner.processUserPrompt("Summarize the key evidence in one paragraph.", 64) == 0)
                val reasoning = StringBuilder()
                val content = StringBuilder()
                fun StringBuilder.applyDelta(delta: String) {
                    if (delta.startsWith('\u0001')) {
                        clear()
                        append(delta.substring(1))
                    } else append(delta)
                }
                var secondCount = 0
                while (true) {
                    val token = runner.nextToken()
                    reasoning.applyDelta(runner.getReasoningDelta())
                    content.applyDelta(runner.getContentDelta())
                    if (token == null) break
                    check(++secondCount <= 64)
                }
                runner.finalizeGeneration()
                check(reasoning.toString() == runner.getReasoning())
                check(content.toString() == runner.getContent())
                check(runner.getReasoningDelta().isEmpty() && runner.getContentDelta().isEmpty())
                report("TURNS verified=2 finalDeltasMatch=true secondTokens=$secondCount ctxUsed=${runner.getContextUsed()}")
            }
            runner.unloadModel()
            finish(Activity.RESULT_OK, Bundle())
        } catch (failure: Exception) {
            runCatching { runner.unloadModel() }
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "Benchmark failed (${failure.javaClass.simpleName}); inspect bounded native diagnostics.\n") })
        }
    }

    private fun diagnose() = runBlocking {
        initPreferencesDataStore(targetContext)
        fun report(value: String) = sendStatus(0, Bundle().apply { putString("stream", "$value\n") })
        val koin = KoinPlatform.getKoin()
        val model = koin.get<LocalModelRepository>().getMainModels().single {
            it.filename == "MiniCPM5-2B-Q4_K_M.gguf"
        }
        val resolver = koin.get<InstalledModelLoadRequestResolver>()
        val prepared = resolver.prepare(model, GenerationMode.Text)
        check(prepared is InstalledModelLoadPreparation.Ready)
        val snapshot = koin.get<DeviceSnapshotProvider>().capture()
        val settings = koin.get<SettingsRepository>().getSettings().first()
        report("SETTINGS gpu=${settings.useGpu} profile=${settings.recommendationProfile} kv=${settings.kvQuantPreset}")
        snapshot.hardwareProfile.backends.forEach {
            report("BACKEND kind=${it.kind} status=${it.status} free=${it.additionalAllocatableBytes} confidence=${it.availabilityConfidence}")
        }
        report("BUDGET shared=${snapshot.baseSharedBudgetBytes} thermal=${snapshot.resources.thermalState}")
        val workload = checkNotNull(koin.get<InstalledModelWorkloadFactory>().create(prepared.descriptor, GenerationMode.Text, settings))
        val repository = koin.get<ModelAssessmentRepository>()
        val assessment = repository.assess(prepared.descriptor, snapshot, workload)
        val recommendation = repository.personalize(assessment, snapshot, settings.recommendationProfile)
        report("POLICY category=${recommendation.category} reasons=${recommendation.reasons} backend=${(recommendation.selectedPlan as? LlmRunPlan)?.backend}")
        assessment.planAssessments.values.forEach {
            val plan = it.plan as? LlmRunPlan
            report("PLAN backend=${plan?.backend} ctx=${plan?.contextTokens} host=${it.hostMemoryBytes} shared=${it.sharedMemoryBytes} performance=${it.performance}")
        }
        report("RESOLUTION ${resolver.resolve(prepared)::class.simpleName}")
    }
}
