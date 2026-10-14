package com.debanshu777.caraml.features.chat.presentation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PendingLoadActionGateTest {
    @Test
    fun repeatedAndConcurrentInvocationsConsumeOnePendingAction() = runTest {
        val gate = PendingLoadActionGate()
        assertFalse(gate.tryConsume())
        gate.open()

        val results = coroutineScope {
            List(64) { async(Dispatchers.Default) { gate.tryConsume() } }.awaitAll()
        }

        assertEquals(1, results.count { it })
        assertFalse(gate.tryConsume())
        gate.open()
        assertTrue(gate.tryConsume())
    }
}
