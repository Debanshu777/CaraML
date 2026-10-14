package com.debanshu777.runner

import java.io.File
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LlamaPreflightParityTest {
    @Test
    fun verifiedTinyModelAndCorruptInputMatchNativePreflightOutcomes() {
        if (!nativeParityEnabled()) return

        val fixtureDir = requiredDirectory("caraml.native.fixture.dir")
        val nativeDir = requiredDirectory("caraml.native.lib.dir")
        val tinyModel = fixtureDir.resolve("$TINY_STORIES_SHA256.gguf")
        val corruptModel = fixtureDir.resolve("$CORRUPT_LLAMA_SHA256.gguf")
        assertTrue(tinyModel.isFile, "verified tiny GGUF fixture is missing")
        assertTrue(corruptModel.isFile, "verified corrupt GGUF fixture is missing")

        val runner = LlamaRunner()
        try {
            runner.initialize(nativeDir.absolutePath)
            val config = NativeRunnerConfig(
                nCtx = 128,
                nCtxMin = 64,
                nThreads = 2,
                nBatch = 32,
                nUbatch = 32,
                nGpuLayers = 0,
                autoFit = false,
            )
            assertIs<LlamaPreflightResult.Fit>(
                runner.preflightModel(tinyModel.absolutePath, config),
            )
            assertTrue(
                runner.loadModel(tinyModel.absolutePath, config),
                "a fixture accepted by preflight must also load with the identical config",
            )
            runner.unloadModel()
            assertIs<LlamaPreflightResult.InvalidModel>(
                runner.preflightModel(corruptModel.absolutePath, NativeRunnerConfig()),
            )
        } finally {
            runner.shutdown()
        }
    }

    private fun nativeParityEnabled(): Boolean =
        System.getenv("CARAML_NATIVE_PARITY")?.equals("true", ignoreCase = true) == true

    private fun requiredDirectory(property: String): File {
        val path = requireNotNull(System.getProperty(property)) { "$property is required for native parity" }
        return File(path).also { require(it.isDirectory) { "$property is not a directory" } }
    }

    private companion object {
        const val TINY_STORIES_SHA256 = "270cba1bd5109f42d03350f60406024560464db173c0e387d91f0426d3bd256d"
        const val CORRUPT_LLAMA_SHA256 = "6d7811831369848469bfa8d62686c7b42baa9e4fcda7555e2a5353f5c5edd47a"
    }
}
