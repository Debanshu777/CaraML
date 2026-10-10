package com.debanshu777.caraml.benchmark

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/** Static, private debug surface keeps validation in the normal foreground CPU group. */
class PerformanceForegroundActivity : Activity() {
    @Volatile var validationResumed: Boolean = false
        private set
    @Volatile var validationFocused: Boolean = false
        private set
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(TextView(this).apply {
            text = "CaraML performance validation"
            gravity = Gravity.CENTER
        })
    }
    override fun onResume() { super.onResume(); validationResumed = true }
    override fun onPause() { validationResumed = false; super.onPause() }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        validationFocused = hasFocus
    }
}
