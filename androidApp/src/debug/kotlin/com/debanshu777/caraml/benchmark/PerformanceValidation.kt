package com.debanshu777.caraml.benchmark

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import java.io.File
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import com.debanshu777.caraml.core.data.inference.InferenceRepository
import com.debanshu777.caraml.core.data.inference.LlamaInferenceRepository
import com.debanshu777.caraml.core.data.inference.ModelLoadResult
import com.debanshu777.caraml.core.data.settings.SettingsRepository
import com.debanshu777.caraml.core.download.DownloadRuntime
import com.debanshu777.caraml.core.download.DownloadTaskStore
import com.debanshu777.caraml.core.download.DownloadUserIntent
import com.debanshu777.caraml.core.download.PlatformDownloadScheduler
import com.debanshu777.caraml.core.recommendation.*
import com.debanshu777.caraml.core.settings.AppSettings
import com.debanshu777.caraml.core.settings.initPreferencesDataStore
import com.debanshu777.caraml.core.storage.localmodel.LocalModelRepository
import com.debanshu777.caraml.features.chat.domain.GenerationMode
import com.debanshu777.runner.LlamaRunner
import com.debanshu777.runner.StopReason
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import org.koin.mp.KoinPlatform
import java.security.MessageDigest

/** Debug-only measurements of the normal installed-model admission and shipping repository path. */
class PerformanceValidation : Instrumentation() {
    private var arguments = Bundle()
    private var active: InferenceRepository? = null
    private var foreground: PerformanceForegroundActivity? = null
    private val results = linkedMapOf<String, String>()
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        this.arguments = arguments ?: Bundle()
        start()
    }
    override fun onStart() {
        waitForIdleSync()
        var completionCode = Activity.RESULT_CANCELED
        val completion = Bundle()
        try {
            require(arguments.keySet().all { it in setOf("case", "phase", "label", "trial", "sampling") && arguments.get(it) is String })
            val selection = arguments.getString("case") ?: "six"
            val phase = arguments.getString("phase") ?: "run"
            val label = arguments.getString("label") ?: "BASELINE"
            val sampling = arguments.getString("sampling") ?: "greedy"
            require(sampling in setOf("greedy", "saved_temperature"))
            val trial = arguments.getString("trial")?.toIntOrNull() ?: if (arguments.containsKey("trial")) error("Invalid trial") else 1
            require(phase in setOf("publication", "diagnose", "saved-load", "load", "run") && label in setOf("BASELINE", "AFTER") && trial in 1..5)
            val cases = when (selection) {
                "six" -> CASES.filter { it.id in REPRESENTATIVES }
                "all" -> CASES
                else -> CASES.filter { it.id == selection }
            }
            require(cases.isNotEmpty())
            initPreferencesDataStore(targetContext)
            foreground = startActivitySync(Intent(targetContext, PerformanceForegroundActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as PerformanceForegroundActivity
            waitForIdleSync()
            runBlocking {
                withTimeout(5_000L) {
                    while (foreground?.validationResumed != true || foreground?.validationFocused != true) delay(20)
                }
                val installedApk = installedApkIdentity()
                report("ENVIRONMENT", "installed_apk", "sha256" to installedApk.first, "bytes" to installedApk.second)
                val koin = KoinPlatform.getKoin()
                val startupStarted = SystemClock.elapsedRealtimeNanos()
                koin.get<DownloadRuntime>().awaitStartupReconciliation()
                report("ENVIRONMENT", "startup_barrier", "startupBarrierMs" to elapsedMs(startupStarted))
                if (phase in setOf("load", "run") && !idle()) {
                    cases.forEach { results[it.id] = "CONDITIONED_SKIP"; report(it.id, "outcome", "result" to "CONDITIONED_SKIP") }
                    return@runBlocking
                }
                val store = koin.get<DownloadTaskStore>()
                val scheduler = koin.get<PlatformDownloadScheduler>()
                val activeDownloads = store.recoverableBatches().count { it.userIntent == DownloadUserIntent.RUN && scheduler.isActive(it.batchId) }
                report("ENVIRONMENT", "start", "label" to label, "trial" to trial,
                    "managedBuild" to "DEBUG", "nativeBuild" to "RELEASE", "activeDownloads" to activeDownloads,
                    "thermal" to thermal(), "sampling" to sampling, "seedControl" to "NOT_EXPOSED", "cacheState" to "OS_CACHE_UNCONTROLLED", "sustainedTarget" to 512, "perTurnCap" to 1024)
                check(activeDownloads == 0)
                report("ENVIRONMENT", "foreground", "resumed" to foreground?.validationResumed,
                    "focused" to foreground?.validationFocused, "cpuAllowedList" to cpuAllowedList())
                val persisted = koin.get<SettingsRepository>().getSettings().first()
                report("ENVIRONMENT", "persisted_numeric_settings", "temperature" to persisted.temperature.takeIf { it.isFinite() },
                    "useGpu" to persisted.useGpu, "kvPreset" to persisted.kvQuantPreset.name,
                    "riskTolerance" to persisted.riskTolerance.name, "optimizationPriority" to persisted.optimizationPriority.name)
                for (case in cases) {
                    try {
                        results[case.id] = withTimeout(15 * 60_000L) { validate(case, phase, sampling) }
                    } catch (cancelled: CancellationException) {
                        if (cancelled !is TimeoutCancellationException) throw cancelled
                        results[case.id] = "TIMEOUT"
                    } catch (failure: Exception) {
                        results[case.id] = "FAILED"
                        report(case.id, "failure", "errorClass" to boundedClass(failure))
                    } finally {
                        withContext(NonCancellable) { active?.unloadModel() }
                        active = null
                    }
                    report(case.id, "outcome", "result" to results.getValue(case.id))
                }
            }
            completionCode = if (results.values.all { it == "PASS" }) Activity.RESULT_OK else Activity.RESULT_CANCELED
            completion.putString("stream", "Performance results: ${results.entries.joinToString { "${it.key}=${it.value}" }}\n")
        } catch (failure: Exception) {
            runBlocking { withContext(NonCancellable) { active?.unloadModel() } }
            report("ENVIRONMENT", "failure", "errorClass" to boundedClass(failure))
        } finally {
            runOnMainSync { foreground?.finish() }
            foreground = null
        }
        finish(completionCode, completion)
    }
    private suspend fun validate(case: Case, phase: String, sampling: String): String {
        val koin = KoinPlatform.getKoin()
        val models = koin.get<LocalModelRepository>().getMainModels()
        val model = models.singleOrNull { it.modelId == case.repo && it.filename == case.file } ?: return "NOT_INSTALLED"
        val resolver = koin.get<InstalledModelLoadRequestResolver>()
        val priorReleaseStarted = SystemClock.elapsedRealtimeNanos()
        koin.get<InferenceRepository>().unloadModel()
        report(case.id, "release_original", "ms" to elapsedMs(priorReleaseStarted))
        val conditioningStarted = SystemClock.elapsedRealtimeNanos()
        if (!idle()) return "CONDITIONED_SKIP"
        val startThermal = thermal()
        val resumed = foreground?.validationResumed == true
        val focused = foreground?.validationFocused == true
        val allowedCpus = cpuAllowedList()
        report(case.id, "case_conditioning", "ms" to elapsedMs(conditioningStarted), "thermal" to startThermal,
            "foregroundResumed" to resumed, "foregroundFocused" to focused, "cpuAllowedList" to allowedCpus)
        if (startThermal != 0) return "CONDITIONED_SKIP"
        if (!resumed || !focused) return "FOREGROUND_NOT_READY"
        if (allowedCpus != "0-7") return "FOREGROUND_AFFINITY_NOT_READY"
        val wholeStarted = SystemClock.elapsedRealtimeNanos()
        val preparation = resolver.prepare(model, GenerationMode.Text)
        report(case.id, "prepare", "ms" to elapsedMs(wholeStarted), "state" to preparation::class.simpleName,
            "reason" to (preparation as? InstalledModelLoadPreparation.Terminal)?.resolution?.let(::resolutionReason))
        if (preparation !is InstalledModelLoadPreparation.Ready) return "ADMISSION_NOT_READY"
        val primary = preparation.artifact.components.singleOrNull { it.logicalRole == "model" } ?: return "IDENTITY_REJECTED"
        val identity = primary.identity
        val pinned = identity.repositoryId == case.repo && identity.revision == case.revision && identity.path == case.file &&
            identity.sizeBytes == case.bytes && primary.byteCount == case.bytes && primary.contentSha256 == case.sha &&
            primary.remoteObjectId == "sha256:${case.sha}"
        report(case.id, "verified_identity", "pinned" to pinned, "bytes" to primary.byteCount,
            "sha256" to primary.contentSha256, "revision" to identity.revision)
        if (!pinned) return "IDENTITY_REJECTED"
        if (phase == "publication") return "PASS"
        val useSavedSettings = phase in setOf("diagnose", "saved-load")
        val originalSettings = koin.get<SettingsRepository>()
        val persistedSettings = originalSettings.getSettings().first()
        val settings = if (useSavedSettings) persistedSettings else persistedSettings
            .copy(systemPrompt = AppSettings.DEFAULT_SYSTEM_PROMPT, temperature = if (sampling == "saved_temperature") persistedSettings.temperature else 0f)
        val controlled = object : SettingsRepository {
            override fun getSettings() = flowOf(settings)
            override suspend fun updateSettings(settings: AppSettings) = error("Read-only benchmark")
            override suspend fun updateRecommendationProfile(profile: RecommendationProfile) = error("Read-only benchmark")
            override suspend fun completeModelProfileOnboarding(profile: RecommendationProfile) = error("Read-only benchmark")
        }
        val runner = koin.get<LlamaRunner>()
        val inference = LlamaInferenceRepository(runner = runner, deviceCapabilities = koin.get(), settingsRepository = if (useSavedSettings) originalSettings else controlled,
            snapshotProvider = koin.get(), suitabilityEngine = koin.get(), recommendationPolicy = koin.get(),
            loadRecoveryRepository = koin.get(), artifactIdentityResolver = koin.get(), loadSessionCoordinator = koin.get(),
            observationRecorder = koin.get()).also { active = it }
        val releaseStarted = SystemClock.elapsedRealtimeNanos()
        inference.unloadModel()
        report(case.id, "release_previous", "ms" to elapsedMs(releaseStarted))
        val resolveStarted = SystemClock.elapsedRealtimeNanos()
        val resolution = resolver.resolve(preparation)
        report(case.id, "resolve", "ms" to elapsedMs(resolveStarted), "state" to resolution::class.simpleName, "reason" to resolutionReason(resolution))
        if (resolution !is InstalledModelLoadResolution.Ready) return "ADMISSION_NOT_READY"
        val loadStarted = SystemClock.elapsedRealtimeNanos()
        val load = inference.loadModel(resolution.request)
        report(case.id, "load", "ms" to elapsedMs(loadStarted), "wholeLoadMs" to elapsedMs(wholeStarted), "state" to load::class.simpleName,
            "context" to inference.getContextLimit(), "config" to inference.getRuntimeConfigString(),
            "thermal" to thermal(), "temperature" to settings.temperature.takeIf { it.isFinite() }, "savedSettings" to (useSavedSettings))
        if (load !is ModelLoadResult.Success) return "LOAD_NOT_READY"
        if (phase in setOf("diagnose", "load", "saved-load")) return "PASS"
        var sustained = 0
        var turns = 0
        for (prompt in PROMPTS) {
            if (sustained >= 512) break
            if (!cool()) return "THERMAL_NOT_READY"
            // Each measurement begins from a normal context reset; prefill stays separate from decode.
            inference.resetContext()
            val measured = generate(case.id, inference, runner, prompt, ++turns, 1024)
            sustained += measured
            check(inference.getStopReason() != StopReason.ERROR)
        }
        val targetMet = sustained >= 512
        report(case.id, "sustained", "tokens" to sustained, "turns" to turns, "targetMet" to targetMet)
        inference.resetContext()
        generate(case.id, inference, runner, "List 200 consecutive integers with explanations. /no_think", 99, 16)
        check(inference.getStopReason() == StopReason.CANCELLED)
        inference.unloadModel()
        check(inference.getContextLimit() == 0)
        report(case.id, "unload", "context" to 0)
        val reloadStarted = SystemClock.elapsedRealtimeNanos()
        val reloaded = inference.loadModel(resolution.request)
        report(case.id, "reload", "ms" to elapsedMs(reloadStarted), "state" to reloaded::class.simpleName)
        check(reloaded is ModelLoadResult.Success)
        val recovery = generate(case.id, inference, runner, "Explain five properties of water. /no_think", 100, 64)
        check(recovery > 0 && inference.getStopReason() != StopReason.ERROR)
        return if (targetMet) "PASS" else "INSUFFICIENT_SUSTAINED"
    }
    private suspend fun generate(id: String, inference: InferenceRepository, runner: LlamaRunner, prompt: String, turn: Int, cap: Int): Int =
        withTimeout(10 * 60_000L) {
            val started = SystemClock.elapsedRealtimeNanos()
            val content = StringBuilder(); val reasoning = StringBuilder()
            val allocatedBefore = runtimeStat("art.gc.bytes-allocated")
            val gcBefore = runtimeStat("art.gc.gc-count")
            var tokens = 0; var first = 0L; var last = 0L; var windowStart = 0L; var nativeNs = 0L
            var thermalStopped = false
            inference.generateResponse(prompt).collect { chunk ->
                if (chunk.contentResync) content.clear()
                if (chunk.reasoningResync) reasoning.clear()
                content.append(chunk.contentDelta); reasoning.append(chunk.reasoningDelta)
                check(content.length + reasoning.length <= 1_048_576)
                if (chunk.isTokenEvent) {
                    val now = SystemClock.elapsedRealtimeNanos()
                    tokens++; nativeNs += chunk.nativeDecodeNanoseconds; last = now
                    if (first == 0L) { first = now; windowStart = now }
                    if (tokens > 1 && (tokens - 1) % 64 == 0) {
                        report(id, "decode_window", "turn" to turn, "throughToken" to tokens, "tokens" to 64,
                            "tps" to 64e9 / (now - windowStart).coerceAtLeast(1L), "thermal" to thermal())
                        windowStart = now
                    }
                    if (tokens >= cap) inference.cancelGeneration()
                    if (thermal() >= 3) { thermalStopped = true; inference.cancelGeneration() }
                }
            }
            check(!thermalStopped)
            check(tokens > 0 && content.isNotBlank())
            check(content.toString() == runner.getContent() && reasoning.toString() == runner.getReasoning())
            val decodeNs = (last - first).coerceAtLeast(0L)
            report(id, "generation", "turn" to turn, "tokens" to tokens, "ttftMs" to (first - started) / 1_000_000,
                "wallMs" to elapsedMs(started), "decodeMs" to decodeNs / 1_000_000,
                "decodeTps" to if (tokens > 1 && decodeNs > 0) (tokens - 1) * 1e9 / decodeNs else 0.0,
                "nativeCallsMs" to nativeNs / 1_000_000, "stop" to inference.getStopReason(),
                "capReached" to (tokens >= cap), "naturalEog" to (inference.getStopReason() == StopReason.EOG),
                "thermal" to thermal(), "allocatedBytes" to (runtimeStat("art.gc.bytes-allocated") - allocatedBefore).coerceAtLeast(0),
                "gcCount" to (runtimeStat("art.gc.gc-count") - gcBefore).coerceAtLeast(0),
                "contentBytes" to content.toString().toByteArray().size, "reasoningBytes" to reasoning.toString().toByteArray().size,
                "contentSha256" to sha(content.toString()), "reasoningSha256" to sha(reasoning.toString()), "deltaMatchesFinal" to true)
            tokens
        }
    private suspend fun idle(): Boolean {
        val deadline = SystemClock.elapsedRealtime() + 10 * 60_000L
        while (thermal() >= 1) {
            report("ENVIRONMENT", "idle_barrier", "thermal" to thermal())
            if (SystemClock.elapsedRealtime() >= deadline) {
                report("ENVIRONMENT", "conditioned_skip", "thermal" to thermal())
                return false
            }
            delay(15_000)
        }
        return true
    }
    private suspend fun cool(): Boolean {
        val deadline = SystemClock.elapsedRealtime() + 10 * 60_000L
        while (thermal() >= 2) {
            report("ENVIRONMENT", "cooldown", "thermal" to thermal())
            if (SystemClock.elapsedRealtime() >= deadline) return false
            delay(15_000)
        }
        return true
    }
    private fun thermal(): Int = if (Build.VERSION.SDK_INT >= 29)
        targetContext.getSystemService(PowerManager::class.java).currentThermalStatus else -1
    private fun installedApkIdentity(): Pair<String, Long> {
        val apk = File(targetContext.applicationInfo.sourceDir)
        val limit = 256L * 1024 * 1024
        val expectedBytes = apk.length()
        require(expectedBytes in 1..limit)
        val digest = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        apk.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                bytes += count
                check(bytes <= limit)
                digest.update(buffer, 0, count)
            }
        }
        check(bytes == expectedBytes)
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) } to bytes
    }
    private fun cpuAllowedList(): String? = try {
        File("/proc/self/status").useLines { lines ->
            lines.take(128).firstOrNull { it.startsWith("Cpus_allowed_list:") }
                ?.substringAfter(":")?.trim()?.takeIf { it.length <= 50 && it.matches(Regex("[0-9,-]+")) }
        }
    } catch (_: Exception) { null }
    private fun runtimeStat(key: String): Long = Debug.getRuntimeStat(key)?.toLongOrNull() ?: 0L
    private fun elapsedMs(start: Long) = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000
    private fun sha(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun boundedClass(error: Exception): String = error.javaClass.simpleName.takeIf { it.length <= 64 && it.all(Char::isLetterOrDigit) } ?: "OTHER"
    private fun resolutionReason(value: InstalledModelLoadResolution): String = when (value) {
        is InstalledModelLoadResolution.NotAdmissible -> value.reason.name
        is InstalledModelLoadResolution.Rejected -> value.reason.name
        is InstalledModelLoadResolution.SafeAlternative -> "SAFE_ALTERNATIVE_REQUIRES_APPROVAL"
        else -> value::class.simpleName ?: "OTHER"
    }
    private fun report(id: String, stage: String, vararg fields: Pair<String, Any?>) {
        val json = JSONObject().put("case", id).put("stage", stage)
        fields.forEach { (key, value) -> json.put(key, value ?: JSONObject.NULL) }
        sendStatus(0, Bundle().apply { putString("stream", "$json\n") })
    }
    private data class Case(val id: String, val repo: String, val revision: String, val file: String, val bytes: Long, val sha: String)
    companion object {
        private val REPRESENTATIVES = setOf("smollm2-135m", "qwen3-0.6b", "lfm2-350m", "tinyllama-1.1b", "stablelm2-zephyr-1.6b", "phi2-2.7b")
        private val PROMPTS = listOf(
            "Write a detailed tutorial with at least 20 numbered sections about how computers execute programs. Explain each section with examples and finish with a conclusion. /no_think",
            "Explain the complete water cycle in at least 20 detailed numbered sections, including physical mechanisms, ecosystems and measurement. /no_think",
            "Give a detailed introduction to astronomy in at least 20 numbered sections, covering planets, stars, galaxies and observations. /no_think",
            "Write a comprehensive tutorial on sorting algorithms in at least 20 numbered sections with examples and tradeoffs. /no_think",
        )
        private val CASES = listOf(
            Case("smollm2-135m", "bartowski/SmolLM2-135M-Instruct-GGUF", "09816acd5d99df7be770d85ea30822623dab342c", "SmolLM2-135M-Instruct-Q4_K_M.gguf", 105454432L, "2e8040ceae7815abe0dcb3540b9995eaa1fa0d2ca9e797d0a635ae4433c68c2d"),
            Case("gemma3-270m", "bartowski/google_gemma-3-270m-it-GGUF", "d127a4e2c6ed47fdf409a956867b604c040432f9", "google_gemma-3-270m-it-Q4_K_M.gguf", 253115168L, "c866c9f113f2e9aa2225c5997ede437392b8fa844ba5db9e4c77e315ffe20800"),
            Case("qwen3-0.6b", "Qwen/Qwen3-0.6B-GGUF", "23749fefcc72300e3a2ad315e1317431b06b590a", "Qwen3-0.6B-Q8_0.gguf", 639446688L, "9465e63a22add5354d9bb4b99e90117043c7124007664907259bd16d043bb031"),
            Case("lfm2-350m", "unsloth/LFM2-350M-GGUF", "7ed6733192873777637b34cf3c8ba0d8380d931e", "LFM2-350M-Q4_K_M.gguf", 229309152L, "15ded463c01b6b6f6fe6a5f8ea6b87902aef9f7191bcc9c110c5591fe2f69282"),
            Case("qwen2.5-0.5b", "Qwen/Qwen2.5-0.5B-Instruct-GGUF", "9217f5db79a29953eb74d5343926648285ec7e67", "qwen2.5-0.5b-instruct-q4_k_m.gguf", 491400032L, "74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db"),
            Case("tinyllama-1.1b", "TheBloke/TinyLlama-1.1B-Chat-v1.0-GGUF", "52e7645ba7c309695bec7ac98f4f005b139cf465", "tinyllama-1.1b-chat-v1.0.Q4_K_M.gguf", 668788096L, "9fecc3b3cd76bba89d504f29b616eedf7da85b96540e490ca5824d3f7d2776a0"),
            Case("llama3.2-1b", "bartowski/Llama-3.2-1B-Instruct-GGUF", "067b946cf014b7c697f3654f621d577a3e3afd1c", "Llama-3.2-1B-Instruct-Q4_K_M.gguf", 807694464L, "6f85a640a97cf2bf5b8e764087b1e83da0fdb51d7c9fab7d0fece9385611df83"),
            Case("stablelm2-zephyr-1.6b", "stabilityai/stablelm-2-zephyr-1_6b", "2f275b1127d59fc31e4f7c7426d528768ada9ea4", "stablelm-2-zephyr-1_6b-Q4_0.gguf", 982781952L, "342f89616e40d79e57ff213d68fcdcab18a94f7370c1fb9526ff57f3e93721e3"),
            Case("gemma2-2b", "bartowski/gemma-2-2b-it-GGUF", "855f67caed130e1befc571b52bd181be2e858883", "gemma-2-2b-it-Q4_K_M.gguf", 1708582752L, "e0aee85060f168f0f2d8473d7ea41ce2f3230c1bc1374847505ea599288a7787"),
            Case("phi2-2.7b", "TheBloke/phi-2-GGUF", "5a454d977c6438bb9fb2df233c8ca70f21c87420", "phi-2.Q4_K_M.gguf", 1789239136L, "324356668fa5ba9f4135de348447bb2bbe2467eaa1b8fcfb53719de62fbd2499"),
            Case("gemma3-1b", "bartowski/google_gemma-3-1b-it-GGUF", "116f76234503685a98f572982177b11d44ec8ff1", "google_gemma-3-1b-it-Q4_K_M.gguf", 806058496L, "12bf0fff8815d5f73a3c9b586bd8fee8e7b248c935de70dec367679873d0f29d"),
            Case("smollm2-360m", "bartowski/SmolLM2-360M-Instruct-GGUF", "7be6f65f1db715fe5dc5a4634c0d459b4eed42ec", "SmolLM2-360M-Instruct-Q4_K_M.gguf", 270590880L, "2fa3f013dcdd7b99f9b237717fa0b12d75bbb89984cc1274be1471a465bac9c2"),
            Case("qwen2.5-1.5b", "Qwen/Qwen2.5-1.5B-Instruct-GGUF", "91cad51170dc346986eccefdc2dd33a9da36ead9", "qwen2.5-1.5b-instruct-q4_k_m.gguf", 1117320736L, "6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e"),
            Case("qwen3-1.7b", "bartowski/Qwen_Qwen3-1.7B-GGUF", "dcb19155b962dbb6389f4691a982043a8e651022", "Qwen_Qwen3-1.7B-Q4_K_M.gguf", 1282439584L, "72c5c3cb38fa32d5256e2fe30d03e7a64c6c79e668ad84057e3bd66e250b24fb"),
            Case("rwkv7-0.4b", "mradermacher/rwkv7-0.4B-world-GGUF", "44853b80d71a85105de94499bbdc5da1c08054b9", "rwkv7-0.4B-world.Q4_K_M.gguf", 300695456L, "25a6de3e6c99d36540b844752435256d97e082d92b389e51fcdef2deee3c88c0"),
            Case("gpt2-medium", "mradermacher/gpt2-medium-GGUF", "3b9897d67a84e967fbcc8d7de3db4c797e386740", "gpt2-medium.Q4_K_M.gguf", 270710816L, "e17361859dbb9dec30c7fadc9f0a3c90b66a6bba0b777a446c37fd8e777b9a5c"),
            Case("openelm-450m", "RichardErkhov/apple_-_OpenELM-450M-Instruct-gguf", "c1d461967f476e5bbb9a1a78d994b54edb84af6c", "OpenELM-450M-Instruct.Q4_K_M.gguf", 289467168L, "9c2968d7069d874cbdbd041a0dfdc510f7c759a974bb8f38493a9485db87abdc"),
            Case("pythia-160m", "QuantFactory/pythia-160m-GGUF", "498f1c9f4c3b090e477a96ad65854d92b65e239c", "pythia-160m.Q4_K_M.gguf", 109764928L, "5f55bef2787f1e4b0ce46d441f42e6f302dc92a60d9ef20b0e3972942545b2fe"),
            Case("falconh1-0.5b", "tiiuae/Falcon-H1-0.5B-Instruct-GGUF", "9bf0c2d4391cf4850aa62bfee1d8fe71afba8be2", "Falcon-H1-0.5B-Instruct-Q4_K_M.gguf", 314806560L, "138a37a94b9e313af4e22d4af46b8119b76a31afdd61cabbeae7010ae45d2ac6"),
            Case("bloom-560m", "QuantFactory/bloom-560m-GGUF", "d66e7d3f402b0bfaf15de9aab775880ee09eb229", "bloom-560m.Q4_K_M.gguf", 561445696L, "e4cd38c73b7abb7938982f6dc9419d289aa2fa9abdf1039790b07ff82eb0b9c9"),
            Case("olmo-1b", "mradermacher/OLMo-1B-0724-hf-GGUF", "d24d020c6d8fd24cc2129be48b5078ad9c6bef11", "OLMo-1B-0724-hf.Q4_K_M.gguf", 791470976L, "12ad05f4747e091ec28afa0457a67cc88e6646cc393418d0761db0b362a3e7bc"),
            Case("bitnet-2b", "microsoft/bitnet-b1.58-2B-4T-gguf", "a1f2f1c765812aa8af3f6eda4a313707064bba15", "ggml-model-i2_s.gguf", 1187801280L, "4221b252fdd5fd25e15847adfeb5ee88886506ba50b8a34548374492884c2162"),
        )
    }
}
