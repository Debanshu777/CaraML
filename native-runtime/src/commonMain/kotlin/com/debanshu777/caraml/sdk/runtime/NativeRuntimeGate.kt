package com.debanshu777.caraml.sdk.runtime

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Internal SDK support shared by both native adapters. A loaded session holds the
 * lease until native teardown completes. Preflight also needs a lease. This
 * deliberately serializes both engines because GGML initialization and logging
 * are shared. It does not coordinate legacy direct runner calls or classloaders.
 *
 * This Kotlin gate does not isolate native symbols, C++ runtimes or backend discovery.
 * Callers must retain the lease when native teardown fails and ownership remains unresolved;
 * releasing it would allow another engine to enter the still-owned runtime.
 */
object NativeRuntimeGate {
    private val gate = Mutex()

    fun tryAcquire(): Lease? {
        val owner = Any()
        return if (gate.tryLock(owner)) Lease(owner) else null
    }

    class Lease internal constructor(private val owner: Any) {
        private val closeGate = Mutex()
        private var released = false

        suspend fun close() = closeGate.withLock {
            if (!released) {
                released = true
                gate.unlock(owner)
            }
        }
    }
}
