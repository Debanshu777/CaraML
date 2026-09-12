package com.debanshu777.diffusionrunner

import java.io.File
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DiffusionPreflightParityTest {
    @Test
    fun corruptInputFailsClosedAndReleasesTransientNativeState() {
        if (!nativeParityEnabled()) return

        val fixtureDir = requiredDirectory("caraml.native.fixture.dir")
        val nativeDir = requiredDirectory("caraml.native.lib.dir")
        val corruptModel = fixtureDir.resolve("$CORRUPT_DIFFUSION_SHA256.safetensors")
        assertTrue(corruptModel.isFile, "verified corrupt diffusion fixture is missing")

        val runner = DiffusionRunner()
        try {
            runner.initialize(nativeDir.absolutePath)
            repeat(2) {
                assertIs<DiffusionPreflightResult.InvalidModel>(
                    runner.preflightModel(DiffusionModelConfig(modelPath = corruptModel.absolutePath)),
                )
            }
        } finally {
            runner.release()
        }
    }

    private fun nativeParityEnabled(): Boolean =
        System.getenv("CARAML_NATIVE_PARITY")?.equals("true", ignoreCase = true) == true

    private fun requiredDirectory(property: String): File {
        val path = requireNotNull(System.getProperty(property)) { "$property is required for native parity" }
        return File(path).also { require(it.isDirectory) { "$property is not a directory" } }
    }

    private companion object {
        const val CORRUPT_DIFFUSION_SHA256 = "f7b42800f307f3bab038f325218b57a683cce41e35b0353f6820dcf260d767ad"
    }
}
