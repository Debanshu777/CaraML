package com.debanshu777.caraml.benchmark.tests

import android.content.ComponentName
import android.content.Intent
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.Until
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatScrollBenchmark {
    @get:Rule val benchmark = MacrobenchmarkRule()
    @Before fun requireReadyDevice() = requireUnlockedDevice()

    @Test fun chatHistory() = benchmark.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = warmedCompilation(),
        iterations = benchmarkIterations,
        // No startupMode: activity launch and initial auto-scroll are outside the measured block.
        setupBlock = {
            killProcess()
            pressHome()
            startActivityAndWait(Intent().apply {
                component = ComponentName(TARGET_PACKAGE, "com.debanshu777.caraml.benchmark.ChatScrollActivity")
                benchmarkSoftEffects?.let { putExtra("softEffects", it) }
            })
            check(device.wait(Until.hasObject(By.res("chat-message-list")), UI_TIMEOUT_MS))
            // The real screen scrolls to its last message on launch. Wait for that endpoint.
            check(device.wait(Until.hasObject(By.textContains("Answer 99:")), UI_TIMEOUT_MS)) {
                "Chat fixture did not finish its initial scroll"
            }
            device.waitForIdle()
        },
    ) {
        fun visibleTextPositions(): List<Pair<String, Int>> {
            repeat(5) {
                try {
                    return checkNotNull(device.findObject(By.res("chat-message-list")))
                        .findObjects(By.clazz("android.widget.TextView"))
                        .map { it.text to it.visibleBounds.top }
                } catch (stale: StaleObjectException) {
                    // LazyColumn can recycle virtual nodes between discovery and reading.
                    if (it == 4) throw stale
                    device.waitForIdle()
                }
            }
            error("Could not read the chat viewport")
        }

        val bounds = checkNotNull(device.findObject(By.res("chat-message-list"))).visibleBounds
        // The list extends behind the frosted header/composer. Use its central region.
        val x = bounds.centerX()
        val upperY = bounds.top + bounds.height() * 2 / 5
        val lowerY = bounds.top + bounds.height() * 7 / 10
        // UiObject2.scroll relies on accessibility scroll events that are not consistently
        // delivered by this Compose/Android combination. Check actual text positions instead.
        val initial = visibleTextPositions()
        repeat(6) {
            check(device.swipe(x, upperY, x, lowerY, 100)) { "Could not inject upward-history swipe" }
            device.waitForIdle()
        }
        val afterUp = visibleTextPositions()
        check(initial.isNotEmpty() && afterUp.isNotEmpty() && afterUp != initial) { "Chat history did not move up" }
        check(!device.hasObject(By.textContains("Answer 99:"))) { "Chat stayed at its initial endpoint" }
        repeat(4) {
            check(device.swipe(x, lowerY, x, upperY, 100)) { "Could not inject downward-history swipe" }
            device.waitForIdle()
        }
        val afterDown = visibleTextPositions()
        check(afterDown.isNotEmpty() && afterDown != afterUp) { "Chat history did not move down" }
        check(device.currentPackageName == TARGET_PACKAGE) { "Chat lost the foreground during scrolling" }
    }
}
