package com.debanshu777.caraml.benchmark.tests

import android.content.Intent
import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A new process with previously populated app/driver caches, separately from COLD's cache clearing. */
@RunWith(AndroidJUnit4::class)
@MeasurementExperiment
class CachePreservingStartupBenchmark {
    @get:Rule val benchmark = MacrobenchmarkRule()
    @Before fun requireReadyDevice() = requireUnlockedDevice()

    @Test fun processColdCachesPreserved() = benchmark.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = CompilationMode.Partial(BaselineProfileMode.Require, warmupIterations = 0),
        iterations = benchmarkIterations,
        // Deliberately no startupMode=COLD: that also clears shader caches. We still
        // kill the process ourselves after an untimed readiness/render journey.
        setupBlock = {
            prepareBenchmarkPreferences()
            startActivityAndWait(freshLauncherIntent())
            check(device.wait(Until.hasObject(By.res("caraml-app-ready")), UI_TIMEOUT_MS))
            device.waitForIdle()
            pressHome()
            killProcess()
        },
    ) {
        startActivityAndWait(freshLauncherIntent())
        check(device.wait(Until.hasObject(By.res("caraml-app-ready")), UI_TIMEOUT_MS)) {
            "CaraML did not reach usable app readiness"
        }
    }

    private fun freshLauncherIntent() = launcherIntent().addFlags(
        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK,
    )
}
