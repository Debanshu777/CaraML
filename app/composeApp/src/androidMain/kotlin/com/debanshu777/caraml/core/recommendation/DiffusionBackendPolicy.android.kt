package com.debanshu777.caraml.core.recommendation

// Image graphs use the shared Vulkan backend. Keep unbenchmarked video on CPU.
internal actual fun platformRequiresCpuDiffusion(mode: DiffusionMode): Boolean = mode == DiffusionMode.VIDEO
