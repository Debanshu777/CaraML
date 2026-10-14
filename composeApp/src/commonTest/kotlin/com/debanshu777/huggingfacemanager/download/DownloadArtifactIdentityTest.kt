package com.debanshu777.huggingfacemanager.download

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class DownloadArtifactIdentityTest {
    @Test
    fun bundleDigestIsIndependentOfArtifactCollectionOrder() {
        val first = identity(
            repositoryId = "org/model",
            revision = "a".repeat(40),
            path = "model.gguf",
            remoteObjectId = "sha256:${"b".repeat(64)}",
            expectedBytes = 10L,
        )
        val second = identity(
            repositoryId = "shared/component",
            revision = "c".repeat(40),
            path = "weights/model.safetensors",
            remoteObjectId = "sha256:${"d".repeat(64)}",
            expectedBytes = 20L,
        )

        val forward = assertNotNull(artifactBundleId(listOf(first, second)))

        assertEquals(forward, artifactBundleId(listOf(second, first)))
    }

    @Test
    fun duplicateRepositoryRevisionPathCoordinateIsRejectedEvenWhenHashedFieldsDiffer() {
        val first = identity(
            repositoryId = "org/model",
            revision = "a".repeat(40),
            path = "model.gguf",
            remoteObjectId = "sha256:${"b".repeat(64)}",
            expectedBytes = 10L,
        )
        val conflicting = identity(
            repositoryId = first.repositoryId,
            revision = first.immutableRevision,
            path = first.relativePath,
            remoteObjectId = "sha256:${"c".repeat(64)}",
            expectedBytes = 11L,
        )

        assertEquals(null, artifactBundleId(listOf(first, conflicting)))
        assertEquals(null, artifactBundleId(listOf(conflicting, first)))
    }

    private fun identity(
        repositoryId: String,
        revision: String,
        path: String,
        remoteObjectId: String,
        expectedBytes: Long,
    ): DownloadArtifactIdentity = requireNotNull(
        DownloadArtifactIdentity.create(
            repositoryId = repositoryId,
            immutableRevision = revision,
            relativePath = path,
            remoteObjectId = remoteObjectId,
            expectedBytes = expectedBytes,
        ),
    )
}
