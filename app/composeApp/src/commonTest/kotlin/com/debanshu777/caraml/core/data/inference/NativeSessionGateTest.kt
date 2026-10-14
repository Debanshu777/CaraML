package com.debanshu777.caraml.core.data.inference

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeSessionGateTest {
    @Test
    fun secondOperationWaitsUntilFirstCompletes() = runBlocking {
        val gate = NativeSessionGate()
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        var secondEntered = false

        val first = launch {
            gate.exclusive {
                firstEntered.complete(Unit)
                releaseFirst.await()
            }
        }
        firstEntered.await()

        val second = launch {
            gate.exclusive {
                secondEntered = true
            }
        }
        yield()

        assertFalse(secondEntered)
        releaseFirst.complete(Unit)
        joinAll(first, second)
        assertTrue(secondEntered)
    }
}
