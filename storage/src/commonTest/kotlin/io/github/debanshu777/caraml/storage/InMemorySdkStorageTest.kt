package io.github.debanshu777.caraml.storage

import io.github.debanshu777.caraml.core.api.ArtifactDescriptor
import io.github.debanshu777.caraml.core.api.ArtifactId
import io.github.debanshu777.caraml.core.api.ArtifactRef
import io.github.debanshu777.caraml.core.api.DownloadTask
import io.github.debanshu777.caraml.core.api.DownloadTaskState
import io.github.debanshu777.caraml.core.api.ExperimentalCaraMLApi
import io.github.debanshu777.caraml.core.api.ModelDescriptor
import io.github.debanshu777.caraml.core.api.ModelId
import io.github.debanshu777.caraml.core.api.TaskId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCaraMLApi::class)
class InMemorySdkStorageTest {
    @Test
    fun staleWorkerCannotCheckpointAfterASecondClaim() = runTest {
        val storage = InMemorySdkStorage()
        val task = DownloadTask(TaskId("task-1"), artifact("weights.bin"))
        storage.create(task)

        val first = assertNotNull(storage.claim(task.id, "worker-a"))
        val checkpoint = first.copy(bytesReceived = 4, totalBytes = 8)
        assertTrue(storage.update(checkpoint, "worker-a", first.generation))
        assertTrue(storage.update(checkpoint.copy(state = DownloadTaskState.PAUSED), "worker-a", first.generation))

        val second = assertNotNull(storage.claim(task.id, "worker-b"))
        assertTrue(second.generation > first.generation)
        assertFalse(storage.update(checkpoint.copy(bytesReceived = 8), "worker-a", first.generation))
        assertEquals("worker-b", storage.read(task.id)?.owner)
        assertEquals(4, storage.read(task.id)?.bytesReceived)
    }

    @Test
    fun artifactAppearsAtCommitAndLeaseFencesRemoval() = runTest {
        val storage = InMemorySdkStorage()
        val descriptor = artifact("weights.bin")
        val stage = storage.stage(descriptor, TaskId("download-task"), "worker", 1)
        stage.write(byteArrayOf(1, 2, 3, 4), "worker", 1)

        assertNull(storage.acquire(descriptor.ref))
        val lease = storage.commit(stage, "worker", 1)
        assertContentEquals(byteArrayOf(1, 2, 3, 4), storage.readBytes(descriptor.ref))
        assertFalse(storage.remove(descriptor.ref))

        lease.close()
        lease.close()
        assertTrue(storage.remove(descriptor.ref))
    }

    @Test
    fun abortNeverPublishesPartialArtifact() = runTest {
        val storage = InMemorySdkStorage()
        val descriptor = artifact("partial.bin")
        val stage = storage.stage(descriptor, TaskId("partial-task"), "owner", 1)
        stage.write(byteArrayOf(7, 8), "owner", 1)
        storage.abort(stage)

        assertNull(storage.acquire(descriptor.ref))
        assertNull(storage.readBytes(descriptor.ref))
    }

    @Test
    fun newerStageGenerationFencesOldWriterAndCommit() = runTest {
        val storage = InMemorySdkStorage()
        val descriptor = artifact("resumable.bin")
        val taskId = TaskId("resume-task")
        val old = storage.stage(descriptor, taskId, "worker-a", 1)
        old.write(byteArrayOf(1, 2), "worker-a", 1)

        val current = storage.stage(descriptor, taskId, "worker-b", 2)
        assertEquals(2L, current.bytesWritten)
        assertFailsWith<IllegalStateException> { old.write(byteArrayOf(9), "worker-a", 1) }
        assertFailsWith<IllegalArgumentException> { storage.commit(old, "worker-a", 1) }
        current.write(byteArrayOf(3, 4), "worker-b", 2)

        val lease = storage.commit(current, "worker-b", 2)
        lease.close()
        assertContentEquals(byteArrayOf(1, 2, 3, 4), storage.readBytes(descriptor.ref))
    }

    @Test
    fun catalogAndTaskRecordsRemainNeutral() = runTest {
        val storage = InMemorySdkStorage()
        val descriptor = ModelDescriptor(ModelId("model-1"), "Demo model", format = "gguf")
        val installed = io.github.debanshu777.caraml.core.api.InstalledModel(descriptor, emptyList(), 42)
        storage.upsert(installed)
        assertEquals(installed, storage.list().single())
        assertTrue(storage.remove(descriptor.id))

        val task = DownloadTask(TaskId("task-2"), artifact("model.bin"), state = DownloadTaskState.QUEUED)
        storage.create(task)
        assertEquals(task, storage.read(task.id))
    }

    private fun artifact(path: String) = ArtifactDescriptor(
        ref = ArtifactRef(ArtifactId("provider:$path")),
        fileName = path,
        sizeBytes = 4,
    )
}
