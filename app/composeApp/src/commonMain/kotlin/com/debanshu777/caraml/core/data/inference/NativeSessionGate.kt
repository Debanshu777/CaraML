package com.debanshu777.caraml.core.data.inference

import kotlinx.coroutines.sync.Mutex
import com.debanshu777.caraml.core.performance.tracePerformanceStage

/**
 * Owns exclusive access to a native model session whose underlying pointers
 * must remain valid across suspending operations such as token collection.
 */
internal class NativeSessionGate {
    private val mutex = Mutex()

    suspend fun <T> exclusive(block: suspend () -> T): T {
        tracePerformanceStage("native.sessionLockWait") { mutex.lock() }
        return try {
            block()
        } finally {
            mutex.unlock()
        }
    }
}
