package com.debanshu777.caraml.core.storage.catalog

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class InstalledModelPublicationCoordinatorTest {
    @Test
    fun invalidOwnerIsRejectedBeforeCriticalSection() = runTest {
        val coordinator = InstalledModelPublicationCoordinator()
        var entered = false

        assertFailsWith<IllegalArgumentException> {
            coordinator.withOwnerPublication("../private") {
                entered = true
            }
        }

        assertFalse(entered)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun cancelledLeaderLetsSingleAndManyLiveFollowersShareOneSuccessor() = runTest {
        listOf(1, 8).forEach { followerCount ->
            val coordinator = InstalledModelPublicationCoordinator()
            val firstEntered = CompletableDeferred<Unit>()
            var calls = 0
            val block: suspend () -> String = {
                calls += 1
                if (calls == 1) {
                    firstEntered.complete(Unit)
                    awaitCancellation()
                }
                "ready"
            }
            val leader = async(start = CoroutineStart.UNDISPATCHED) {
                coordinator.coalesceRepair("owner/model", "Text", block)
            }
            firstEntered.await()
            val followers = List(followerCount) {
                async(start = CoroutineStart.UNDISPATCHED) {
                    coordinator.coalesceRepair("owner/model", "Text", block)
                }
            }

            leader.cancel()
            assertFailsWith<CancellationException> { leader.await() }
            runCurrent()

            assertEquals(List(followerCount) { "ready" }, followers.awaitAll())
            assertEquals(2, calls)
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun cancellingEveryCallerLeavesNoSuccessorOrStuckFlight() = runTest {
        val coordinator = InstalledModelPublicationCoordinator()
        val leaderEntered = CompletableDeferred<Unit>()
        var calls = 0
        val leader = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.coalesceRepair("owner/model", "Text") {
                calls += 1
                leaderEntered.complete(Unit)
                awaitCancellation()
            }
        }
        leaderEntered.await()
        val followers = List(4) {
            async(start = CoroutineStart.UNDISPATCHED) {
                coordinator.coalesceRepair("owner/model", "Text") {
                    calls += 1
                    "unexpected"
                }
            }
        }

        followers.forEach { it.cancel() }
        followers.forEach { it.cancelAndJoin() }
        leader.cancelAndJoin()
        runCurrent()

        assertEquals(1, calls)
        assertEquals(
            "recovered",
            coordinator.coalesceRepair("owner/model", "Text") { "recovered" },
        )
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun cancelledFollowerDoesNotCancelLeaderOrOtherFollowers() = runTest {
        val coordinator = InstalledModelPublicationCoordinator()
        val leaderEntered = CompletableDeferred<Unit>()
        val releaseLeader = CompletableDeferred<Unit>()
        var calls = 0
        val block: suspend () -> String = {
            calls += 1
            leaderEntered.complete(Unit)
            releaseLeader.await()
            "ready"
        }
        val leader = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.coalesceRepair("owner/model", "Text", block)
        }
        leaderEntered.await()
        val cancelledFollower = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.coalesceRepair("owner/model", "Text", block)
        }
        val liveFollower = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.coalesceRepair("owner/model", "Text", block)
        }

        cancelledFollower.cancelAndJoin()
        releaseLeader.complete(Unit)
        runCurrent()

        assertEquals("ready", leader.await())
        assertEquals("ready", liveFollower.await())
        assertTrue(cancelledFollower.isCancelled)
        assertEquals(1, calls)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun ordinaryFailureIsSharedWithoutAnotherAttempt() = runTest {
        val coordinator = InstalledModelPublicationCoordinator()
        val leaderEntered = CompletableDeferred<Unit>()
        val releaseLeader = CompletableDeferred<Unit>()
        val failure = IllegalStateException("lookup failed")
        var calls = 0
        val block: suspend () -> String = {
            calls += 1
            leaderEntered.complete(Unit)
            releaseLeader.await()
            throw failure
        }
        val leader = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { coordinator.coalesceRepair("owner/model", "Text", block) }
        }
        leaderEntered.await()
        val followers = List(4) {
            async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { coordinator.coalesceRepair("owner/model", "Text", block) }
            }
        }

        releaseLeader.complete(Unit)
        runCurrent()
        val outcomes = listOf(leader.await()) + followers.awaitAll()

        assertEquals(1, calls)
        assertTrue(outcomes.all { it.exceptionOrNull() === failure })
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun repeatedLeaderAbortStopsAfterOneFollowerReelection() = runTest {
        val coordinator = InstalledModelPublicationCoordinator()
        val firstEntered = CompletableDeferred<Unit>()
        var calls = 0
        val block: suspend () -> String = {
            calls += 1
            if (calls == 1) {
                firstEntered.complete(Unit)
                awaitCancellation()
            }
            throw CancellationException("successor aborted")
        }
        val firstLeader = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.coalesceRepair("owner/model", "Text", block)
        }
        firstEntered.await()
        val followers = List(2) {
            async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { coordinator.coalesceRepair("owner/model", "Text", block) }
            }
        }

        firstLeader.cancelAndJoin()
        runCurrent()
        val failures = followers.awaitAll().map { it.exceptionOrNull() }

        assertEquals(2, calls)
        assertEquals(1, failures.count { it is CancellationException })
        val exhausted = failures.single { it !is CancellationException }
        assertIs<IllegalStateException>(exhausted)
    }
}
