package com.debanshu777.caraml.core.download

import android.app.job.JobParameters
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AndroidDownloadOwnershipTest {
    private val batchId = "a".repeat(64)

    @Test
    fun recreatedSchedulerAdoptsExistingUidtWithoutSchedulingAgain() = runTest {
        val uidt = FakeUidtBackend(active = mutableSetOf(batchId))
        val work = FakeWorkBackend()

        AndroidDownloadOwnershipController(34, uidt, work).enqueue(batchId)

        assertEquals(0, uidt.scheduleCalls)
        assertEquals(0, work.enqueueCalls)
    }

    @Test
    fun recreatedSchedulerAdoptsExistingWorkWithoutCreatingUidtOwner() = runTest {
        val uidt = FakeUidtBackend()
        val work = FakeWorkBackend(active = mutableSetOf(batchId))

        AndroidDownloadOwnershipController(34, uidt, work).enqueue(batchId)

        assertEquals(0, uidt.scheduleCalls)
        assertEquals(0, work.enqueueCalls)
    }

    @Test
    fun definitiveUidtFailureFallsBackOnlyAfterFreshOwnershipCheck() = runTest {
        val uidt = FakeUidtBackend(scheduleResult = false, becomeActiveAfterSchedule = true)
        val work = FakeWorkBackend()

        AndroidDownloadOwnershipController(34, uidt, work).enqueue(batchId)

        assertEquals(1, uidt.scheduleCalls)
        assertEquals(0, work.enqueueCalls)
        assertEquals(2, uidt.ownerQueries)
    }

    @Test
    fun definitiveUidtFailureWithNoOwnerUsesOneUniqueWorker() = runTest {
        val uidt = FakeUidtBackend(scheduleResult = false)
        val work = FakeWorkBackend()

        AndroidDownloadOwnershipController(34, uidt, work).enqueue(batchId)

        assertEquals(1, uidt.scheduleCalls)
        assertEquals(1, work.enqueueCalls)
        assertEquals(2, uidt.ownerQueries)
    }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun concurrentEnqueueSerializesTheOwnershipDecision() = runTest {
        val uidt = FakeUidtBackend()
        val workQueryEntered = CompletableDeferred<Unit>()
        val releaseWorkQuery = CompletableDeferred<Unit>()
        val work = object : AndroidWorkBackend {
            override suspend fun isActive(batchId: String): Boolean {
                workQueryEntered.complete(Unit)
                releaseWorkQuery.await()
                return false
            }
            override fun enqueue(batchId: String) = Unit
            override suspend fun cancel(batchId: String) = Unit
            override fun reconcile(liveBatchIds: Set<String>) = Unit
        }
        val controller = AndroidDownloadOwnershipController(34, uidt, work)

        val first = async { controller.enqueue(batchId) }
        workQueryEntered.await()
        val second = async { controller.enqueue(batchId) }
        runCurrent()
        assertEquals(1, uidt.ownerQueries)

        releaseWorkQuery.complete(Unit)
        first.await()
        second.await()
        assertEquals(1, uidt.scheduleCalls)
    }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun startupPrefersExactUidtAndAwaitsCancellationOfDuplicateWorker() = runTest {
        val uidt = FakeUidtBackend(active = mutableSetOf(batchId))
        val cancelEntered = CompletableDeferred<Unit>()
        val releaseCancel = CompletableDeferred<Unit>()
        val work = FakeWorkBackend(
            active = mutableSetOf(batchId),
            cancelEntered = cancelEntered,
            releaseCancel = releaseCancel,
        )

        val reconciliation = async {
            AndroidDownloadOwnershipController(34, uidt, work).reconcile(setOf(batchId))
        }
        cancelEntered.await()
        runCurrent()
        assertFalse(reconciliation.isCompleted)
        releaseCancel.complete(Unit)
        reconciliation.await()

        assertEquals(1, work.cancelCalls)
        assertFalse(work.isActive(batchId))
        assertTrue(uidt.hasOwner(batchId))
    }

    @Test
    fun startupConsumesCrashDurableUserStopBeforeChoosingAnOwner() = runTest {
        val generation = "uidt-generation"
        val uidt = FakeUidtBackend(active = mutableSetOf(batchId))
        val work = FakeWorkBackend()
        val platformTasks = FakePlatformTaskStore(currentGeneration = generation)
        val markers = FakeStopMarkerStore().also { assertTrue(it.record(batchId, generation)) }

        AndroidDownloadOwnershipController(34, uidt, work, platformTasks, markers)
            .reconcile(setOf(batchId))

        assertEquals(listOf(batchId to generation), platformTasks.pauseCalls)
        assertFalse(uidt.hasOwner(batchId))
        assertNull(markers.generation(batchId))
    }

    @Test
    fun enqueueConsumesStopMarkerBeforeCreatingAReplacementOwner() = runTest {
        val generation = "stopped-generation"
        val uidt = FakeUidtBackend()
        val work = FakeWorkBackend()
        val platformTasks = FakePlatformTaskStore(currentGeneration = generation)
        val markers = FakeStopMarkerStore().also { assertTrue(it.record(batchId, generation)) }
        val controller = AndroidDownloadOwnershipController(34, uidt, work, platformTasks, markers)

        controller.enqueue(batchId)

        assertEquals(listOf(batchId to generation), platformTasks.pauseCalls)
        assertEquals(0, uidt.scheduleCalls)
        assertEquals(0, work.enqueueCalls)
        assertNull(markers.generation(batchId))
    }

    @Test
    fun generationlessPersistedUidtIsNotAValidOwner() {
        assertFalse(isValidUidtOwnerMetadata(batchId, batchId, null))
        assertFalse(isValidUidtOwnerMetadata(batchId, batchId, "contains spaces"))
        assertFalse(isValidUidtOwnerMetadata(batchId, "b".repeat(64), "valid-generation"))
        assertTrue(isValidUidtOwnerMetadata(batchId, batchId, "valid-generation"))
    }

    @Test
    fun staleUserStopCannotPauseOrCancelReplacementGeneration() = runTest {
        val oldGeneration = "old-generation"
        val uidt = FakeUidtBackend(active = mutableSetOf(batchId))
        val work = FakeWorkBackend()
        val platformTasks = FakePlatformTaskStore(currentGeneration = "replacement-generation")
        val markers = FakeStopMarkerStore().also { assertTrue(it.record(batchId, oldGeneration)) }

        AndroidDownloadOwnershipController(34, uidt, work, platformTasks, markers)
            .reconcile(setOf(batchId))

        assertEquals(listOf(batchId to oldGeneration), platformTasks.pauseCalls)
        assertTrue(uidt.hasOwner(batchId))
        assertEquals(0, uidt.cancelCalls)
        assertNull(markers.generation(batchId))
    }

    @Test
    fun stoppedGenerationCannotRemoveOrFinishReplacement() {
        val registry = AndroidUidtOwnerRegistry()
        val firstParams = Any()
        val secondParams = Any()
        val first = registry.register(7, batchId, firstParams)
        val second = registry.register(7, batchId, secondParams)

        assertNull(registry.stop(7, firstParams))
        assertFalse(registry.complete(first))
        assertSame(second, registry.current(7))
        assertTrue(registry.complete(second))
        assertNull(registry.current(7))
    }

    @Test
    fun stoppedCurrentGenerationNeverCompletesAfterStop() {
        val registry = AndroidUidtOwnerRegistry()
        val params = Any()
        val token = registry.register(7, batchId, params)

        assertSame(token, registry.stop(7, params))
        assertFalse(registry.complete(token))
    }

    @Test
    fun userStopPausesWithoutRescheduleWhileSystemStopRetries() {
        assertEquals(
            AndroidUidtStopDecision.PAUSE,
            androidUidtStopDecision(JobParameters.STOP_REASON_USER),
        )
        assertEquals(
            AndroidUidtStopDecision.RETRY,
            androidUidtStopDecision(JobParameters.STOP_REASON_CONSTRAINT_CONNECTIVITY),
        )
        assertEquals(
            AndroidUidtStopDecision.RETRY,
            androidUidtStopDecision(JobParameters.STOP_REASON_PREEMPT),
        )
    }
}

private class FakeUidtBackend(
    private val active: MutableSet<String> = mutableSetOf(),
    private val scheduleResult: Boolean = true,
    private val becomeActiveAfterSchedule: Boolean = false,
) : AndroidUidtBackend {
    var scheduleCalls = 0
    var ownerQueries = 0

    override fun hasOwner(batchId: String): Boolean {
        ownerQueries += 1
        return batchId in active
    }

    var cancelCalls = 0

    override suspend fun schedule(batchId: String): Boolean {
        scheduleCalls += 1
        if (scheduleResult || becomeActiveAfterSchedule) active += batchId
        return scheduleResult
    }

    override fun cancel(batchId: String) {
        cancelCalls += 1
        active -= batchId
    }

    override fun reconcile(liveBatchIds: Set<String>) {
        active.retainAll(liveBatchIds)
    }
}

private class FakeWorkBackend(
    private val active: MutableSet<String> = mutableSetOf(),
    private val cancelEntered: CompletableDeferred<Unit>? = null,
    private val releaseCancel: CompletableDeferred<Unit>? = null,
) : AndroidWorkBackend {
    var enqueueCalls = 0
    var cancelCalls = 0

    override suspend fun isActive(batchId: String): Boolean = batchId in active
    override fun enqueue(batchId: String) {
        enqueueCalls += 1
        active += batchId
    }
    override suspend fun cancel(batchId: String) {
        cancelCalls += 1
        cancelEntered?.complete(Unit)
        releaseCancel?.await()
        active -= batchId
    }
    override fun reconcile(liveBatchIds: Set<String>) {
        active.retainAll(liveBatchIds)
    }
}

private class FakePlatformTaskStore(
    private var currentGeneration: String?,
) : AndroidPlatformTaskStore {
    val pauseCalls = mutableListOf<Pair<String, String>>()

    override suspend fun bind(batchId: String, generation: String): Boolean {
        currentGeneration = generation
        return true
    }

    override suspend fun pause(batchId: String, generation: String): Boolean {
        pauseCalls += batchId to generation
        if (currentGeneration != generation) return false
        currentGeneration = null
        return true
    }
}

private class FakeStopMarkerStore : AndroidUidtStopMarkerStore {
    private val markers = mutableMapOf<String, String>()

    override fun record(batchId: String, generation: String): Boolean {
        markers[batchId] = generation
        return true
    }

    override fun generation(batchId: String): String? = markers[batchId]

    override fun clear(batchId: String, generation: String): Boolean {
        if (markers[batchId] != generation) return false
        markers.remove(batchId)
        return true
    }
}
