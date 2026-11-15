package com.debanshu777.caraml.features.modelhub.presentation.details.components

import com.debanshu777.caraml.core.rating.ModelSuitabilityCalculator

/** Display shorthand only; selection/downloads still carry the exact artifact identity. */
internal fun compactVariantLabel(filename: String): String? {
    val quantization = ModelSuitabilityCalculator.parseQuantTag(filename) ?: return null
    return if (filename.contains("UD-$quantization", ignoreCase = true)) "UD-$quantization" else quantization
}
