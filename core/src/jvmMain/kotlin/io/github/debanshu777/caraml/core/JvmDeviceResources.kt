@file:OptIn(io.github.debanshu777.caraml.core.api.ExperimentalCaraMLApi::class)

package io.github.debanshu777.caraml.core

import com.sun.management.OperatingSystemMXBean
import io.github.debanshu777.caraml.core.api.DeviceResourceSource
import io.github.debanshu777.caraml.core.api.ResourceSnapshot
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
