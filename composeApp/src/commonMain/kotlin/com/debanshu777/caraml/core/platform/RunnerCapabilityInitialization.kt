package com.debanshu777.caraml.core.platform

private const val MAX_NATIVE_LIBRARY_DIRECTORY_BYTES = 4_096

internal inline fun <T> discoverWithInitializedRunner(
    trustedNativeLibraryDirectory: () -> String,
    initialize: (String) -> Unit,
    discover: () -> T,
): T? {
    val directory = trustedNativeLibraryDirectory()
    if (directory.isBlank() ||
        directory.encodeToByteArray().size > MAX_NATIVE_LIBRARY_DIRECTORY_BYTES ||
        '\u0000' in directory
    ) {
        return null
    }
    initialize(directory)
    return discover()
}
