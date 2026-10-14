package com.debanshu777.caraml.core.recommendation

/** Engine policy is distinct from the shared GGML registry's device inventory. */
internal expect fun platformRequiresCpuDiffusion(): Boolean
