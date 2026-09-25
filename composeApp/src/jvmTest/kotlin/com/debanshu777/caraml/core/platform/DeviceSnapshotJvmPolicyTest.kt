package com.debanshu777.caraml.core.platform

import kotlin.test.Test
import kotlin.test.assertEquals

class DeviceSnapshotJvmPolicyTest {
    @Test
    fun macDesktopIsUnifiedOnlyForValidatedAppleSiliconArchitectures() {
        assertEquals(MemoryTopology.UNIFIED, desktopMemoryTopology("Mac OS X", "arm64"))
        assertEquals(MemoryTopology.UNIFIED, desktopMemoryTopology("Darwin", "aarch64"))
        assertEquals(MemoryTopology.UNKNOWN, desktopMemoryTopology("Mac OS X", "x86_64"))
        assertEquals(MemoryTopology.UNKNOWN, desktopMemoryTopology("Mac OS X", "amd64"))
        assertEquals(MemoryTopology.UNKNOWN, desktopMemoryTopology("Linux", "arm64"))
        assertEquals(MemoryTopology.UNKNOWN, desktopMemoryTopology("Mac OS X", ""))
    }
}
