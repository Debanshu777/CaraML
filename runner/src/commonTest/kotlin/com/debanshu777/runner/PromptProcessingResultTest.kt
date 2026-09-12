package com.debanshu777.runner

import kotlin.test.Test
import kotlin.test.assertEquals

class PromptProcessingResultTest {
    @Test
    fun mapsNativePromptAdmissionCodesWithoutExposingNativeDetails() {
        val cases = listOf(
            0 to PromptProcessingResult.Success,
            4 to PromptProcessingResult.ContextFull,
            1 to PromptProcessingResult.Failure,
            2 to PromptProcessingResult.Failure,
            3 to PromptProcessingResult.Failure,
            Int.MIN_VALUE to PromptProcessingResult.Failure,
        )

        cases.forEach { (code, expected) ->
            assertEquals(expected, PromptProcessingResult.fromNativeCode(code))
        }
    }
}
