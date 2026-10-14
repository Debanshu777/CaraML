package com.debanshu777.caraml.core.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RunnerCapabilityInitializationTest {
    @Test
    fun capabilityDiscoveryInitializesFromTrustedPlatformPathFirst() {
        val events = mutableListOf<String>()

        val result = discoverWithInitializedRunner(
            trustedNativeLibraryDirectory = { "/trusted/app/lib" },
            initialize = { events += "initialize:$it" },
            discover = {
                events += "discover"
                "capabilities"
            },
        )

        assertEquals("capabilities", result)
        assertEquals(listOf("initialize:/trusted/app/lib", "discover"), events)
    }

    @Test
    fun invalidPlatformPathFailsClosedBeforeNativeDiscovery() {
        var enteredNative = false

        val result = discoverWithInitializedRunner(
            trustedNativeLibraryDirectory = { "bad\u0000path" },
            initialize = { enteredNative = true },
            discover = {
                enteredNative = true
                "capabilities"
            },
        )

        assertNull(result)
        assertEquals(false, enteredNative)
    }
}
