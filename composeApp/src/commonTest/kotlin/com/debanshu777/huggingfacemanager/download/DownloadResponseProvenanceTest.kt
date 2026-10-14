package com.debanshu777.huggingfacemanager.download

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DownloadResponseProvenanceTest {
    @Test
    fun acceptedResponsePersistsActualNormalizedProvenanceWithoutSignedData() {
        val provenance = DownloadResponseProvenance.validate(
            "https://cdn-lfs.huggingface.co/path/model.gguf?Signature=secret#fragment",
            206,
        )

        assertEquals("https://cdn-lfs.huggingface.co", provenance?.origin)
        assertEquals(206, provenance?.statusCode)
    }

    @Test
    fun invalidResponseCannotProduceRecoverableProvenance() {
        assertNull(DownloadResponseProvenance.validate("http://huggingface.co/model.gguf", 200))
        assertNull(DownloadResponseProvenance.validate("https://huggingface.co:444/model.gguf", 200))
        assertNull(DownloadResponseProvenance.validate("https://attacker.example/model.gguf", 200))
        assertNull(DownloadResponseProvenance.validate("https://huggingface.co/model.gguf", 404))
        assertNull(DownloadResponseProvenance.validate("not a url", 200))
    }
}
