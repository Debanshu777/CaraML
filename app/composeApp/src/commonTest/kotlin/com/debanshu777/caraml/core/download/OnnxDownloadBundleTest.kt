package com.debanshu777.caraml.core.download

import com.debanshu777.caraml.core.download.transfer.DownloadArtifactIdentity
import com.debanshu777.caraml.core.download.transfer.DownloadMetadataDTO
import kotlin.test.*

class OnnxDownloadBundleTest {
    private fun metadata(path: String, revision: String = "a".repeat(40)) = DownloadMetadataDTO(
        artifact = requireNotNull(DownloadArtifactIdentity.create("owner/model", revision, path, "b".repeat(40), 10)),
        logicalRole = "model", sizeBytes = 10, author = "owner", libraryName = "onnx", pipelineTag = "text-generation",
    )

    @Test fun onePrimaryOwnsAllSidecarsInOneImmutableGeneration() {
        val requests = assertNotNull(onnxDownloadRequests(listOf(metadata("tokenizer.json"), metadata("onnx/model_int8.onnx_data"),
            metadata("config.json"), metadata("onnx/model_int8.onnx"))))
        assertEquals("onnx/model_int8.onnx", requests.single { it.primary }.metadata.layoutRelativePath)
        assertEquals(1, requests.map { it.metadata.bundleId }.distinct().size)
        assertEquals(1, requests.map { it.metadata.generationRootRelativePath }.distinct().size)
        assertEquals(4, requests.map { it.metadata.logicalRole }.distinct().size)
        assertTrue(requests.all { it.metadata.usesImmutableStorageLayout })
    }

    @Test fun incompleteOrMixedRevisionBundlesCannotBeEnqueued() {
        assertNull(onnxDownloadRequests(listOf(metadata("model.onnx"))))
        assertNull(onnxDownloadRequests(listOf(metadata("model.onnx"), metadata("config.json"), metadata("tokenizer.json", "c".repeat(40)))))
        assertNull(onnxDownloadRequests(listOf(metadata("model.onnx"), metadata("model_q4.onnx"), metadata("config.json"), metadata("tokenizer.json"))))
    }
}
