package com.debanshu777.diffusionrunner

import kotlin.test.Test
import kotlin.test.assertFailsWith

class DiffusionRunnerValidationTest {
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
            modelConfig(streamLayers = true, maxVram = "0"),
            modelConfig(streamLayers = true, maxVram = "cuda0=0.0"),
        )

        invalid.forEach { config ->
            assertFailsWith<IllegalArgumentException> {
                validateModelConfig(config)
            }
        }
    }

    private fun modelConfig(
        modelPath: String = "/models/model.gguf",
        vaePath: String = "",
        nThreads: Int = -1,
        wtype: Int = -1,
        prediction: Int = -1,
        flowShift: Float = Float.POSITIVE_INFINITY,
        maxVram: String = "",
        streamLayers: Boolean = false,
    ) = DiffusionModelConfig(
        modelPath = modelPath,
        vaePath = vaePath,
        nThreads = nThreads,
        wtype = wtype,
        prediction = prediction,
        flowShift = flowShift,
        maxVram = maxVram,
        streamLayers = streamLayers,
    )
}
