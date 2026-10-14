package com.debanshu777.caraml.features.modelhub.domain

import com.debanshu777.caraml.core.recommendation.AssessmentReason
import com.debanshu777.caraml.core.recommendation.BrowseFitEstimate
import com.debanshu777.caraml.core.recommendation.BrowseResourceFit
import com.debanshu777.caraml.core.recommendation.Compatibility
import com.debanshu777.caraml.core.recommendation.PerformanceEstimate
import com.debanshu777.caraml.core.recommendation.ModelFileIdentity
import com.debanshu777.huggingfacemanager.download.DownloadArtifactIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BrowseVariantSelectionTest {
    @Test
    fun selectedPathProjectsItsOwnFitAndExactActionTarget() {
        val small = variant("small.gguf", "Small", 1_000L, BrowseResourceFit.LIKELY_FIT)
        val large = variant("large.gguf", "Large", 9_000L, BrowseResourceFit.TOO_LARGE)

        val selected = projectBrowseSelection(listOf(small, large), "large.gguf")

        assertEquals(listOf("large.gguf"), selected?.filePaths)
        assertEquals("Large", selected?.displayName)
        assertEquals(BrowseResourceFit.TOO_LARGE, selected?.estimate?.memoryFit)
        assertEquals(9_000L, selected?.estimate?.downloadBytes)
    }

    @Test
    fun selectingAnyShardProjectsWholeConfigurationAndExactGroupGuardRejectsPartialMixedAndStaleIdentity() {
        val small = variant("small.gguf", "Small", 1_000L, BrowseResourceFit.LIKELY_FIT)
        val shardIdentities = listOf(
            identity("model-00001-of-00002.gguf", 6_000L, "a".repeat(40)),
            identity("model-00002-of-00002.gguf", 6_000L, "b".repeat(40)),
        )
        val shards = BrowseVariantUiState(
            stableIdentity = "shards",
            displayName = "Sharded",
            filePaths = shardIdentities.map { it.path },
            fileIdentities = shardIdentities,
            estimate = estimate(12_000L, BrowseResourceFit.TIGHT_FIT),
        )

        val selectedShard = projectBrowseSelection(listOf(small, shards), "model-00002-of-00002.gguf")
        assertEquals("shards", selectedShard?.stableIdentity)
        assertEquals(shards.filePaths, selectedShard?.filePaths)
        assertEquals(BrowseResourceFit.TIGHT_FIT, selectedShard?.estimate?.memoryFit)
        val exactArtifacts = shardIdentities.map { identity ->
            DownloadArtifactIdentity.create(
                repositoryId = identity.repositoryId,
                immutableRevision = identity.revision,
                relativePath = identity.path,
                remoteObjectId = identity.gitOid,
                expectedBytes = identity.sizeBytes,
            )!!
        }
        assertTrue(matchesExactBrowseGroup(shards, exactArtifacts))
        assertFalse(matchesExactBrowseGroup(shards, exactArtifacts.drop(1)))
        assertFalse(
            matchesExactBrowseGroup(
                shards,
                listOf(
                    exactArtifacts.first(),
                    artifact("e".repeat(40), "model-00002-of-00002.gguf", "b".repeat(40), 7_000L),
                ),
            ),
        )
        assertFalse(
            matchesExactBrowseGroup(
                shards,
                listOf(
                    exactArtifacts.first(),
                    artifact("e".repeat(40), "other-00002-of-00002.gguf", "f".repeat(40), 6_000L),
                ),
            ),
        )
        // A same-basename local copy from an older revision is not a member of this group.
        assertFalse(
            matchesExactBrowseGroup(
                shards,
                listOf(
                    exactArtifacts.first(),
                    artifact("c".repeat(40), "model-00002-of-00002.gguf", "b".repeat(40), 6_000L),
                ),
            ),
        )
        assertNull(projectBrowseSelection(listOf(small), "unknown.gguf"))
    }

    private fun variant(path: String, name: String, size: Long, fit: BrowseResourceFit): BrowseVariantUiState {
        val identity = identity(path, size, "d".repeat(40))
        return BrowseVariantUiState(path, name, listOf(path), listOf(identity), estimate(size, fit))
    }

    private fun identity(path: String, size: Long, oid: String) = ModelFileIdentity(
        repositoryId = "org/model",
        revision = "e".repeat(40),
        path = path,
        sizeBytes = size,
        gitOid = oid,
        lfsOid = null,
        xetHash = null,
        evidence = emptyList(),
    )

    private fun artifact(revision: String, path: String, remoteObjectId: String, bytes: Long) =
        DownloadArtifactIdentity.create(
            repositoryId = "org/model",
            immutableRevision = revision,
            relativePath = path,
            remoteObjectId = remoteObjectId,
            expectedBytes = bytes,
        )!!

    private fun estimate(size: Long, fit: BrowseResourceFit) = BrowseFitEstimate(
        compatibility = Compatibility.Unknown(listOf(AssessmentReason.GGUF_VERSION_UNKNOWN)),
        memoryFit = fit,
        storageFit = BrowseResourceFit.UNKNOWN,
        memory = null,
        storageBytes = null,
        downloadBytes = size,
        performance = PerformanceEstimate.Unknown(AssessmentReason.SPEED_NOT_VERIFIED),
        resourceSnapshotFresh = true,
        resourceTimestampEpochMs = 1L,
        reasons = emptyList(),
        evidence = emptyList(),
    )
}
