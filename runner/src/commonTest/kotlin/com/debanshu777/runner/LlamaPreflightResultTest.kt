package com.debanshu777.runner

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LlamaPreflightResultTest {
    @Test
    fun malformedNativePayloadBecomesUnavailable() {
        val result = decodeLlamaPreflight(longArrayOf(1L))

        assertEquals(
            LlamaPreflightReason.MALFORMED_NATIVE_PAYLOAD,
            assertIs<LlamaPreflightResult.Unavailable>(result).reason,
        )
    }

    @Test
    fun successPreservesPerPoolBreakdown() {
        val result = assertIs<LlamaPreflightResult.Fit>(
            decodeLlamaPreflight(successPayload(hostModel = 1_000L, gpuModel = 2_000L)),
        )

        assertEquals(4_096, result.report.fittedContextTokens)
        assertEquals(24, result.report.fittedGpuLayers)
        assertEquals(2, result.report.memoryPools.size)
        assertEquals(LlamaMemoryPoolKind.DISCRETE_GPU, result.report.memoryPools[0].kind)
        assertEquals(2_000L, result.report.memoryPools[0].modelBytes)
        assertEquals(LlamaMemoryPoolKind.HOST, result.report.memoryPools[1].kind)
        assertEquals(1_000L, result.report.memoryPools[1].modelBytes)
    }

    @Test
    fun negativeNativeByteCountBecomesInvalidModel() {
        val payload = successPayload(hostModel = 1_000L, gpuModel = 2_000L)
        payload[8] = -1L

        val result = decodeLlamaPreflight(payload)

        assertEquals(
            LlamaPreflightReason.INVALID_NATIVE_SIZE,
            assertIs<LlamaPreflightResult.InvalidModel>(result).reason,
        )
    }

    @Test
    fun unorderedPoolRecordsBecomeUnavailable() {
        val payload = successPayload(hostModel = 1_000L, gpuModel = 2_000L)
        payload[5] = 1L

        assertIs<LlamaPreflightResult.Unavailable>(decodeLlamaPreflight(payload))
    }

    @Test
    fun declaredPoolCountMustMatchFixedPayloadLayout() {
        val payload = successPayload(hostModel = 1_000L, gpuModel = 2_000L)
        payload[3] = LLAMA_PREFLIGHT_MAX_POOLS.toLong() + 1L

        assertIs<LlamaPreflightResult.Unavailable>(decodeLlamaPreflight(payload))
    }

    @Test
    fun nativeNoFitAndParserErrorRemainDistinct() {
        assertIs<LlamaPreflightResult.NoFit>(
            decodeLlamaPreflight(longArrayOf(1L, 0L, 0L, 0L)),
        )
        assertIs<LlamaPreflightResult.InvalidModel>(
            decodeLlamaPreflight(longArrayOf(2L, 0L, 0L, 0L)),
        )
        assertIs<LlamaPreflightResult.Unavailable>(
            decodeLlamaPreflight(longArrayOf(3L, 0L, 0L, 0L)),
        )
    }

    @Test
    fun invalidPathOrConfigIsRejectedBeforeNativePreflight() {
        listOf(
            "" to NativeRunnerConfig(),
            "bad\u0000path.gguf" to NativeRunnerConfig(),
            "/models/model.gguf" to NativeRunnerConfig(nUbatch = 513, nBatch = 512),
        ).forEach { (path, config) ->
            var enteredNative = false

            val result = runLlamaPreflight(path, config) { _, _ ->
                enteredNative = true
                successPayload(hostModel = 1_000L, gpuModel = 2_000L)
            }

            assertFalse(enteredNative)
            assertEquals(
                LlamaPreflightReason.INVALID_ARGUMENT,
                assertIs<LlamaPreflightResult.InvalidModel>(result).reason,
            )
        }
    }

    @Test
    fun overlongArchitectureIsRejectedBeforeNativeEntry() {
        var enteredNative = false

        val result = probeNativeModelFeatures("a".repeat(65), "Q4_K_M") { _, _ ->
            enteredNative = true
            supportedFeaturePayload()
        }

        assertFalse(enteredNative)
        assertEquals(NativeFeatureState.UNKNOWN, result.architecture)
        assertEquals(NativeFeatureProbeReason.INVALID_LABEL, result.reason)
    }

    @Test
    fun nonAsciiOrControlLabelsAreRejectedBeforeNativeEntry() {
        listOf(
            "llam\u00e1" to "Q4_K_M",
            "llama\n" to "Q4_K_M",
            "llama" to "Q4_K_M\u007f",
        ).forEach { (architecture, quantization) ->
            var enteredNative = false

            val result = probeNativeModelFeatures(architecture, quantization) { _, _ ->
                enteredNative = true
                supportedFeaturePayload()
            }

            assertFalse(enteredNative)
            assertEquals(NativeFeatureProbeReason.INVALID_LABEL, result.reason)
        }
    }

    @Test
    fun knownUpstreamArchitectureIsSupportedWithCurrentEngineVersion() {
        val result = probeNativeModelFeatures("llama", "Q4_K_M") { architecture, quantization ->
            assertEquals("llama", architecture)
            assertEquals("Q4_K_M", quantization)
            supportedFeaturePayload(buildNumber = 42L)
        }

        assertEquals(NativeFeatureState.SUPPORTED, result.architecture)
        assertEquals(NativeFeatureState.SUPPORTED, result.quantization)
        assertEquals("llama.cpp-b42", result.engineVersion)
        assertEquals(null, result.reason)
    }

    @Test
    fun floatingPointAliasesAreNormalizedBeforeNativeEntry() {
        listOf(
            "FP16" to "F16",
            "F16" to "F16",
            "FP32" to "F32",
            "F32" to "F32",
        ).forEach { (input, expectedNativeLabel) ->
            val result = probeNativeModelFeatures("llama", input) { _, quantization ->
                assertEquals(expectedNativeLabel, quantization)
                supportedFeaturePayload()
            }

            assertEquals(NativeFeatureState.SUPPORTED, result.quantization)
        }
    }

    @Test
    fun unmappedQuantizationPassesToNativeAndRemainsUnknown() {
        val result = probeNativeModelFeatures("llama", "future_quant") { _, quantization ->
            assertEquals("future_quant", quantization)
            longArrayOf(0, 2, 123)
        }

        assertEquals(NativeFeatureState.UNKNOWN, result.quantization)
    }

    @Test
    fun unknownArchitectureRemainsUnsupportedWithoutThrowing() {
        val result = probeNativeModelFeatures("future_arch", null) { _, _ ->
            longArrayOf(1L, 2L, 42L)
        }

        assertEquals(NativeFeatureState.UNSUPPORTED, result.architecture)
        assertEquals(NativeFeatureState.UNKNOWN, result.quantization)
        assertEquals("llama.cpp-b42", result.engineVersion)
    }

    @Test
    fun malformedFeaturePayloadRemainsUnknown() {
        val result = decodeNativeModelFeatureSupport(longArrayOf(Long.MAX_VALUE))

        assertEquals(NativeFeatureState.UNKNOWN, result.architecture)
        assertEquals(NativeFeatureState.UNKNOWN, result.quantization)
        assertEquals(NativeFeatureProbeReason.MALFORMED_NATIVE_PAYLOAD, result.reason)
    }

    @Test
    fun backendRegistryDecoderPreservesBoundedTypedMemory() {
        val decoded = decodeNativeBackendCapabilities(
            longArrayOf(
                2L,
                0L, 0L, 10_000L, 20_000L,
                2L, 2L, 30_000L, 40_000L,
            ),
        )

        assertEquals(2, decoded.size)
        assertEquals(NativeBackendKind.CPU, decoded[0].kind)
        assertEquals(NativeBackendDeviceType.CPU, decoded[0].deviceType)
        assertEquals(10_000L, decoded[0].freeBytes)
        assertEquals(NativeBackendKind.METAL, decoded[1].kind)
        assertEquals(NativeBackendDeviceType.INTEGRATED_GPU, decoded[1].deviceType)
        assertTrue(decoded.all { it.totalBytes != null })
    }

    @Test
    fun backendRegistryDecoderCarriesBoundedCanonicalDeviceIdentity() {
        val payload = longArrayOf(
            1L,
            NativeBackendKind.METAL.ordinal.toLong(),
            NativeBackendDeviceType.INTEGRATED_GPU.ordinal.toLong(),
            30_000L,
            40_000L,
            6L,
            *packIdentity("metal0"),
        )

        val decoded = decodeNativeBackendCapabilities(payload)

        assertTrue(decoded.single().toString().contains("deviceIdentity=metal0"))
    }

    @Test
    fun malformedBackendRegistryFailsClosed() {
        assertTrue(decodeNativeBackendCapabilities(longArrayOf(1L, 0L)).isEmpty())
        assertTrue(
            decodeNativeBackendCapabilities(
                longArrayOf(1L, 0L, 0L, -2L, 20L),
            ).isEmpty(),
        )
        assertTrue(
            decodeNativeBackendCapabilities(
                longArrayOf(1L, 4_294_967_296L, 0L, 10L, 20L),
            ).isEmpty(),
        )
    }

    private fun supportedFeaturePayload(buildNumber: Long = 1L): LongArray =
        longArrayOf(0L, 0L, buildNumber)

    private fun packIdentity(value: String): LongArray = LongArray(8).also { packed ->
        value.encodeToByteArray().forEachIndexed { index, byte ->
            packed[index / 8] = packed[index / 8] or
                ((byte.toLong() and 0xffL) shl ((index % 8) * 8))
        }
    }

    private fun successPayload(hostModel: Long, gpuModel: Long): LongArray = longArrayOf(
        0L, 4_096L, 24L, 2L,
        1L, 0L, gpuModel, 300L, 400L, 8_000L, 16_000L,
        0L, 1L, hostModel, 500L, 600L, 32_000L, 64_000L,
    )
}
