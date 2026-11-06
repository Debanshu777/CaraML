@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.debanshu777.caraml.core.recommendation

import kotlinx.cinterop.*
import platform.darwin.sysctlbyname

internal actual fun onnxDeviceModel(): String? = memScoped {
    val capacity = 128
    val size = alloc<ULongVar>().apply { value = capacity.toULong() }
    val bytes = allocArray<ByteVar>(capacity)
    if (sysctlbyname("hw.machine", bytes, size.ptr, null, 0u) != 0 || size.value !in 1uL..capacity.toULong()) return@memScoped null
    bytes[size.value.toInt() - 1] = 0
    bytes.toKString()
}
