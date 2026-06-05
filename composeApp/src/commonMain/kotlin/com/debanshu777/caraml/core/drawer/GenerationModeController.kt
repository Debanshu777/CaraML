package com.debanshu777.caraml.core.drawer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.debanshu777.caraml.features.chat.domain.GenerationMode

class GenerationModeController(initial: GenerationMode = GenerationMode.Text) {
    var mode by mutableStateOf(initial)
        private set

    fun setState(mode: GenerationMode) {
        this.mode = mode
    }
}

val LocalGenerationModeController = staticCompositionLocalOf<GenerationModeController> {
    error("No GenerationModeController provided")
}
