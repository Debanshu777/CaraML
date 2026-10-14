package com.debanshu777.caraml.sdk.storage

import com.debanshu777.caraml.sdk.api.ArtifactDescriptor
import com.debanshu777.caraml.sdk.api.ArtifactId
import com.debanshu777.caraml.sdk.api.ArtifactLocation
import com.debanshu777.caraml.sdk.api.ArtifactRef
import com.debanshu777.caraml.sdk.api.ExperimentalCaraMLApi
import com.debanshu777.caraml.sdk.api.TaskId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import okio.Path.Companion.toPath
import okio.buffer
import okio.ByteString.Companion.toByteString
import okio.fakefilesystem.FakeFileSystem
import okio.use
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCaraMLApi::class)
class FileArtifactStoreTest {
    @Test
    fun commitPublishesBoundedFileAndLeaseFencesRemoval() = runTest {
        val fs = FakeFileSystem()
        val root = "/cache/models".toPath()
        val descriptor = artifact("weights.gguf", byteArrayOf(1, 2, 3, 4))
        val store = FileArtifactStore(root, fs, maxArtifactBytes = 8)
        assertFalse(fs.exists(root))

        val stage = store.stage(descriptor, TaskId("task"), "worker", 1)
        stage.write(byteArrayOf(1, 2), "worker", 1)
        stage.write(byteArrayOf(3, 4), "worker", 1)
        assertNull(store.acquire(descriptor.ref))

        val lease = store.commit(stage, "worker", 1)
        val location = lease.location as ArtifactLocation.LocalFile
        assertEquals(root / "objects", location.path.toPath().parent)
        assertContentEquals(byteArrayOf(1, 2, 3, 4), fs.source(location.path.toPath()).buffer().use { it.readByteArray() })
        assertFalse(store.remove(descriptor.ref))
        lease.close()
        lease.close()
        assertTrue(store.remove(descriptor.ref))
        assertNull(store.acquire(descriptor.ref))
        fs.checkNoOpenFiles()
    }

    @Test
    fun untrustedIdsBecomeOpaqueNamesAndConcurrentTasksAreRejected() = runTest {
        val fs = FakeFileSystem()
        val root = "/cache".toPath()
        val store = FileArtifactStore(root, fs)
        val ref = ArtifactRef(ArtifactId("../../outside/weights"))
        val descriptor = ArtifactDescriptor(ref, "../../outside.bin", sizeBytes = 2)
        val first = store.stage(descriptor, TaskId("../task"), "owner-a", 1)
        assertFailsWith<IllegalArgumentException> {
            store.stage(descriptor, TaskId("other-task"), "owner-b", 1)
        }
        first.write(byteArrayOf(8, 9), "owner-a", 1)
        val lease = store.commit(first, "owner-a", 1)
        val path = (lease.location as ArtifactLocation.LocalFile).path.toPath()
        assertEquals(root / "objects", path.parent)
        assertTrue(path.name.matches(Regex("[0-9a-f]{64}\\.blob")))
        assertFalse(fs.exists("/outside.bin".toPath()))
        lease.close()
        fs.checkNoOpenFiles()
    }

    @Test
    fun generationFencesOldWriterAndExpectedDigestIsVerifiedBeforePublication() = runTest {
        val fs = FakeFileSystem()
        val store = FileArtifactStore("/cache".toPath(), fs)
        val bytes = byteArrayOf(1, 2, 3, 4)
        val descriptor = artifact("valid.bin", bytes)
        val taskId = TaskId("fenced-task")
        val old = store.stage(descriptor, taskId, "old", 1)
        old.write(byteArrayOf(1, 2), "old", 1)
        val current = store.stage(descriptor, taskId, "new", 2)
        assertEquals(2L, current.bytesWritten)
        assertFailsWith<IllegalStateException> { old.write(byteArrayOf(7), "old", 1) }
        current.write(byteArrayOf(3, 4), "new", 2)
        val lease = store.commit(current, "new", 2)
        lease.close()

        val badStore = FileArtifactStore("/bad".toPath(), fs)
        val bad = artifact("bad.bin", bytes, digest = "0".repeat(64))
        val badStage = badStore.stage(bad, TaskId("bad-task"), "worker", 1)
        badStage.write(bytes, "worker", 1)
        assertFailsWith<IllegalArgumentException> { badStore.commit(badStage, "worker", 1) }
        assertNull(badStore.acquire(bad.ref))
        fs.checkNoOpenFiles()
    }

    @Test
    fun nullableIdentityFieldsCannotAliasLeasedArtifacts() = runTest {
        val fs = FakeFileSystem()
        val store = FileArtifactStore("/cache".toPath(), fs)
        val base = artifact("alias.bin", byteArrayOf(1, 2, 3, 4))
        val refWithNoRevision = base.ref.copy(revision = null)
        val descriptor = base.copy(ref = refWithNoRevision)
        val stage = store.stage(descriptor, TaskId("alias-task"), "worker", 1)
        stage.write(byteArrayOf(1, 2, 3, 4), "worker", 1)
        val lease = store.commit(stage, "worker", 1)

        val alias = refWithNoRevision.copy(revision = "")
        assertNull(store.acquire(alias))
        assertFalse(store.remove(alias))
        assertFalse(store.remove(refWithNoRevision))
        lease.close()
        assertTrue(store.remove(refWithNoRevision))
        fs.checkNoOpenFiles()
    }

    @Test
    fun cancelledAcquireReleasesLeaseCreatedOnIoDispatcher() = runTest {
        val fs = FakeFileSystem()
        val dispatcher = PauseOnceDispatcher()
        val store = FileArtifactStore("/cache".toPath(), fs, ioDispatcher = dispatcher)
        val descriptor = artifact("cancel-acquire.bin", byteArrayOf(1, 2, 3, 4))
        val stage = store.stage(descriptor, TaskId("cancel-acquire"), "worker", 1)
        stage.write(byteArrayOf(1, 2, 3, 4), "worker", 1)
        store.commit(stage, "worker", 1).close()

        var acquired = false
        dispatcher.pauseNext = true
        val acquisition = launch {
            store.acquire(descriptor.ref)
            acquired = true
        }
        runCurrent()
        assertTrue(dispatcher.hasPending)
        dispatcher.runNext()
        acquisition.cancel()
        runCurrent()

        assertTrue(acquisition.isCancelled)
        assertFalse(acquired)
        assertTrue(store.remove(descriptor.ref))
        fs.checkNoOpenFiles()
    }

    @Test
    fun anotherStoreInstanceRejectsOrphanStageInsteadOfClaimingDurableRecovery() = runTest {
        val fs = FakeFileSystem()
        val root = "/cache".toPath()
        val descriptor = artifact("orphan.bin", byteArrayOf(1, 2, 3, 4))
        val first = FileArtifactStore(root, fs)
        val stage = first.stage(descriptor, TaskId("orphan-task"), "worker", 1)
        stage.write(byteArrayOf(1, 2), "worker", 1)
        stage.close()

        val restarted = FileArtifactStore(root, fs)
        assertFailsWith<IllegalStateException> {
            restarted.stage(descriptor, TaskId("orphan-task"), "worker", 2)
        }
        first.abort(stage)
        fs.checkNoOpenFiles()
    }

    @Test
    fun abortClosedStageRemovesPartialFileAndReleasesWriter() = runTest {
        val fs = FakeFileSystem()
        val root = "/cache".toPath()
        val store = FileArtifactStore(root, fs)
        val descriptor = artifact("abort.bin", byteArrayOf(1, 2, 3, 4))
        val old = store.stage(descriptor, TaskId("aborted"), "first", 1)
        old.write(byteArrayOf(1, 2), "first", 1)
        old.close()
        store.abort(old)
        assertTrue(fs.list(root / "staging").isEmpty())
        val replacement = store.stage(descriptor, TaskId("replacement"), "second", 1)
        replacement.write(byteArrayOf(1, 2, 3, 4), "second", 1)
        store.commit(replacement, "second", 1).close()
        fs.checkNoOpenFiles()
    }

    @Test
    fun abortStaleClosedHandleCannotDeleteResumedStage() = runTest {
        val fs = FakeFileSystem()
        val store = FileArtifactStore("/cache".toPath(), fs)
        val descriptor = artifact("resume.bin", byteArrayOf(1, 2, 3, 4))
        val task = TaskId("resume")
        val old = store.stage(descriptor, task, "first", 1)
        old.write(byteArrayOf(1, 2), "first", 1)
        old.close()
        val current = store.stage(descriptor, task, "second", 2)
        store.abort(old)
        current.write(byteArrayOf(3, 4), "second", 2)
        store.commit(current, "second", 2).close()
        fs.checkNoOpenFiles()
    }

    private fun artifact(name: String, bytes: ByteArray, digest: String = bytes.sha256Hex()) =
        ArtifactDescriptor(
            ref = ArtifactRef(ArtifactId("test:$name"), expectedSha256 = digest),
            fileName = name,
            sizeBytes = bytes.size.toLong(),
        )

    private fun ByteArray.sha256Hex() = toByteString().sha256().hex()

    private class PauseOnceDispatcher : CoroutineDispatcher() {
        var pauseNext = false
        private val pending = mutableListOf<Runnable>()
        val hasPending get() = pending.isNotEmpty()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (pauseNext) {
                pauseNext = false
                pending += block
            } else block.run()
        }

        fun runNext() {
            pending.removeAt(0).run()
        }
    }
}
