package com.debanshu777.caraml.benchmark

import android.app.Activity
import android.app.Instrumentation
import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.accessibilityservice.AccessibilityServiceInfo
import com.debanshu777.caraml.MainActivity
import com.debanshu777.caraml.core.data.inference.InferenceRepository
import com.debanshu777.caraml.core.data.settings.SettingsRepository
import com.debanshu777.caraml.core.download.DownloadRuntime
import com.debanshu777.caraml.core.performance.ModelLoadDiagnostics
import com.debanshu777.caraml.core.performance.ModelLoadMeasurement
import com.debanshu777.caraml.core.performance.PerformanceTrace
import com.debanshu777.caraml.core.settings.initPreferencesDataStore
import com.debanshu777.caraml.core.storage.localmodel.LocalModelRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.koin.mp.KoinPlatform
import java.io.File
import java.security.MessageDigest

/** Clicks the real model picker; does not invoke selectModel/loadModel directly or alter settings. */
class ModelReselectionValidation : Instrumentation() {
    private var arguments = Bundle()
    private var activity: MainActivity? = null
    private var originalWindowFlags = 0
    private var phase = "ARGUMENTS"

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        this.arguments = arguments ?: Bundle()
        start()
    }

    override fun onStart() {
        var passed = false
        val previousObserver = ModelLoadDiagnostics.observer
        val previousForceFullReload = ModelLoadDiagnostics.forceFullReloadForMeasurement
        var prefsBefore: String? = null
        var preferencesCaptured = false
        var originalAutomationFlags: Int? = null
        val events = Channel<ModelLoadMeasurement>(Channel.UNLIMITED)
        try {
            require(Build.VERSION.SDK_INT >= 29)
            require(arguments.keySet().all { it in setOf("trials", "case", "reuse") && arguments.get(it) is String })
            require(arguments.getString("case") in setOf(null, "qwen2.5-3b"))
            val reuseMode = arguments.getString("reuse") ?: "on"
            require(reuseMode in setOf("on", "off"))
            val trials = arguments.getString("trials")?.toIntOrNull()
                ?: if (arguments.containsKey("trials")) error("Invalid trial count") else 3
            require(trials in 1..5)
            check(previousObserver == null)
            ModelLoadDiagnostics.forceFullReloadForMeasurement = reuseMode == "off"
            initPreferencesDataStore(targetContext)
            prefsBefore = preferencesHash()
            preferencesCaptured = true
            val automationInfo = uiAutomation.serviceInfo
            originalAutomationFlags = automationInfo.flags
            automationInfo.flags = automationInfo.flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            uiAutomation.serviceInfo = automationInfo
            runBlocking {
                phase = "INITIAL_DATA"
                val koin = KoinPlatform.getKoin()
                koin.get<DownloadRuntime>().awaitStartupReconciliation()
                val model = koin.get<LocalModelRepository>().getMainModels().single {
                    it.modelId == "Qwen/Qwen2.5-3B-Instruct-GGUF" && it.filename == "qwen2.5-3b-instruct-q4_k_m.gguf"
                }
                val settings = koin.get<SettingsRepository>().getSettings().first()
                val inference = koin.get<InferenceRepository>()
                requireUnlockedAndNormal()
                ModelLoadDiagnostics.observer = { events.trySend(it) }
                phase = "LAUNCH_ACTIVITY"
                activity = startActivitySync(Intent(targetContext, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
                runOnMainSync {
                    originalWindowFlags = requireNotNull(activity).window.attributes.flags
                    activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                phase = "WAIT_INITIAL_MODEL_READY"
                var initial = withTimeout(180_000L) { events.receive() }
                check(initial.outcome == "SUCCESS") { "Initial model did not reach Ready" }
                if (initial.modelRowId != model.id) {
                    // Selection is not persisted. Set up the pinned case through the real picker,
                    // and exclude this first load from the timed same-selection trials.
                    val initialRow = initial.modelRowId
                    val afterAttempt = initial.attemptId
                    phase = "SETUP_OPEN_MODEL_PICKER"
                    val picker = waitForNode("chat-model-picker", 15_000L)
                    requireForeground()
                    check(picker.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                    picker.recycleCompat()
                    phase = "SETUP_SELECT_REQUESTED_MODEL"
                    val target = waitForNode("chat-model-option-${model.id}", 15_000L)
                    check(target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                    target.recycleCompat()
                    phase = "SETUP_WAIT_REQUESTED_MODEL_READY"
                    initial = withTimeout(180_000L) {
                        var next = events.receive()
                        while (next.modelRowId != model.id || next.attemptId <= afterAttempt) next = events.receive()
                        next
                    }
                    check(initial.outcome == "SUCCESS") { "Requested model did not reach Ready" }
                    report("setup_selection", "timed" to false, "fromModelRowId" to initialRow,
                        "modelRowId" to model.id, "loadMs" to initial.elapsedMs,
                        "outcome" to initial.outcome, "scope" to "REAL_MODEL_PICKER")
                }
                val initialContext = initial.contextLimit
                check(initialContext != null && initialContext > 0)
                phase = "WAIT_INITIAL_READY_UI"
                waitForNode("chat-model-picker", 15_000L).recycleCompat()
                withTimeout(15_000L) {
                    while (findNode("chat-selected-model-${model.id}")?.let { it.recycleCompat(); true } == true) delay(25)
                }
                requireForeground()
                check(inference.getContextLimit() == initialContext)
                report("start", "trials" to trials, "scope" to "REAL_MODEL_PICKER", "modelRowId" to model.id,
                    "reuse" to reuseMode,
                    "useGpu" to settings.useGpu, "kvPreset" to settings.kvQuantPreset.name,
                    "temperature" to settings.temperature, "runtimeConfig" to inference.getRuntimeConfigString(),
                    "diagnosticTracing" to PerformanceTrace.isEnabled(), "thermal" to thermal())
                check(inference.getContextLimit() > 0)
                var priorAttempt = initial.attemptId
                repeat(trials) { index ->
                    phase = "TRIAL_${index + 1}_CONDITIONS"
                    requireUnlockedAndNormal()
                    requireForeground()
                    phase = "TRIAL_${index + 1}_OPEN_PICKER"
                    val picker = waitForNode("chat-model-picker", 15_000L)
                    check(picker.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                    picker.recycleCompat()
                    // The ID binds this journey to the selected installed row, never another option.
                    phase = "TRIAL_${index + 1}_FIND_SELECTED_ROW"
                    val selected = waitForNode("chat-selected-model-${model.id}", 15_000L)
                    while (true) {
                        val prior = events.tryReceive().getOrNull() ?: break
                        priorAttempt = maxOf(priorAttempt, prior.attemptId)
                    }
                    phase = "TRIAL_${index + 1}_CLICK_SELECTED_ROW"
                    val started = SystemClock.elapsedRealtimeNanos()
                    check(selected.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                    selected.recycleCompat()
                    phase = "TRIAL_${index + 1}_WAIT_COMPLETION"
                    val completed = withTimeout(180_000L) {
                        var next = events.receive()
                        while (next.modelRowId != model.id || next.attemptId <= priorAttempt) next = events.receive()
                        next
                    }
                    val actionToCompletionMs = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
                    phase = "TRIAL_${index + 1}_VERIFY_READY"
                    check(completed.outcome == "SUCCESS")
                    val completedContext = completed.contextLimit
                    check(completedContext != null && completedContext > 0)
                    priorAttempt = completed.attemptId
                    waitForNode("chat-model-picker", 15_000L).recycleCompat()
                    withTimeout(15_000L) {
                        while (findNode("chat-selected-model-${model.id}")?.let { it.recycleCompat(); true } == true) delay(25)
                    }
                    requireUnlockedAndNormal()
                    requireForeground()
                    check(inference.getContextLimit() == completed.contextLimit)
                    report("reselection", "trial" to index + 1, "attempt" to completed.attemptId,
                        "reuse" to reuseMode,
                        "modelRowId" to completed.modelRowId, "loadMs" to completed.elapsedMs,
                        "actionToCompletionMs" to actionToCompletionMs,
                        "context" to completed.contextLimit, "runtimeConfig" to inference.getRuntimeConfigString(),
                        "outcome" to completed.outcome, "thermal" to thermal(), "scope" to "REAL_MODEL_PICKER")
                }
            }
            passed = true
        } catch (failure: Exception) {
            report("failure", "errorClass" to failure.javaClass.simpleName, "phase" to phase, "reason" to "UI_JOURNEY_FAILED")
        } finally {
            ModelLoadDiagnostics.observer = previousObserver
            ModelLoadDiagnostics.forceFullReloadForMeasurement = previousForceFullReload
            events.close()
            if (activity != null) runOnMainSync {
                activity?.window?.setFlags(originalWindowFlags, WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                activity?.finish()
            }
            originalAutomationFlags?.let { flags ->
                val restored = runCatching {
                    val info = uiAutomation.serviceInfo
                    info.flags = flags
                    uiAutomation.serviceInfo = info
                    uiAutomation.serviceInfo.flags == flags
                }.getOrDefault(false)
                report("accessibility_cleanup", "restored" to restored)
                passed = passed && restored
            }
            if (preferencesCaptured) {
                val preserved = runCatching { preferencesHash() == prefsBefore }.getOrDefault(false)
                report("preferences", "preserved" to preserved)
                passed = passed && preserved
            }
        }
        report("outcome", "result" to if (passed) "PASS" else "FAILED")
        finish(if (passed) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString("stream", "UI reselection: ${if (passed) "PASS" else "FAILED"}\n")
        })
    }

    private suspend fun waitForNode(tag: String, timeoutMs: Long): AccessibilityNodeInfo = withTimeout(timeoutMs) {
        var found = findNode(tag)
        var polls = 0
        var scrolls = 0
        while (found == null) {
            delay(50)
            if (++polls % 10 == 0 && scrolls < 10) {
                // Both the empty composer and the picker list may extend below the viewport.
                findNode(scrollable = true)?.let { node ->
                    node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    node.recycleCompat()
                    scrolls++
                }
            }
            found = findNode(tag)
        }
        found
    }

    /** Return one owned node; recycle all traversed nodes, and bound work on unexpected windows. */
    private fun findNode(tag: String? = null, scrollable: Boolean = false): AccessibilityNodeInfo? {
        val root = uiAutomation.rootInActiveWindow ?: return null
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        pending.add(root)
        var visited = 0
        try {
            while (pending.isNotEmpty() && visited++ < 1000) {
                val node = pending.removeFirst()
                val name = node.viewIdResourceName
                if (node.packageName?.toString() == targetContext.packageName && node.isVisibleToUser &&
                    node.isEnabled && (if (scrollable) node.isScrollable
                        else name == tag || name == "${targetContext.packageName}:id/$tag")
                ) return node
                for (index in 0 until node.childCount) node.getChild(index)?.let(pending::addLast)
                node.recycleCompat()
            }
            return null
        } finally {
            pending.forEach { it.recycleCompat() }
        }
    }

    private fun requireForeground() {
        var focused = false
        runOnMainSync { focused = activity?.hasWindowFocus() == true && activity?.isFinishing == false }
        check(focused) { "Foreground activity is not ready" }
    }

    private fun requireUnlockedAndNormal() {
        check(targetContext.getSystemService(PowerManager::class.java).isInteractive) { "Screen is not awake" }
        check(!targetContext.getSystemService(KeyguardManager::class.java).isKeyguardLocked) { "Unlock before UI measurements" }
        check(thermal() == 0) { "Thermal status must be NORMAL" }
    }

    private fun thermal(): Int = targetContext.getSystemService(PowerManager::class.java).currentThermalStatus

    private fun preferencesHash(): String? {
        val file = File(targetContext.filesDir, "app.preferences_pb")
        if (!file.exists()) return null
        check(file.length() <= 1_048_576)
        return MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun report(stage: String, vararg fields: Pair<String, Any?>) {
        val json = JSONObject().put("stage", stage)
        fields.forEach { (key, value) -> json.put(key, value ?: JSONObject.NULL) }
        sendStatus(0, Bundle().apply { putString("stream", "$json\n") })
    }
}

@Suppress("DEPRECATION")
private fun AccessibilityNodeInfo.recycleCompat() = recycle()
