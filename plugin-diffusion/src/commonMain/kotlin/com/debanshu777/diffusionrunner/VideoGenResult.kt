package com.debanshu777.diffusionrunner

data class VideoGenResult(
    val frames: List<ByteArray>,
    val effectiveFps: Int,
)
