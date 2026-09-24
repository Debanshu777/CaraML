package com.debanshu777.diffusionrunner

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiffusionPreflightResultTest {
    @Test
    fun preflightPayloadMustContainAllDeclaredComponents() {
        val payload = successfulPreflightPayload().toMutableList().apply {
            this[6] = (1L shl DiffusionComponentRole.DIFFUSION_MODEL.ordinal) or
                (1L shl DiffusionComponentRole.VAE.ordinal)
            this[8] = 1L
            repeat(DIFFUSION_PREFLIGHT_COMPONENT_FIELDS) {
                removeAt(DIFFUSION_PREFLIGHT_HEADER_FIELDS + DIFFUSION_PREFLIGHT_COMPONENT_FIELDS)
            }
        }.toLongArray()

        val result = decodeDiffusionPreflight(payload)

        assertEquals(
            DiffusionPreflightReason.INCOMPLETE_COMPONENT_EVIDENCE,
            assertIs<DiffusionPreflightResult.InvalidModel>(result).reason,
        )
    }

    @Test
    fun malformedOrUnboundedPayloadFailsClosed() {
        assertIs<DiffusionPreflightResult.Unavailable>(decodeDiffusionPreflight(longArrayOf(0L)))

        val tooManyComponents = successfulPreflightPayload().also {
            it[8] = DIFFUSION_PREFLIGHT_MAX_COMPONENTS.toLong() + 1L
        }
        assertIs<DiffusionPreflightResult.Unavailable>(decodeDiffusionPreflight(tooManyComponents))

        val tooManyBackends = successfulPreflightPayload().also {
            it[9] = DIFFUSION_PREFLIGHT_MAX_BACKENDS.toLong() + 1L
        }
        assertIs<DiffusionPreflightResult.Unavailable>(decodeDiffusionPreflight(tooManyBackends))
    }

    @Test
    fun negativeNativeSizeBecomesInvalidModel() {
        val payload = successfulPreflightPayload().also {
            it[DIFFUSION_PREFLIGHT_HEADER_FIELDS + 4] = -1L
        }

        assertEquals(
            DiffusionPreflightReason.INVALID_NATIVE_SIZE,
            assertIs<DiffusionPreflightResult.InvalidModel>(decodeDiffusionPreflight(payload)).reason,
        )
    }

    @Test
    fun successPreservesComponentPlacementsBackendBudgetsAndModelEvidence() {
        val fit = assertIs<DiffusionPreflightResult.Fit>(
            decodeDiffusionPreflight(successfulPreflightPayload()),
        ).report

        assertEquals(DiffusionArchitecture.SDXL, fit.architecture)
        assertEquals(DiffusionQuantization.Q4_K, fit.quantization)
        assertEquals(DiffusionMemoryConfidence.MEDIUM, fit.memoryConfidence)
        assertTrue(fit.segmentedCompute)
        assertFalse(fit.prefetch)
        assertEquals(2, fit.components.size)
        assertEquals(DiffusionComponentRole.DIFFUSION_MODEL, fit.components[0].sourceRole)
        assertEquals(0, fit.components[0].sourceOrdinal)
        assertEquals(DiffusionComponentRole.DIFFUSION_MODEL, fit.components[0].subdivisionRole)
        assertEquals(4_000L, fit.components[0].parameterBytes)
        assertEquals(DiffusionRuntimePlacement.GPU, fit.components[0].runtimePlacement)
        assertEquals(1L, fit.components[0].runtimeBackendMask)
        assertEquals(DiffusionParameterPlacement.DISK, fit.components[0].parameterPlacement)
        assertEquals(DiffusionComponentRole.VAE, fit.components[1].sourceRole)
        assertEquals(1, fit.components[1].sourceOrdinal)
        assertEquals(DiffusionComponentRole.VAE, fit.components[1].subdivisionRole)
        assertEquals(DiffusionRuntimePlacement.CPU, fit.components[1].runtimePlacement)
        assertEquals(2, fit.backends.size)
        assertEquals(DiffusionBackendKind.METAL, fit.backends[0].kind)
        assertEquals(8_000L, fit.backends[0].budgetBytes)
        assertEquals(DiffusionBackendKind.CPU, fit.backends[1].kind)
    }

    @Test
    fun invalidBackendTopologyFailsClosed() {
        val budgetOverFree = successfulPreflightPayload().also {
            val offset = DIFFUSION_PREFLIGHT_HEADER_FIELDS +
                2 * DIFFUSION_PREFLIGHT_COMPONENT_FIELDS
            it[offset + 3] = 9_001L
        }
        assertEquals(
            9_001L,
            assertIs<DiffusionPreflightResult.Fit>(decodeDiffusionPreflight(budgetOverFree))
                .report.backends.first().budgetBytes,
        )

        val unknownBackendBit = successfulPreflightPayload().also {
            it[DIFFUSION_PREFLIGHT_HEADER_FIELDS + 6] = 1L shl 7
        }
        assertIs<DiffusionPreflightResult.Unavailable>(decodeDiffusionPreflight(unknownBackendBit))
    }

    @Test
    fun contradictorySourceRoleForOneOrdinalFailsClosed() {
        val payload = successfulPreflightPayload().also {
            val secondComponent = DIFFUSION_PREFLIGHT_HEADER_FIELDS +
                DIFFUSION_PREFLIGHT_COMPONENT_FIELDS
            it[secondComponent + 1] = 0L
        }

        assertEquals(
            DiffusionPreflightReason.INCOMPLETE_COMPONENT_EVIDENCE,
            assertIs<DiffusionPreflightResult.InvalidModel>(decodeDiffusionPreflight(payload)).reason,
        )
    }

    @Test
    fun invalidConfigNeverEntersNativePreflight() {
        var enteredNative = false

        val result = runDiffusionPreflight(
            DiffusionModelConfig(modelPath = "bad\u0000model.gguf"),
        ) {
            enteredNative = true
            successfulPreflightPayload()
        }

        assertFalse(enteredNative)
        assertEquals(
            DiffusionPreflightReason.INVALID_ARGUMENT,
            assertIs<DiffusionPreflightResult.InvalidModel>(result).reason,
        )
    }

    @Test
    fun featureProbeSupportsPinnedImageAndVideoArchitectures() {
        val image = probeDiffusionModelFeatures(
            architecture = "SDXL",
            quantization = "Q4_K_M",
            mode = DiffusionGenerationMode.IMAGE,
            nativeProbe = { architecture, quantization, mode ->
                assertEquals("SDXL", architecture)
                assertEquals("Q4_K", quantization)
                assertEquals(0, mode)
                longArrayOf(0L, 0L, 0L)
            },
            nativeVersion = { "stable-diffusion.cpp-v1" },
        )
        val video = probeDiffusionModelFeatures(
            architecture = "WAN_SMALL",
            quantization = "F16",
            mode = DiffusionGenerationMode.VIDEO,
            nativeProbe = { _, _, _ -> longArrayOf(0L, 0L, 0L) },
            nativeVersion = { "stable-diffusion.cpp-v1" },
        )

        assertEquals(DiffusionFeatureState.SUPPORTED, image.architecture)
        assertEquals(DiffusionFeatureState.SUPPORTED, image.quantization)
        assertEquals(DiffusionFeatureState.SUPPORTED, image.mode)
        assertEquals("stable-diffusion.cpp-v1", image.engineVersion)
        assertEquals(DiffusionFeatureState.SUPPORTED, video.architecture)
        assertEquals(DiffusionFeatureState.SUPPORTED, video.mode)
    }

    @Test
    fun featureProbeKeepsWrongModeAndUnknownArchitectureTyped() {
        val wrongMode = probeDiffusionModelFeatures(
            "SDXL",
            null,
            DiffusionGenerationMode.VIDEO,
            nativeProbe = { _, _, _ -> longArrayOf(0L, 2L, 1L) },
            nativeVersion = { "stable-diffusion.cpp-v1" },
        )
        val unknown = probeDiffusionModelFeatures(
            "future_arch",
            null,
            DiffusionGenerationMode.IMAGE,
            nativeProbe = { _, _, _ -> longArrayOf(1L, 2L, 2L) },
            nativeVersion = { "stable-diffusion.cpp-v1" },
        )

        assertEquals(DiffusionFeatureState.UNSUPPORTED, wrongMode.mode)
        assertEquals(DiffusionFeatureState.UNSUPPORTED, unknown.architecture)
        assertEquals(DiffusionFeatureState.UNKNOWN, unknown.quantization)
        assertNull(unknown.reason)
    }

    @Test
    fun unsafeFeatureLabelsNeverEnterNative() {
        listOf(
            "x".repeat(65) to "Q4_K_M",
            "SDXL\n" to "Q4_K_M",
            "SDXL" to "x".repeat(33),
            "SDXL" to "Q4_K_\u00c9",
        ).forEach { (architecture, quantization) ->
            var enteredNative = false
            val support = probeDiffusionModelFeatures(
                architecture,
                quantization,
                DiffusionGenerationMode.IMAGE,
                nativeProbe = { _, _, _ ->
                    enteredNative = true
                    longArrayOf(0L, 0L, 0L)
                },
                nativeVersion = { "stable-diffusion.cpp-v1" },
            )

            assertFalse(enteredNative)
            assertEquals(DiffusionFeatureProbeReason.INVALID_LABEL, support.reason)
        }
    }

    @Test
    fun malformedFeaturePayloadRemainsUnknown() {
        val support = decodeDiffusionModelFeatureSupport(null, "version")

        assertEquals(DiffusionFeatureState.UNKNOWN, support.architecture)
        assertEquals(DiffusionFeatureState.UNKNOWN, support.quantization)
        assertEquals(DiffusionFeatureState.UNKNOWN, support.mode)
        assertEquals(DiffusionFeatureProbeReason.MALFORMED_NATIVE_PAYLOAD, support.reason)
    }

    @Test
    fun backendRegistryDecoderPreservesOnlyBoundedTypedEvidence() {
        val capabilities = decodeDiffusionBackendCapabilities(
            longArrayOf(
                2L,
                DiffusionBackendKind.CPU.ordinal.toLong(), DiffusionBackendDeviceType.CPU.ordinal.toLong(), 10L, 20L,
                DiffusionBackendKind.METAL.ordinal.toLong(), DiffusionBackendDeviceType.INTEGRATED_GPU.ordinal.toLong(), 30L, 40L,
            ),
        )

        assertEquals(2, capabilities.size)
        assertEquals(DiffusionBackendKind.CPU, capabilities[0].kind)
        assertEquals(DiffusionBackendKind.METAL, capabilities[1].kind)
        assertEquals(30L, capabilities[1].freeBytes)
        assertTrue(decodeDiffusionBackendCapabilities(longArrayOf(1L, 0L)).isEmpty())
    }

    @Test
    fun backendRegistryDecoderCarriesBoundedCanonicalDeviceIdentity() {
        val capabilities = decodeDiffusionBackendCapabilities(
            longArrayOf(
                1L,
                DiffusionBackendKind.METAL.ordinal.toLong(),
                DiffusionBackendDeviceType.INTEGRATED_GPU.ordinal.toLong(),
                30L,
                40L,
                6L,
                *packIdentity("metal0"),
            ),
        )

        assertTrue(capabilities.single().toString().contains("deviceIdentity=metal0"))
    }

    private fun successfulPreflightPayload(): LongArray = longArrayOf(
        0L,
        DiffusionArchitecture.SDXL.ordinal.toLong(),
        DiffusionQuantization.Q4_K.ordinal.toLong(),
        DiffusionMemoryConfidence.MEDIUM.ordinal.toLong(),
        1L,
        0L,
        (1L shl DiffusionComponentRole.DIFFUSION_MODEL.ordinal) or
            (1L shl DiffusionComponentRole.VAE.ordinal),
        2L,
        2L,
        2L,
        DiffusionComponentRole.DIFFUSION_MODEL.ordinal.toLong(), 0L,
        DiffusionComponentRole.DIFFUSION_MODEL.ordinal.toLong(), 0L, 4_000L,
        DiffusionRuntimePlacement.GPU.ordinal.toLong(), 1L,
        DiffusionParameterPlacement.DISK.ordinal.toLong(),
        DiffusionComponentRole.VAE.ordinal.toLong(), 1L,
        DiffusionComponentRole.VAE.ordinal.toLong(), 1L, 1_000L,
        DiffusionRuntimePlacement.CPU.ordinal.toLong(), 0L,
        DiffusionParameterPlacement.CPU.ordinal.toLong(),
        DiffusionBackendKind.METAL.ordinal.toLong(),
        DiffusionBackendDeviceType.INTEGRATED_GPU.ordinal.toLong(),
        0L, 8_000L, 9_000L, 10_000L,
        DiffusionBackendKind.CPU.ordinal.toLong(),
        DiffusionBackendDeviceType.CPU.ordinal.toLong(),
        1L, 0L, 40_000L, 50_000L,
    )

    private fun packIdentity(value: String): LongArray = LongArray(8).also { packed ->
        value.encodeToByteArray().forEachIndexed { index, byte ->
            packed[index / 8] = packed[index / 8] or
                ((byte.toLong() and 0xffL) shl ((index % 8) * 8))
        }
    }
}
