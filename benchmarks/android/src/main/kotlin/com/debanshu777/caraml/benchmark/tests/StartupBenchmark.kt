package com.debanshu777.caraml.benchmark.tests

import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Intent
import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

internal const val TARGET_PACKAGE = "com.debanshu777.caraml.benchmark"
internal const val UI_TIMEOUT_MS = 15_000L
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class WarmupComparison

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class ProfileGeneration

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class MeasurementExperiment

internal val benchmarkIterations: Int
    get() = InstrumentationRegistry.getArguments().getString("iterations", "10").toInt().also {
        require(it in 1..100) { "iterations must be between 1 and 100" }
    }

internal val benchmarkSoftEffects: Boolean?
    get() = InstrumentationRegistry.getArguments().getString("softEffects")?.let {
        when (it) {
            "on" -> true
            "off" -> false
            else -> error("softEffects must be on or off")
        }
    }

internal fun MacrobenchmarkScope.prepareBenchmarkPreferences() {
    val effects = benchmarkSoftEffects ?: return
    startActivityAndWait(Intent().apply {
        component = ComponentName(TARGET_PACKAGE, "com.debanshu777.caraml.benchmark.BenchmarkPreferencesActivity")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        putExtra("softEffects", effects)
    })
    check(device.wait(Until.hasObject(By.desc("benchmark-preferences-ready")), UI_TIMEOUT_MS)) {
        "Benchmark preference setup did not finish"
    }
}

internal fun warmedCompilation() = CompilationMode.Partial(
    baselineProfileMode = BaselineProfileMode.Disable,
    warmupIterations = 3,
)

internal fun requireUnlockedDevice() {
    val context = InstrumentationRegistry.getInstrumentation().context
    check(!context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
        "Unlock the device before running UI benchmarks"
    }
}

internal fun launcherIntent() = Intent(Intent.ACTION_MAIN).apply {
    component = ComponentName(TARGET_PACKAGE, "com.debanshu777.caraml.MainActivity")
    addCategory(Intent.CATEGORY_LAUNCHER)
}

@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule val benchmark = MacrobenchmarkRule()
    @Before fun requireReadyDevice() = requireUnlockedDevice()

    @Test fun coldNoCompilation() = startup(CompilationMode.None())
    @Test @WarmupComparison fun coldWarmupProfile() = startup(warmedCompilation())
    @Test fun coldBaselineProfile() = startup(CompilationMode.Partial(
        baselineProfileMode = BaselineProfileMode.Require,
        warmupIterations = 0,
    ))

    private fun startup(compilation: CompilationMode) = benchmark.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = compilation,
        startupMode = StartupMode.COLD,
        iterations = benchmarkIterations,
        setupBlock = {
            prepareBenchmarkPreferences()
            // Initialize empty-install preferences/databases outside measurement. COLD kills
            // this process after setup, so every measured launch still starts a new process.
            startActivityAndWait(launcherIntent())
            check(device.wait(Until.hasObject(By.res("caraml-app-ready")), UI_TIMEOUT_MS))
            device.waitForIdle()
            pressHome()
        },
    ) {
        // Always measure the real launcher, including Application/Koin/Room initialization.
        startActivityAndWait(launcherIntent())
        check(device.wait(Until.hasObject(By.pkg(TARGET_PACKAGE).depth(0)), UI_TIMEOUT_MS)) {
            "CaraML did not reach the foreground"
        }
        check(device.wait(Until.hasObject(By.res("caraml-app-ready")), UI_TIMEOUT_MS)) {
            "CaraML did not reach usable app readiness"
        }
    }
}
