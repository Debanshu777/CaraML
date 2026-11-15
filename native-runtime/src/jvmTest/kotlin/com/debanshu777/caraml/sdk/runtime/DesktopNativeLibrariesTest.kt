package com.debanshu777.caraml.sdk.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopNativeLibrariesTest {
    @Test
    fun explicitDirectoryIsAuthoritativeEvenWhenItDoesNotExist() {
        val properties = mapOf(
            "caraml.native.lib.dir" to "/missing-sdk-runtime",
            "compose.application.resources.dir" to "/installed/resources",
        )
        assertEquals("/missing-sdk-runtime", DesktopNativeLibraries.resolveDirectory(properties::get))
    }

    @Test
    fun installedResourcesWorkWithoutDevelopmentLibraryPath() {
        val properties = mapOf("compose.application.resources.dir" to "/installed/resources")
        assertEquals("/installed/resources", DesktopNativeLibraries.resolveDirectory(properties::get))
        assertNull(DesktopNativeLibraries.resolveDirectory { "  " })
    }
}
