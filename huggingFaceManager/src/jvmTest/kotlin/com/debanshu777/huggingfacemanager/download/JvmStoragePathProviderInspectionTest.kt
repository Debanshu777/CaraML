package com.debanshu777.huggingfacemanager.download

import java.nio.file.Files
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertNull

class JvmStoragePathProviderInspectionTest {
    @Test
    fun symlinkedModelRootOutsideCanonicalModelsParentIsRejected() {
        val appDirectory = Files.createTempDirectory("caraml-storage-app-")
        val outsideRoot = Files.createTempDirectory("caraml-storage-outside-")
        val modelFile = outsideRoot.resolve("model.gguf")
        modelFile.writeBytes(byteArrayOf(1, 2, 3))
        val ownerDirectory = appDirectory.resolve("models/owner")
        Files.createDirectories(ownerDirectory)
        Files.createSymbolicLink(ownerDirectory.resolve("model"), outsideRoot)
        val provider = JvmStoragePathProvider(appDirectory.toFile())

        val pathThroughSymlink = ownerDirectory.resolve("model/model.gguf")
        assertNull(provider.inspectDownloadedArtifact("owner/model", pathThroughSymlink.toString()))
    }
}
