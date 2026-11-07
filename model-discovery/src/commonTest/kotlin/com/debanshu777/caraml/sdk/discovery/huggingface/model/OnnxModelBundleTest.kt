package com.debanshu777.caraml.sdk.discovery.huggingface.model

import kotlin.test.*

class OnnxModelBundleTest {
    private fun file(path: String, size: Long = 10) = ModelFileTreeResponse(path = path, size = size, type = "file")
    private val metadata = listOf(file("config.json"), file("tokenizer.json"))

    @Test fun quantizationsKeepTheirOwnExternalWeightsAndSharedMetadata() {
        val bundles = OnnxModelBundles.resolve(metadata + listOf(file("onnx/model_int8.onnx"), file("onnx/model_int8.onnx_data"),
            file("onnx/model_q4.onnx"), file("onnx/model_q4.onnx_data_0"), file("onnx/model_q4.onnx_data_1"), file("onnx/model_fp16.onnx")))
        assertEquals(2, bundles.size)
        assertEquals(40, bundles[0].sizeBytes)
        assertEquals(50, bundles[1].sizeBytes)
        assertTrue(bundles.all { OnnxModelBundles.isComplete(it.files.map { file -> file.path!! }) })
        assertFalse(bundles[0].files.any { "q4" in it.path!! })
    }

    @Test fun missingMetadataAmbiguousPathsAndUnsupportedPrecisionAreRejected() {
        assertTrue(OnnxModelBundles.resolve(listOf(file("model.onnx"), file("config.json"))).isEmpty())
        assertTrue(OnnxModelBundles.resolve(metadata + file("model.onnx", 0)).isEmpty())
        assertTrue(OnnxModelBundles.resolve(metadata + metadata + file("model.onnx")).isEmpty())
        assertFalse(OnnxModelBundles.isComplete(listOf("config.json", "tokenizer.json", "model.onnx", "../weights")))
        assertFalse(OnnxModelBundles.isComplete(listOf("config.json", "tokenizer.json", "model.onnx", "model_int8.onnx")))
        assertFalse(OnnxModelBundles.isSupportedGraph("onnx/model_q4f16.onnx"))
        assertFalse(OnnxModelBundles.isSupportedGraph("../model.onnx"))
    }
}
