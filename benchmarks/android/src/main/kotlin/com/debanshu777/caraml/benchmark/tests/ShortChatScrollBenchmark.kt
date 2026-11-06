package com.debanshu777.caraml.benchmark.tests

import android.content.ComponentName
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Bounded opt-in journeys. The original slow-swipe test remains a separate baseline. */
@RunWith(AndroidJUnit4::class)
@MeasurementExperiment
class ShortChatScrollBenchmark {
    @get:Rule val benchmark = MacrobenchmarkRule()
    private val uiDevice by lazy { UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()) }
    private var previousIdleTimeout = 0L

    @Before fun prepare() {
        requireUnlockedDevice()
        previousIdleTimeout = Configurator.getInstance().waitForIdleTimeout
        // Continuous token updates do not become accessibility-idle. Do not turn
        // every viewport lookup into an unbounded wait for streaming to stop.
        Configurator.getInstance().waitForIdleTimeout = 0
    }

    @After fun restore() {
        Configurator.getInstance().waitForIdleTimeout = previousIdleTimeout
    }

    @Test fun fastFling() = scroll(richText = false, streaming = false)
    @Test fun markdownFling() = scroll(richText = true, streaming = false)
    @Test fun streamingFling() = scroll(richText = true, streaming = true)

    private fun scroll(richText: Boolean, streaming: Boolean) {
        val arguments = InstrumentationRegistry.getArguments()
        val windowMs = arguments.getString("scrollWindowMs", "4000").toLong().also {
            require(it in 2_000..10_000)
        }
        val messageCount = arguments.getString("messageCount", "200").toInt().also {
            require(it in 200..2_000 && it % 2 == 0)
        }
        val streamPeriodMs = arguments.getString("streamPeriodMs", "100").toLong().also {
            require(it in 25..1_000)
        }
        var bounds = Rect()
        benchmark.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = warmedCompilation(),
            iterations = benchmarkIterations,
            setupBlock = {
                killProcess()
                pressHome()
                startActivityAndWait(Intent().apply {
                    component = ComponentName(TARGET_PACKAGE, "com.debanshu777.caraml.benchmark.ChatScrollActivity")
                    putExtra("richText", richText)
                    putExtra("messageCount", messageCount)
                    putExtra("streamPeriodMs", streamPeriodMs)
                    benchmarkSoftEffects?.let { putExtra("softEffects", it) }
                })
                check(device.wait(Until.hasObject(By.res("chat-message-list")), UI_TIMEOUT_MS))
                check(device.wait(Until.hasObject(By.textContains("Answer ${messageCount / 2 - 1}:")), UI_TIMEOUT_MS))
                // Let initial auto-scroll settle outside the captured interaction.
                SystemClock.sleep(500)
                bounds = checkNotNull(device.findObject(By.res("chat-message-list"))).visibleBounds
            },
        ) {
            fun stream(running: Boolean) {
                InstrumentationRegistry.getInstrumentation().context.sendBroadcast(
                    Intent("com.debanshu777.caraml.benchmark.STREAM")
                        .setPackage(TARGET_PACKAGE).putExtra("running", running),
                )
            }
            if (streaming) stream(true)
            try {
                val start = SystemClock.uptimeMillis()
                val x = bounds.centerX()
                val upperY = bounds.top + bounds.height() * 2 / 5
                val lowerY = bounds.top + bounds.height() * 7 / 10
                // Keep the newest response visible briefly before scrolling away.
                if (streaming) SystemClock.sleep(400)
                var movementVerified = false
                while (SystemClock.uptimeMillis() - start < windowMs) {
                    val earlier = SystemClock.uptimeMillis() - start < windowMs * 2 / 3
                    check(device.swipe(x, if (earlier) upperY else lowerY,
                        x, if (earlier) lowerY else upperY, 12))
                    SystemClock.sleep(250)
                    if (!movementVerified && earlier) {
                        val current = viewport()
                        // Streaming changes text too. Require the latest-answer marker
                        // to leave the viewport, not merely a token or bounds change.
                        movementVerified = current.isNotEmpty() &&
                            !device.hasObject(By.textContains("Answer ${messageCount / 2 - 1}:"))
                    }
                }
                check(movementVerified) { "The chat viewport did not move during flings" }
                check(device.currentPackageName == TARGET_PACKAGE) { "Chat lost the foreground" }
            } finally {
                if (streaming) stream(false)
            }
        }
    }

    private fun viewport(): List<Pair<String, Int>> {
        repeat(5) { attempt ->
            try {
                return checkNotNull(uiDevice.findObject(By.res("chat-message-list")))
                    .findObjects(By.clazz("android.widget.TextView"))
                    .map { it.text to it.visibleBounds.top }
            } catch (stale: StaleObjectException) {
                if (attempt == 4) throw stale
                SystemClock.sleep(50)
            }
        }
        error("Could not read the chat viewport")
    }
}
