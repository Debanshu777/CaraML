package com.debanshu777.caraml.core.data.inference

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PromptAdmissionPolicyTest {
    @Test
    fun boundsTextBeforeNativeTokenization() {
        assertTrue(isPromptLengthSupported("x".repeat(32_768)))
        assertFalse(isPromptLengthSupported("x".repeat(32_769)))
    }
}
