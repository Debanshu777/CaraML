package com.debanshu777.caraml.core.data.inference

import com.debanshu777.caraml.core.recommendation.DiffusionExecutionConfig
import com.debanshu777.caraml.core.recommendation.DiffusionMode
import com.debanshu777.diffusionrunner.DiffusionModelConfig
import com.debanshu777.diffusionrunner.ImageGenParams
import com.debanshu777.diffusionrunner.VideoGenParams
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AdmittedDiffusionExecutionStateTest {
    @Test
    fun admittedValuesOverrideCallerDriftAndWrongModeFailsClosed() {
        val state = AdmittedDiffusionExecutionState()
        state.publish(execution(DiffusionMode.IMAGE))

        val caller = ImageGenParams(prompt = "hello", width = 256, height = 256, steps = 9)
        val admitted = state.applyTo(caller)

        assertEquals(caller.copy(width = 768, height = 448, steps = 31), admitted)
        assertNull(state.applyTo(VideoGenParams(prompt = "hello")))
    }

    @Test
    fun clearingTerminalStateRestoresLegacyCallerValues() {
        val state = AdmittedDiffusionExecutionState()
        val caller = VideoGenParams(prompt = "hello", width = 256, height = 256, videoFrames = 4, steps = 9)
        state.publish(execution(DiffusionMode.VIDEO))
        assertEquals(
            caller.copy(width = 768, height = 448, videoFrames = 24, steps = 31),
            state.applyTo(caller),
        )

        state.clear()

        assertEquals(caller, state.applyTo(caller))
    }

    private fun execution(mode: DiffusionMode) = DiffusionExecutionConfig(
        model = DiffusionModelConfig(modelPath = "/private/model.gguf"),
        mode = mode,
        width = 768,
        height = 448,
        frameCount = if (mode == DiffusionMode.IMAGE) 1 else 24,
        batchSize = 1,
        steps = 31,
        maxVramBytes = null,
    )
}
