package com.debanshu777.caraml.sdk.plugin.diffusion.bridge

data class VideoGenResult(
    val frames: List<ByteArray>,
    val effectiveFps: Int,
)
