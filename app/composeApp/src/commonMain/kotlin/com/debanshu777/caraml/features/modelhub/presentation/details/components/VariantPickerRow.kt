package com.debanshu777.caraml.features.modelhub.presentation.details.components

import com.debanshu777.caraml.core.ui.icons.AppIcons
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.debanshu777.caraml.core.theme.AppTheme
import com.debanshu777.caraml.core.rating.ModelSuitabilityCalculator
import com.debanshu777.caraml.core.rating.ui.formatBytesHuman
import com.debanshu777.caraml.core.theme.CaraMLTheme
import com.debanshu777.caraml.core.theme.ThemePreferences
import com.debanshu777.caraml.core.ui.components.StatusMark
import com.debanshu777.caraml.core.ui.components.GenericListItem
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
                        icon = AppIcons.CheckCircle,
                    )
                })
                isRecommended -> ({
                    StatusMark(
                        label = "Recommended",
                        contentDescription = "Recommended artifact",
                        icon = AppIcons.CheckCircle,
                    )
                })
                isSelected -> ({
                    StatusMark(
                        label = "Selected",
                        contentDescription = "Selected artifact",
                        icon = AppIcons.CheckCircle,
                    )
                })
                else -> null
            }
            GenericListItem(
                title = label,
                eyebrow = variant.filename,
                contentDescription = "Artifact ${variant.filename}",
                selected = isSelected,
                emphasized = isSelected || isRecommended,
                selectionEnabled = true,
                onClick = if (variant.isDownloaded) null else {
                    { onVariantSelected(variant.path) }
                },
                status = statusContent,
                trailing = variant.sizeBytes?.let { bytes ->
                    {
                        Text(
                            text = formatBytesHuman(bytes),
                            style = AppTheme.typography.technical12,
                        )
                    }
                },
            )
        }
    }
}

@Preview(name = "Artifact variants - compact", widthDp = 360, heightDp = 280)
@Composable
private fun VariantPickerRowCompactPreview() {
    VariantPickerRowPreviewContent()
}

@Preview(name = "Artifact variants - large text", widthDp = 360, heightDp = 440, fontScale = 2f)
@Composable
private fun VariantPickerRowLargeTextPreview() {
    VariantPickerRowPreviewContent()
}

@Composable
private fun VariantPickerRowPreviewContent() {
    val variants = listOf(
        GgufFileUiState(
            path = "MiniCPM5-2B-Q4_K_M.gguf",
            filename = "MiniCPM5-2B-Q4_K_M.gguf",
            sizeBytes = 1_610_612_736L,
            isDownloaded = false,
            progress = null,
        ),
        GgufFileUiState(
            path = "MiniCPM5-2B-Q5_K_M.gguf",
            filename = "MiniCPM5-2B-Q5_K_M.gguf",
            sizeBytes = 2_147_483_648L,
            isDownloaded = false,
            progress = null,
        ),
        GgufFileUiState(
            path = "MiniCPM5-2B-Q8_0.gguf",
            filename = "MiniCPM5-2B-Q8_0.gguf",
            sizeBytes = 3_221_225_472L,
            isDownloaded = true,
            progress = null,
        ),
    )
    CaraMLTheme(ThemePreferences()) {
        Surface {
            VariantPickerRow(
                variants = variants,
                selectedVariantPath = variants[1].path,
                recommendedVariantPath = variants[0].path,
                onVariantSelected = {},
            )
        }
    }
}
