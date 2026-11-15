package com.debanshu777.caraml.sdk.plugin.onnx

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.nio.LongBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OnnxInputTensorsTest {
    @Test
    fun invalidCacheConfigurationClosesInputsAlreadyPrepared() {
        val environment = OrtEnvironment.getEnvironment()
        val ids = OnnxTensor.createTensor(environment, LongBuffer.wrap(longArrayOf(1)), longArrayOf(1, 1))
        val mask = OnnxTensor.createTensor(environment, LongBuffer.wrap(longArrayOf(1)), longArrayOf(1, 1))
        try {
            assertFailsWith<IllegalArgumentException> {
                OnnxInputTensors(false).use { inputs ->
                    inputs.own(ids)
                    inputs.own(mask)
                    initialOnnxCacheShape(longArrayOf(1, -1, -1, -1), buildJsonObject {
                        put("num_attention_heads", JsonPrimitive(0))
                        put("hidden_size", JsonPrimitive(16))
                    })
                }
            }
            assertTrue(ids.isClosed)
            assertTrue(mask.isClosed)
        } finally {
            if (!ids.isClosed) ids.close()
            if (!mask.isClosed) mask.close()
        }
    }

    @Test
    fun successfulInputScopePreservesBorrowedPreviousCache() {
        val environment = OrtEnvironment.getEnvironment()
        val previous = OnnxTensor.createTensor(environment, floatArrayOf(1f))
        val ids = OnnxTensor.createTensor(environment, LongBuffer.wrap(longArrayOf(1)), longArrayOf(1, 1))
        try {
            OnnxInputTensors(false).use { inputs ->
                val prepared = mapOf("input_ids" to inputs.own(ids), "past_key_values.0.key" to previous)
                assertFalse(prepared.getValue("past_key_values.0.key").isClosed)
            }
            assertTrue(ids.isClosed)
            assertFalse(previous.isClosed)
        } finally {
            previous.close()
            if (!ids.isClosed) ids.close()
        }
    }

    @Test
    fun dynamicCacheDimensionsRequireUsableModelConfiguration() {
        val dimensions = buildJsonObject {
            put("num_key_value_heads", JsonPrimitive(2))
            put("num_attention_heads", JsonPrimitive(4))
            put("hidden_size", JsonPrimitive(32))
        }
        assertContentEquals(longArrayOf(1, 2, 0, 8), initialOnnxCacheShape(longArrayOf(-1, -1, -1, -1), dimensions))
        assertFailsWith<IllegalArgumentException> {
            initialOnnxCacheShape(longArrayOf(1, -1, -1, -1), buildJsonObject {})
        }
    }
}
