package com.debanshu777.caraml.benchmark

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.os.*
import com.debanshu777.caraml.core.data.inference.*
import com.debanshu777.caraml.core.data.settings.SettingsRepository
import com.debanshu777.caraml.core.download.DownloadRuntime
import com.debanshu777.caraml.core.performance.*
import com.debanshu777.caraml.core.recommendation.*
import com.debanshu777.caraml.core.settings.AppSettings
import com.debanshu777.caraml.core.settings.initPreferencesDataStore
import com.debanshu777.caraml.core.storage.localmodel.LocalModelRepository
import com.debanshu777.caraml.features.chat.domain.GenerationMode
import com.debanshu777.caraml.sdk.plugin.llama.bridge.StopReason
import com.debanshu777.caraml.sdk.plugin.onnx.createOnnxTextRuntime
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import org.koin.mp.KoinPlatform
import java.security.MessageDigest

/** Installed ONNX path: no downloads, no preference writes, no admission/identity bypass. */
class OnnxPerformanceValidation : Instrumentation() {
    private var options = Bundle()
    private var caseId = "qwen25-05b"
    private var memorySampling = true
    private var foreground: PerformanceForegroundActivity? = null
    private val power get() = targetContext.getSystemService(PowerManager::class.java)
    private fun thermal() = power.currentThermalStatus
    private fun temperature() = targetContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)?.div(10.0)
    private fun foregroundReady() = foreground?.validationResumed == true &&
        foreground?.validationFocused == true && power.isInteractive
    private fun ms(start: Long) = (SystemClock.elapsedRealtimeNanos() - start) / 1e6
    private fun report(stage: String, vararg fields: Pair<String, Any?>) {
        val row = JSONObject().put("case", caseId).put("stage", stage)
        fields.forEach { (key, value) -> row.put(key, value ?: JSONObject.NULL) }
        sendStatus(0, Bundle().apply { putString("stream", "CARAML_ONNX_PERF $row\n") })
    }
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        options = arguments ?: Bundle()
        start()
    }
    override fun onStart() = runBlocking {
        var engine: InferenceRepository? = null
        var code = Activity.RESULT_CANCELED
        try {
            require(options.keySet().all { it in setOf("case", "runs", "tokens", "reselect", "memorySample") })
            caseId = options.getString("case") ?: caseId
            val fixture = cases.getValue(caseId)
            val runs = (options.getString("runs")?.toInt() ?: 3).also { require(it in 1..10) }
            val tokenLimit = (options.getString("tokens")?.toInt() ?: 32).also { require(it in 8..256) }
            val reselect = options.getString("reselect")?.also { require(it in setOf("reuse", "reload")) }
            memorySampling = (options.getString("memorySample") ?: "1").also { require(it in setOf("0", "1")) } == "1"
            waitForIdleSync()
            initPreferencesDataStore(targetContext)
            val koin = KoinPlatform.getKoin()
            foreground = startActivitySync(Intent(targetContext, PerformanceForegroundActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as PerformanceForegroundActivity
            runOnMainSync { foreground!!.window.attributes = foreground!!.window.attributes.apply { screenBrightness = 0.1f } }
            withTimeout(10_000) { while (!foregroundReady()) delay(50) }
            report("environment", "foreground_resumed" to foreground!!.validationResumed,
                "foreground_focused" to foreground!!.validationFocused, "display_interactive" to power.isInteractive,
                "thermal" to thermal(), "battery_temperature_c" to temperature(), "memory_sampling" to memorySampling)
            withTimeout(180_000) {
                val startup = SystemClock.elapsedRealtimeNanos()
                tracePerformanceStage("onnx.benchmark.startupReconciliation") {
                    koin.get<DownloadRuntime>().awaitStartupReconciliation()
                    koin.get<InferenceRepository>().unloadModel()
                }
                report("startup", "elapsed_ms" to ms(startup))
                val installed = koin.get<LocalModelRepository>().getMainModels().singleOrNull {
                    it.modelId == fixture.first && it.filename == fixture.second
                } ?: error("Requested ONNX model is not installed")
                val preferences = koin.get<SettingsRepository>().getSettings().first()
                    .copy(systemPrompt = "You are a helpful assistant.", temperature = 0f, useGpu = false)
                val settings = object : SettingsRepository {
                    override fun getSettings() = flowOf(preferences)
                    override suspend fun updateSettings(transform: (AppSettings) -> AppSettings) = error("Read-only benchmark")
                    override suspend fun updateRecommendationProfile(profile: RecommendationProfile) = error("Read-only benchmark")
                    override suspend fun completeModelProfileOnboarding(profile: RecommendationProfile) = error("Read-only benchmark")
                }
                val resolver = InstalledModelLoadRequestResolver(
                    componentRepository = koin.get(), evidenceRepairer = koin.get(), artifactResolver = koin.get(),
                    snapshotProvider = koin.get(), assessmentRepository = koin.get(), settingsRepository = settings,
                    workloadFactory = InstalledModelWorkloadFactory(LlmWorkloadConfig(
                        userRequestedContextTokens = 2048, contextTokens = 2048, minimumContextTokens = 2048,
                        promptTokens = 256, generationReserveTokens = 256, batchSize = 256, microBatchSize = 64,
                        sequenceCount = 1, kvCacheSelection = KvCacheSelection.Explicit(KvCacheType.F32, KvCacheType.F32),
                        allowContextFallback = false, allowBatchFallback = false, allowKvCacheFallback = false,
                        allowedKvCacheTypes = listOf(KvCacheType.F32), evidence = emptyList(),
                    )),
                )
                val active = OnnxInferenceRepository(createOnnxTextRuntime(), settings, koin.get(), koin.get(),
                    koin.get(), koin.get(), koin.get(), koin.get()).also { engine = it }
                check(thermal() == 0 && foregroundReady()) { "Device not ready" }
                val thermalStart = thermal()
                lateinit var request: LoadRequest
                var prepareMs = 0.0
                var resolveMs = 0.0
                var loadMs = 0.0
                val total = SystemClock.elapsedRealtimeNanos()
                tracePerformanceOperation("onnx.benchmark.installedLoad") {
                    var stage = SystemClock.elapsedRealtimeNanos()
                    val prepared = resolver.prepare(installed.copy(contextLength = 2048), GenerationMode.Text)
                    prepareMs = ms(stage)
                    check(prepared is InstalledModelLoadPreparation.Ready) { "Prepare failed: $prepared" }
                    stage = SystemClock.elapsedRealtimeNanos()
                    val resolution = resolver.resolve(prepared)
                    resolveMs = ms(stage)
                    check(resolution is InstalledModelLoadResolution.Ready) { "Resolution failed: $resolution" }
                    request = resolution.request
                    val plan = request.plan as? LlmRunPlan ?: error("Invalid plan")
                    check(plan.contextTokens == 2048 && !request.nativeMetadataFallback) { "Requested context was not admitted" }
                    report("plan", "context" to plan.contextTokens, "backend" to plan.backend.name,
                        "key_cache" to plan.keyCacheType.name, "value_cache" to plan.valueCacheType.name,
                        "stable_key" to plan.stableKey)
                    report("identity", "files" to JSONArray(prepared.artifact.components.map {
                        JSONObject().put("name", it.repositoryRelativePath).put("bytes", it.byteCount).put("sha256", it.contentSha256)
                    }))
                    stage = SystemClock.elapsedRealtimeNanos()
                    check(load(active, request, prepared.artifact, "initial") is ModelLoadResult.Success) { "Load did not succeed" }
                    loadMs = ms(stage)
                }
                report("load", "load_ms" to loadMs, "prepare_ms" to prepareMs, "resolve_ms" to resolveMs,
                    "whole_load_ms" to ms(total), "context" to active.getContextLimit(), "runtime" to active.getRuntimeConfigString(),
                    "thermal_start" to thermalStart, "thermal_end" to thermal(), "battery_temperature_c" to temperature(),
                    "foreground_verified" to foregroundReady())
                if (reselect != null) {
                    val reselectThermal = thermal()
                    val selected = installed.copy(contextLength = 2048)
                    var reused = false
                    val started = SystemClock.elapsedRealtimeNanos()
                    tracePerformanceOperation("onnx.benchmark.reselect") {
                        val prepared = resolver.prepare(selected, GenerationMode.Text)
                        check(prepared is InstalledModelLoadPreparation.Ready)
                        check(prepared.artifact == request.artifact) { "Artifact changed before reselection" }
                        val retained = if (reselect == "reuse") active.tryReuseInstalledModel(selected, prepared.artifact) else null
                        reused = retained != null
                        if (retained == null) {
                            active.unloadModel()
                            val resolved = resolver.resolve(prepared)
                            check(resolved is InstalledModelLoadResolution.Ready)
                            check(resolved.request.plan == request.plan) { "Reselection changed the admitted plan" }
                            check(load(active, resolved.request, prepared.artifact, "reselect") is ModelLoadResult.Success)
                        }
                    }
                    report("reselect", "elapsed_ms" to ms(started), "mode" to reselect, "reused" to reused,
                        "context" to active.getContextLimit(), "runtime" to active.getRuntimeConfigString(),
                        "thermal_start" to reselectThermal, "thermal_end" to thermal(),
                        "foreground_verified" to foregroundReady(), "scope" to "PREPARE_AND_REPOSITORY_RESELECT")
                }
                repeat(runs + 1) { iteration ->
                    active.resetContext()
                    generate(active, iteration, tokenLimit)
                }
                val beforeRepeatThermal = thermal()
                val repeatStart = SystemClock.elapsedRealtimeNanos()
                val repeated = tracePerformanceStage("onnx.benchmark.repeatLoad") {
                    load(active, request, requireNotNull(request.artifact), "repeat")
                }
                check(repeated is ModelLoadResult.Success)
                report("repeat_load", "load_ms" to ms(repeatStart), "runtime" to active.getRuntimeConfigString(),
                    "context" to active.getContextLimit(), "thermal_start" to beforeRepeatThermal, "thermal_end" to thermal(),
                    "foreground_verified" to foregroundReady(), "scope" to "REPOSITORY_REQUEST")
                active.unloadModel()
                report("complete", "success" to true)
                report("outcome", "result" to "PASS")
                code = Activity.RESULT_OK
            }
        } catch (failure: Throwable) {
            android.util.Log.e("OnnxPerformance", "Benchmark failed", failure)
            report("error", "message" to failure.toString().take(1200))
        } finally {
            withContext(NonCancellable) { engine?.unloadModel() }
            foreground?.let { runOnMainSync { it.finish() } }
            finish(code, Bundle())
        }
    }
    private suspend fun load(engine: InferenceRepository, request: LoadRequest, artifact: ResolvedLocalArtifact, scope: String): ModelLoadResult {
        suspend fun attempt(candidate: LoadRequest, number: Int): ModelLoadResult {
            val started = SystemClock.elapsedRealtimeNanos()
            val result = engine.loadModel(candidate)
            val confirmation = (result as? ModelLoadResult.AdmissionRequired)?.admission as? LoadAdmission.ConfirmationRequired
            report("load_attempt", "scope" to scope, "attempt" to number, "elapsed_ms" to ms(started),
                "state" to result::class.simpleName, "reason" to confirmation?.reason?.name,
                "explicit_retry" to confirmation?.explicitRetryRequired, "acknowledged" to (candidate.riskAcknowledgement != null))
            return result
        }
        val first = attempt(request, 1)
        val confirmation = (first as? ModelLoadResult.AdmissionRequired)?.admission as? LoadAdmission.ConfirmationRequired ?: return first
        check(confirmation.reason == LoadAdmissionReason.RISK_ACKNOWLEDGEMENT_REQUIRED && !confirmation.explicitRetryRequired &&
            confirmation.request == request && request.artifact == artifact && request.identity == artifact.identity &&
            request.riskAcknowledgement == null && request.assessmentKey.isNotBlank() &&
            request.assessedPlans?.assessmentKey == request.assessmentKey && !request.nativeMetadataFallback) {
            "Admission requires a different plan, explicit retry or artifact"
        }
        return attempt(request.copy(riskAcknowledgement = RiskAcknowledgement(
            request.assessmentKey, request.plan.stableKey, System.currentTimeMillis())), 2)
    }
    private suspend fun generate(engine: InferenceRepository, iteration: Int, limit: Int) = coroutineScope {
        check(foregroundReady())
        val startThermal = thermal()
        val peakPss = java.util.concurrent.atomic.AtomicLong(0)
        val focus = java.util.concurrent.atomic.AtomicBoolean(true)
        val sampler = launch(Dispatchers.IO) {
            while (isActive) {
                if (!foregroundReady()) focus.set(false)
                if (memorySampling) tracePerformanceStage("onnx.benchmark.memorySample") {
                    peakPss.accumulateAndGet(Debug.MemoryInfo().also(Debug::getMemoryInfo).totalPss.toLong() * 1024, ::maxOf)
                }
                delay(100)
            }
        }
        var first = 0L
        var count = 0
        val text = StringBuilder()
        val start = SystemClock.elapsedRealtimeNanos()
        try {
            tracePerformanceOperation("onnx.benchmark.generation") {
                engine.generateResponse("What is 2 plus 3? Answer in one short sentence.").collect { chunk ->
                    if (chunk.contentDelta.isNotEmpty() || chunk.reasoningDelta.isNotEmpty()) {
                        if (first == 0L) first = SystemClock.elapsedRealtimeNanos()
                        count++
                        text.append(chunk.contentDelta).append(chunk.reasoningDelta)
                        if (count >= limit) engine.cancelGeneration()
                    }
                }
            }
        } finally { sampler.cancelAndJoin() }
        check(text.isNotBlank() && first != 0L && engine.getStopReason() != StopReason.ERROR)
        check(focus.get() && foregroundReady()) { "Foreground lost during generation" }
        report("generation", "iteration" to iteration, "warmup" to (iteration == 0), "tokens" to count,
            "token_count_scope" to "NONEMPTY_STREAM_CHUNKS", "ttft_ms" to (first - start) / 1e6,
            "elapsed_ms" to ms(start), "foreground_verified" to true, "stop_reason" to engine.getStopReason(),
            "output_valid" to true, "text" to text.toString(), "output_sha256" to MessageDigest.getInstance("SHA-256")
                .digest(text.toString().toByteArray()).joinToString("") { "%02x".format(it) },
            "thermal_start" to startThermal, "thermal_end" to thermal(), "peak_pss_bytes" to peakPss.get().takeIf { memorySampling },
            "battery_temperature_c" to temperature())
    }
    companion object {
        private val cases = mapOf(
            "smollm2-135m" to ("onnx-community/SmolLM2-135M-Instruct-ONNX" to "model_int8.onnx"),
            "smollm2-360m" to ("onnx-community/SmolLM2-360M-Instruct-ONNX" to "model_int8.onnx"),
            "qwen25-05b" to ("onnx-community/Qwen2.5-0.5B-Instruct" to "model_int8.onnx"),
            "qwen3-06b" to ("onnx-community/Qwen3-0.6B-ONNX" to "model_int8.onnx"),
            "llama32-1b" to ("onnx-community/Llama-3.2-1B-Instruct-ONNX" to "model_q4.onnx"),
        )
    }
}
