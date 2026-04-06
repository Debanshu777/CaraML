package com.debanshu777.caraml.core.ui.graphics

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image

actual fun decodePngToImageBitmap(bytes: ByteArray): ImageBitmap? {
    if (bytes.isEmpty()) return null
    return runCatching {
        Image.makeFromEncoded(bytes).toComposeImageBitmap()
    }.getOrNull()
}
