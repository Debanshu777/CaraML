package com.debanshu777.caraml.core.platform

import kotlin.test.Test
import kotlin.test.assertNull

class JvmProcessMemoryObservationTest {
    @Test
    fun heapUsageIsNotPublishedAsWholeProcessMemory() {
        assertNull(DeviceCapabilities().getResourceSnapshot().currentProcessBytes)
    }
}
