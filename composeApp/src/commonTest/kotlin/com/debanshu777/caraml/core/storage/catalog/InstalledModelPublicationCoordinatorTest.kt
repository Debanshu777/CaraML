package com.debanshu777.caraml.core.storage.catalog

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class InstalledModelPublicationCoordinatorTest {
    @Test
    fun invalidOwnerIsRejectedBeforeCriticalSection() = runTest {
        val coordinator = InstalledModelPublicationCoordinator()
        var entered = false

        assertFailsWith<IllegalArgumentException> {
            coordinator.withOwnerPublication("../private") {
                entered = true
            }
        }

        assertFalse(entered)
    }
}
