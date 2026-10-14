package com.debanshu777.caraml.core.recommendation

// The shared Vulkan module accelerates llama. Keep the existing mobile
// diffusion CPU policy while its unsupported driver/pipeline paths remain.
internal actual fun platformRequiresCpuDiffusion(): Boolean = true
