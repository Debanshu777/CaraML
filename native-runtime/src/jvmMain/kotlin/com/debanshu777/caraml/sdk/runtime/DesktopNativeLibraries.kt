package com.debanshu777.caraml.sdk.runtime

import java.io.File

/** Resolves desktop JNI libraries without depending on a developer checkout. */
object DesktopNativeLibraries {
    /** Explicit SDK overrides take precedence over Compose's installed resources. */
    fun directory(): String? = resolveDirectory(System::getProperty)

    fun load(name: String) {
        val directory = directory()
        if (directory == null) {
            System.loadLibrary(name)
        } else {
            // An explicit directory is authoritative: never mask a broken package
            // or override by falling back to a library from the build machine.
            System.load(File(directory, System.mapLibraryName(name)).absolutePath)
        }
    }

    internal fun resolveDirectory(property: (String) -> String?): String? =
        property("caraml.native.lib.dir")?.trim()?.takeIf(String::isNotEmpty)
            ?: property("compose.application.resources.dir")?.trim()?.takeIf(String::isNotEmpty)
}
