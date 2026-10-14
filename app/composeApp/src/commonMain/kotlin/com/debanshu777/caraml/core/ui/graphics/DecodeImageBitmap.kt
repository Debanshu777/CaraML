package com.debanshu777.caraml.core.ui.graphics

import androidx.compose.ui.graphics.ImageBitmap

expect fun decodePngToImageBitmap(bytes: ByteArray): ImageBitmap?
