package com.debanshu777.caraml.features.modelhub.presentation.details.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.platform.DeviceHints
import com.debanshu777.caraml.core.rating.ModelSuitabilityCalculator
import com.debanshu777.caraml.core.rating.ui.SuitabilityDot
import com.debanshu777.caraml.core.rating.ui.formatBytesHuman
import com.debanshu777.caraml.features.modelhub.presentation.search.GgufFileUiState

/** Short quantization label for chip text. Delegates parsing to the shared
 *  calculator so the regex lives in one place. Falls back to the raw suffix
 *  when no canonical tag is detected (e.g. unusual filenames). */
private fun quantizationLabel(filename: String): String {
    return ModelSuitabilityCalculator.parseQuantTag(filename)
        ?: filename.substringBeforeLast('.').substringAfterLast('-').ifEmpty { filename }
}

/**
 * Horizontal scrollable chip row for selecting a model quantization variant.
 *
 * When [deviceHints], [numParameters], and [architecture] are provided we render
 * a per-variant suitability dot next to the size — the calculation is accurate
 * here because each variant has a known on-disk size.
 */
@Composable
fun VariantPickerRow(
    variants: List<GgufFileUiState>,
    selectedVariantPath: String?,
    onVariantSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    deviceHints: DeviceHints? = null,
    numParameters: Long? = null,
    contextLength: Int? = null,
    architecture: String? = null,
) {
    if (variants.isEmpty()) return

    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(variants) { variant ->
            val isSelected = variant.path == selectedVariantPath
            val label = quantizationLabel(variant.filename)
            val rating = deviceHints?.let { hints ->
                ModelSuitabilityCalculator.rateLlm(
                    hints = hints,
                    numParameters = numParameters,
                    sizeBytes = variant.sizeBytes,
                    quantTag = ModelSuitabilityCalculator.parseQuantTag(variant.filename),
                    contextLength = contextLength,
                    architecture = architecture,
                ).rating
            }

            FilterChip(
                selected = isSelected || variant.isDownloaded,
                onClick = { if (!variant.isDownloaded) onVariantSelected(variant.path) },
                label = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = label,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                        if (variant.isDownloaded) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                            )
                        } else {
                            variant.sizeBytes?.let { bytes ->
                                Text(
                                    text = formatBytesHuman(bytes),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        rating?.let { SuitabilityDot(rating = it) }
                    }
                },
                border = if (isSelected && !variant.isDownloaded) {
                    BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
                } else null,
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = if (variant.isDownloaded) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    },
                ),
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }
    }
}
