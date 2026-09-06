package com.debanshu777.huggingfacemanager.download

import okio.ByteString.Companion.toByteString
import okio.fakefilesystem.FakeFileSystem
import okio.Path
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ArtifactBundleManifestStoreTest {
    @Test
    fun crossRepositoryBundleRequiresEveryExactComponentBeforeAndAfterRestart() {
        val fixture = BundleFixture()
        val entries = fixture.installBundle("a".repeat(40), "main", "vae")
        val store = fixture.bundleStore()

        store.publish(entries)
        assertEquals(2, store.readValidated()?.entries?.size)

        fixture.fs.write(fixture.vaeRoot / "vae.safetensors") { writeUtf8("substituted") }
        assertNull(fixture.bundleStore().readValidated())
    }

    @Test
    fun owningBundleManifestRecoversAfterEveryPublicationPhase() {
        ManifestJournalPhase.entries.forEach { crashPhase ->
            val fixture = BundleFixture(crashPhase.name)
            fixture.bundleStore().publish(fixture.installBundle("a".repeat(40), "old-main", "old-vae"))
            val replacement = fixture.installBundle("b".repeat(40), "new-main", "new-vae")
            val crashing = fixture.bundleStore { phase -> if (phase == crashPhase) throw BundleCrash() }

            assertFailsWith<BundleCrash> { crashing.publish(replacement) }
            repeat(3) { fixture.bundleStore().recover() }

            val recovered = assertNotNull(fixture.bundleStore().readValidated(), crashPhase.name)
            assertEquals(replacement.map { it.identity }.toSet(), recovered.entries.map { it.identity }.toSet())
        }
    }

    @Test
    fun invalidPreparedAggregateNeverDeletesTheStillValidPublishedBundle() {
        val fixture = BundleFixture("preserve-valid")
        val old = fixture.installBundle("a".repeat(40), "old-main", "old-vae")
        fixture.bundleStore().publish(old)
        val replacement = old.map { entry ->
            requireNotNull(
                ArtifactManifestEntry.create(
                    logicalRole = entry.logicalRole,
                    identity = entry.identity,
                    byteCount = entry.byteCount,
                    contentSha256 = entry.contentSha256,
                    bundleId = "f".repeat(64),
                    localRelativePath = entry.localRelativePath,
                ),
            )
        }
        val crashing = fixture.bundleStore { phase ->
            if (phase == ManifestJournalPhase.PREPARED) throw BundleCrash()
        }
        assertFailsWith<BundleCrash> { crashing.publish(replacement) }
        fixture.fs.write(fixture.mainRoot / "${ArtifactBundleManifestStore.MANIFEST_FILE_NAME}.part") {
            writeUtf8("invalid")
        }

        fixture.bundleStore().recover()

        assertNotNull(fixture.bundleStore().readValidated())
    }
}

private class BundleCrash : RuntimeException()

private class BundleFixture(suffix: String = "default") {
    val fs = FakeFileSystem()
    val mainRoot = "/models/org/main-$suffix".toPath()
    val vaeRoot = "/models/org/vae-$suffix".toPath()
    private val roots = mutableMapOf<String, Path>()

    init {
        fs.createDirectories(mainRoot)
        fs.createDirectories(vaeRoot)
        roots["org/main"] = mainRoot
        roots["org/vae"] = vaeRoot
    }

    fun installBundle(revision: String, mainValue: String, vaeValue: String): List<ArtifactManifestEntry> {
        val mainIdentity = identity("org/main", revision, "model.safetensors", mainValue.encodeToByteArray().size.toLong())
        val vaeIdentity = identity("org/vae", revision, "vae.safetensors", vaeValue.encodeToByteArray().size.toLong())
        val bundleId = requireNotNull(artifactBundleId(listOf(mainIdentity, vaeIdentity)))
        return listOf(
            install(mainRoot, mainIdentity, "model", mainValue, bundleId),
            install(vaeRoot, vaeIdentity, "vae", vaeValue, bundleId),
        )
    }

    fun bundleStore(observer: (ManifestJournalPhase) -> Unit = {}): ArtifactBundleManifestStore =
        ArtifactBundleManifestStore(mainRoot, fs, phaseObserver = observer) { entry ->
            val root = roots[entry.identity.repositoryId] ?: return@ArtifactBundleManifestStore false
            ArtifactManifestStore(root, fs).readValidated()?.entries?.any { installed ->
                installed.logicalRole == entry.logicalRole && installed.identity == entry.identity &&
                    installed.localRelativePath == entry.localRelativePath &&
                    installed.byteCount == entry.byteCount && installed.contentSha256 == entry.contentSha256
            } == true
        }

    private fun install(
        root: Path,
        identity: DownloadArtifactIdentity,
        role: String,
        value: String,
        bundleId: String,
    ): ArtifactManifestEntry {
        val bytes = value.encodeToByteArray()
        val entry = requireNotNull(
            ArtifactManifestEntry.create(role, identity, bytes.size.toLong(), bytes.toByteString().sha256().hex(), bundleId),
        )
        fs.write(root / "${identity.relativePath}.part") { write(bytes) }
        ArtifactManifestStore(root, fs).commit(identity.relativePath, entry)
        return entry
    }

    private fun identity(repo: String, revision: String, path: String, bytes: Long) = requireNotNull(
        DownloadArtifactIdentity.create(repo, revision, path, null, bytes),
    )
}
