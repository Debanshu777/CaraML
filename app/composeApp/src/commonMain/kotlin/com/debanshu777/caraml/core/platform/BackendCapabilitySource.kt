package com.debanshu777.caraml.core.platform

fun interface BackendCapabilitySource {
    fun capabilities(): List<BackendCapability>
}
