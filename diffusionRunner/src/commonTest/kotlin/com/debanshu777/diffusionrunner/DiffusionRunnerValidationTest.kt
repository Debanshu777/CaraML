package com.debanshu777.diffusionrunner

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith

class DiffusionRunnerValidationTest {
    @Test
    fun segmentedExecutionDefaultsAreConservativeAndVideoReportsEffectiveFps() {
        val config = DiffusionModelConfig(modelPath = "/models/model.gguf")

        assertFalse(config.segmentedCompute)
        assertFalse(config.prefetch)
        assertEquals(24, VideoGenResult(emptyList(), effectiveFps = 24).effectiveFps)
    }

    @Test
    fun prefetchRequiresSegmentedCompute() {
        assertFailsWith<IllegalArgumentException> {
            validateModelConfig(modelConfig(segmentedCompute = false, prefetch = true))
        }
    }

    @Test
    fun runtimeBackendContractIsClosedAndDefaultsToCpu() {
        assertEquals(
            listOf(
                DiffusionRuntimeBackend.CPU,
                DiffusionRuntimeBackend.METAL,
                DiffusionRuntimeBackend.VULKAN,
                DiffusionRuntimeBackend.CUDA,
            ),
            DiffusionRuntimeBackend.entries,
        )
        assertEquals(
            listOf(0, 1, 2, 3),
            DiffusionRuntimeBackend.entries.map { it.nativeValue },
        )
        assertEquals(DiffusionRuntimeBackend.CPU, modelConfig().runtimeBackend)
    }

    @Test
    fun rejectsUnboundedOrMalformedMaxVramSpec() {
        val invalidSpecs = listOf(
            "../../bad",
            "1e3",
            "NaN",
            "Infinity",
            "cuda0=",
            "=4",
            "cuda0=4,cuda0=6",
            "cuda0 =4",
            "cuda0=4,",
            "cuda/0=4",
            "future0=4",
            "1".repeat(257),
        )

        invalidSpecs.forEach { maxVram ->
            assertFailsWith<IllegalArgumentException>("maxVram=$maxVram") {
                validateModelConfig(modelConfig(maxVram = maxVram))
            }
        }
    }

    @Test
    fun acceptsDocumentedBoundedMaxVramGrammar() {
        listOf(
            "",
            "0",
            "-1",
            "6",
            "6.5",
            "cuda0=6,vulkan0=2",
            "default=-1,metal=8",
        ).forEach { maxVram ->
            validateModelConfig(modelConfig(maxVram = maxVram))
        }
    }

    @Test
    fun rejectsUnsafeModelConfigBeforeNativeEntry() {
        val invalid = listOf(
            modelConfig(modelPath = "bad\u0000path.gguf"),
            modelConfig(modelPath = "x".repeat(4_097)),
            modelConfig(vaePath = "bad\u0000vae.gguf"),
            modelConfig(nThreads = 0),
            modelConfig(nThreads = 1_025),
            modelConfig(wtype = 42),
            modelConfig(prediction = 6),
            modelConfig(flowShift = Float.NaN),
            modelConfig(segmentedCompute = true, maxVram = "0"),
            modelConfig(segmentedCompute = true, maxVram = "cuda0=0.0"),
        )

        invalid.forEach { config ->
            assertFailsWith<IllegalArgumentException> {
                validateModelConfig(config)
            }
        }
    }

    @Test
    fun rejectsImageRequestsThatCanExhaustOrCorruptNativeGeneration() {
        val unsafe = listOf(
            ImageGenParams(prompt = "x", width = 4_104),
            ImageGenParams(prompt = "x", width = 4_096, height = 4_096 + 8),
            ImageGenParams(prompt = "x", steps = 151),
            ImageGenParams(prompt = "x", cfgScale = Float.NaN),
            ImageGenParams(prompt = "x", cfgScale = Float.POSITIVE_INFINITY),
            ImageGenParams(prompt = "x".repeat(16_385)),
            ImageGenParams(
                prompt = "x",
                loraPaths = List(33) { "/models/lora-$it.gguf" },
                loraStrengths = List(33) { 1f },
            ),
            ImageGenParams(
                prompt = "x",
                loraPaths = listOf("/models/lora.gguf"),
                loraStrengths = listOf(Float.NaN),
            ),
        )

        unsafe.forEach { params ->
            assertFailsWith<IllegalArgumentException> {
                validateImageGenParams(params)
            }
        }
    }

    @Test
    fun rejectsUnsafeVideoFrameBudgets() {
        listOf(0, 257).forEach { frames ->
            assertFailsWith<IllegalArgumentException> {
                validateVideoGenParams(VideoGenParams(prompt = "x", videoFrames = frames))
            }
        }

        assertFailsWith<IllegalArgumentException> {
            validateVideoGenParams(
                VideoGenParams(
                    prompt = "x",
                    width = 4_096,
                    height = 4_096,
                    videoFrames = 2,
                )
            )
        }
    }

    @Test
    fun acceptsConservativeImageAndVideoRequests() {
        validateImageGenParams(
            ImageGenParams(
                prompt = "render a mountain",
                width = 1_024,
                height = 1_024,
                steps = 40,
                cfgScale = 7f,
            )
        )
        validateVideoGenParams(
            VideoGenParams(
                prompt = "a slow camera pan",
                width = 512,
                height = 512,
                videoFrames = 64,
                steps = 30,
                cfgScale = 6f,
            )
        )
    }

    private fun modelConfig(
        modelPath: String = "/models/model.gguf",
        vaePath: String = "",
        nThreads: Int = -1,
        wtype: Int = -1,
        prediction: Int = -1,
        flowShift: Float = Float.POSITIVE_INFINITY,
        maxVram: String = "",
        segmentedCompute: Boolean = false,
        prefetch: Boolean = false,
    ) = DiffusionModelConfig(
        modelPath = modelPath,
        vaePath = vaePath,
        nThreads = nThreads,
        wtype = wtype,
        prediction = prediction,
        flowShift = flowShift,
        maxVram = maxVram,
        segmentedCompute = segmentedCompute,
        prefetch = prefetch,
    )
}
