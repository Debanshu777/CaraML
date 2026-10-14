package com.debanshu777.caraml.sdk.runtime

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class NativeRuntimeGateTest {
    @Test
    fun competingAdaptersCannotMutateSharedRuntimeUntilTeardownCompletes() = runTest {
        val first = assertNotNull(NativeRuntimeGate.tryAcquire())
        assertNull(NativeRuntimeGate.tryAcquire())
        val close1 = launch { first.close() }
        val close2 = launch { first.close() }
        close1.join()
        close2.join()
        val second = assertNotNull(NativeRuntimeGate.tryAcquire())
        first.close() // A late repeated close must not release another adapter's lease.
        assertNull(NativeRuntimeGate.tryAcquire())
        second.close()
    }
}
