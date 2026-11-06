package com.debanshu777.caraml.core.recommendation

import kotlin.test.*

class OnnxExportCompatibilityTest {
    @Test fun exclusionIsLimitedToTheMeasuredDeviceAndExactWeightDigest() {
        fun file(hash: String) = ModelFileIdentity("owner/model", "a".repeat(40), "onnx/model_int8.onnx", 123,
            null, hash, null, emptyList())
        val known = file("sha256:ad53ed44994c27ab60e8024e83fcd83ce38db4d4ad3a28549b432480eb84d180")
        assertNotNull(onnxExportIssue(listOf(known), "Pixel 9"))
        assertNull(onnxExportIssue(listOf(known), null))
        assertNull(onnxExportIssue(listOf(known), "Different device"))
        assertNull(onnxExportIssue(listOf(file("b".repeat(64))), "Pixel 9"))
        val largeQ4 = file("a3a6f10916f79379d15cfa9270b7be0d09be2b80fe0872bd7030eaf9001baf46")
        assertNotNull(onnxExportIssue(listOf(largeQ4), "iPhone14,5"))
        assertNull(onnxExportIssue(listOf(largeQ4), "Pixel 9"))
        assertNull(onnxExportIssue(listOf(largeQ4), "iPhone16,1"))
    }
}
