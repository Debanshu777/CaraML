package com.debanshu777.caraml.features.modelhub.presentation.details.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.debanshu777.caraml.core.rating.ModelSuitabilityCalculator
import com.debanshu777.caraml.core.rating.ui.formatBytesHuman
import com.debanshu777.caraml.core.theme.AppTechnicalLabel
import com.debanshu777.caraml.core.ui.components.SignalTone
import com.debanshu777.caraml.core.ui.components.StatusMark
import com.debanshu777.caraml.core.ui.components.TechnicalListRow
import com.debanshu777.caraml.features.modelhub.presentation.search.GgufFileUiState

/** Short quantization label for technical rows. Delegates parsing to the shared
 *  calculator so the regex lives in one place. Falls back to the raw suffix
 *  when no canonical tag is detected (e.g. unusual filenames). */
private fun quantizationLabel(filename: String): String {
    return ModelSuitabilityCalculator.parseQuantTag(filename)
        ?: filename.substringBeforeLast('.').substringAfterLast('-').ifEmpty { filename }
}

/**
 * Technical decision list for selecting a model quantization variant.
 *
 * The recommendation marker is projected from the assessed exact descriptor;
 * this UI never recomputes policy from partial file metadata.
 */
@Composable
fun VariantPickerRow(
    variants: List<GgufFileUiState>,
    selectedVariantPath: String?,
    onVariantSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    recommendedVariantPath: String? = null,
) {
    if (variants.isEmpty()) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .selectableGroup(),
    ) {
        variants.forEach { variant ->
            val isSelected = variant.path == selectedVariantPath
            val isRecommended = variant.path == recommendedVariantPath
            val label = quantizationLabel(variant.filename)
            val statusContent: (@Composable () -> Unit)? = when {
                variant.isDownloaded -> ({
                    StatusMark(
                        label = "Downloaded",
                        contentDescription = "Downloaded artifact",
                        tone = SignalTone.Positive,
                        icon = Icons.Default.CheckCircle,
                    )
                })
                isRecommended -> ({
                    StatusMark(
                        label = "Recommended",
                        contentDescription = "Recommended artifact",
                        tone = SignalTone.Accent,
                        icon = Icons.Default.CheckCircle,
                    )
                })
                isSelected -> ({
                    StatusMark(
                        label = "Selected",
                        contentDescription = "Selected artifact",
                        tone = SignalTone.Neutral,
                        icon = Icons.Default.CheckCircle,
                    )
                })
                else -> null
            }
            TechnicalListRow(
                title = label,
                eyebrow = variant.filename,
                contentDescription = "Artifact ${variant.filename}",
                selected = isSelected,
                emphasized = isSelected || isRecommended,
                selectionEnabled = true,
                signalTone = if (isSelected || isRecommended) SignalTone.Accent else null,
                onClick = if (variant.isDownloaded) null else {
                    { onVariantSelected(variant.path) }
                },
                status = statusContent,
                trailing = variant.sizeBytes?.let { bytes ->
                    {
                        Text(
                            text = formatBytesHuman(bytes),
                            style = AppTechnicalLabel,
                        )
                    }
                },
            )
        }
    }
}
