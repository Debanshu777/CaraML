package com.debanshu777.huggingfacemanager.download

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ArtifactRootLockCoordinatorTest {
    @Test
    fun concurrentBundlesWithReversedRootOrderSerializeWithoutDeadlock() = runTest {
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        var secondEntered = false

        val first = async {
            ArtifactRootLockCoordinator.withRoots(listOf("/models/b", "/models/a")) {
                firstEntered.complete(Unit)
                releaseFirst.await()
            }
        }
        firstEntered.await()
        val second = async {
            ArtifactRootLockCoordinator.withRoots(listOf("/models/a", "/models/b")) {
                secondEntered = true
            }
        }

        runCurrent()
        assertFalse(secondEntered)
        releaseFirst.complete(Unit)
        first.await()
        second.await()
        assertTrue(secondEntered)
    }

    @Test
    fun bundlePublicationSerializesWithComponentReplacement() = runTest {
        val bundleEntered = CompletableDeferred<Unit>()
        val releaseBundle = CompletableDeferred<Unit>()
        var replacementEntered = false

        val bundle = async {
            ArtifactRootLockCoordinator.withRoots(listOf("/models/owner", "/models/component")) {
                bundleEntered.complete(Unit)
                releaseBundle.await()
            }
        }
        bundleEntered.await()
        val replacement = async {
            ArtifactRootLockCoordinator.withRoots(listOf("/models/component")) {
                replacementEntered = true
            }
        }

        runCurrent()
        assertFalse(replacementEntered)
        releaseBundle.complete(Unit)
        bundle.await()
        replacement.await()
        assertTrue(replacementEntered)
    }
}
