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
import com.debanshu777.caraml.features.modelhub.presentation.search.GgufFileUiState

/** Extracts a short quantization label from a filename. e.g. "flux1-q4_k_m.gguf" → "Q4_K_M" */
private fun quantizationLabel(filename: String): String {
    val name = filename.substringBeforeLast('.')
    // Try to find a known quant tag
    val quantPattern = Regex("""(Q\d[\w.]*|FP16|BF16|F16|F32|INT8|INT4)""", RegexOption.IGNORE_CASE)
    val match = quantPattern.find(name)
    return match?.value?.uppercase() ?: name.substringAfterLast('-').ifEmpty { filename }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return ""
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex++
    }
    val display = if (value >= 100 || unitIndex == 0) {
        value.toInt().toString()
    } else {
        val rounded = kotlin.math.round(value * 10.0) / 10.0
        if (rounded % 1.0 == 0.0) rounded.toInt().toString() else rounded.toString()
    }
    return "$display ${units[unitIndex]}"
}

/**
 * Horizontal scrollable chip row for selecting a model quantization variant.
 */
@Composable
fun VariantPickerRow(
    variants: List<GgufFileUiState>,
    selectedVariantPath: String?,
    onVariantSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (variants.isEmpty()) return

    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(variants) { variant ->
            val isSelected = variant.path == selectedVariantPath
            val label = quantizationLabel(variant.filename)

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
                                    text = formatBytes(bytes),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
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
