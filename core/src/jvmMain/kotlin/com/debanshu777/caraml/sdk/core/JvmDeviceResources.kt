@file:OptIn(com.debanshu777.caraml.sdk.api.ExperimentalCaraMLApi::class)

package com.debanshu777.caraml.sdk.core

import com.sun.management.OperatingSystemMXBean
import com.debanshu777.caraml.sdk.api.DeviceResourceSource
import com.debanshu777.caraml.sdk.api.ResourceSnapshot
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path

/** Reads OS memory, not JVM heap headroom. Unknown readings remain unknown.
 * [outputDirectory] must be on the volume where the host will publish output.
 */
fun jvmDeviceResources(outputDirectory: Path? = null): DeviceResourceSource = DeviceResourceSource {
    val memory = runCatching {
        (ManagementFactory.getOperatingSystemMXBean() as? OperatingSystemMXBean)?.freeMemorySize
    }.getOrNull()?.takeIf { it >= 0 }
    val disk = outputDirectory?.let { directory ->
        runCatching { Files.getFileStore(directory).usableSpace }.getOrNull()?.takeIf { it >= 0 }
    }
    ResourceSnapshot(
        availableMemoryBytes = memory,
        availableStorageBytes = disk,
        capturedAtEpochMillis = System.currentTimeMillis(),
    )
}
