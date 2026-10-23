package com.debanshu777.caraml.benchmark.tests

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in collection of the real launcher; the synthetic scrolling activity is excluded. */
@RunWith(AndroidJUnit4::class)
@ProfileGeneration
class StartupProfileGenerator {
    @get:Rule val profile = BaselineProfileRule()

    @Test fun startup() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("generateProfile") == "true")
        requireUnlockedDevice()
        profile.collect(
            packageName = TARGET_PACKAGE,
            // Keep DEX layout unchanged in this first experiment: measure ART precompilation.
            includeInStartupProfile = false,
        ) {
            pressHome()
            startActivityAndWait(launcherIntent())
            check(device.wait(Until.hasObject(By.pkg(TARGET_PACKAGE).depth(0)), UI_TIMEOUT_MS))
            device.waitForIdle()
        }
    }
}
