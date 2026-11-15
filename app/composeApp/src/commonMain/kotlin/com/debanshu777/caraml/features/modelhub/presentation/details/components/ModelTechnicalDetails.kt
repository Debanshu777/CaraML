package com.debanshu777.caraml.features.modelhub.presentation.details.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.recommendation.BrowseFitEstimate
import com.debanshu777.caraml.core.recommendation.BrowseResourceFit
import com.debanshu777.caraml.core.recommendation.Compatibility
import com.debanshu777.caraml.core.theme.AppTheme
import com.debanshu777.caraml.core.ui.icons.AppIcons
import com.debanshu777.caraml.features.modelhub.domain.DescriptorState
import com.debanshu777.caraml.features.modelhub.domain.RecommendedModelUiState
import com.debanshu777.caraml.features.modelhub.presentation.search.components.formatCompactMetric
import com.debanshu777.caraml.sdk.discovery.huggingface.model.ModelDetailResponse

private data class MetadataEntry(val label: String, val value: String)

private fun String?.nonBlankMetadata(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
private fun List<String?>?.metadataList(): String? = this?.mapNotNull { it.nonBlankMetadata() }
    ?.takeIf { it.isNotEmpty() }?.joinToString(", ")

@Composable
internal fun ModelMetadataSection(model: ModelDetailResponse) {
    val parameters = (model.gguf?.total?.takeIf { it > 0 } ?: model.safetensors?.total?.takeIf { it > 0 })
        ?.let(::formatCompactMetric)
    val context = model.gguf?.contextLength?.takeIf { it > 0 }
    val contextLabel = context?.toString()?.reversed()?.chunked(3)?.joinToString(",")?.reversed()
    val modelEntries = listOfNotNull(
        model.gguf?.architecture.nonBlankMetadata()?.let { MetadataEntry("Architecture", it) },
        model.config?.modelType.nonBlankMetadata()?.let { MetadataEntry("Model type", it) },
        model.config?.architectures.metadataList()?.let { MetadataEntry("Architectures", it) },
        model.libraryName.nonBlankMetadata()?.let { MetadataEntry("Library", it) },
    )
    val usageEntries = listOfNotNull(
        model.pipelineTag.nonBlankMetadata()?.let { MetadataEntry("Pipeline", it) },
        model.cardData?.language.metadataList()?.let { MetadataEntry("Languages", it) },
        model.cardData?.baseModel.metadataList()?.let { MetadataEntry("Base model", it) },
    )
    val repositoryEntries = listOfNotNull(
        model.cardData?.license.nonBlankMetadata()?.let { MetadataEntry("License", it) },
        formatHubTimestamp(model.createdAt).nonBlankMetadata()?.let { MetadataEntry("Created", it) },
        formatHubTimestamp(model.lastModified).nonBlankMetadata()?.let { MetadataEntry("Last modified", it) },
    )
    val tags = visibleModelTags(model.tags.orEmpty(), model.pipelineTag, model.libraryName, model.config?.modelType)
    Column(
        Modifier.fillMaxWidth().testTag("detail-metadata"),
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.spacing24),
    ) {
        if (parameters != null || context != null || modelEntries.isNotEmpty()) {
            TechnicalSection("Model specifications", AppIcons.Compute) {
                if (parameters != null || context != null) {
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val stacked = maxWidth / LocalDensity.current.fontScale < 280.dp
                        val stats: @Composable (Modifier) -> Unit = { modifier ->
                            if (parameters != null) TechnicalMetric("Parameters", parameters, modifier)
                            if (context != null) TechnicalMetric("Advertised context", "$contextLabel tokens", modifier, "Device context may be lower")
                        }
                        if (stacked) Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.spacing16)) {
                            stats(Modifier.fillMaxWidth())
                        } else Row(horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.spacing16)) {
                            stats(Modifier.weight(1f))
                        }
                    }
                    if (modelEntries.isNotEmpty()) HorizontalDivider(color = AppTheme.colors.outlineVariant)
                }
                MetadataRows(modelEntries)
            }
        }
        if (usageEntries.isNotEmpty()) TechnicalSection("Usage", AppIcons.Text) { MetadataRows(usageEntries) }
        if (repositoryEntries.isNotEmpty()) TechnicalSection("Repository", AppIcons.Clock) { MetadataRows(repositoryEntries) }
        if (tags.isNotEmpty()) {
            TechnicalSection("Tags", AppIcons.Models) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.spacing8),
                    verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.spacing8),
                ) {
                    tags.forEach { tag ->
                        Surface(shape = AppTheme.shapes.small, color = AppTheme.colors.surfaceContainerLow) {
                            Text(tag, style = AppTheme.typography.labelBase, color = AppTheme.colors.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = AppTheme.spacing.spacing8, vertical = AppTheme.spacing.spacing4))
                        }
                    }
                }
            }
        }
        if (parameters == null && context == null && modelEntries.isEmpty() && usageEntries.isEmpty() && repositoryEntries.isEmpty() && tags.isEmpty()) {
            Text("No technical details available", style = AppTheme.typography.bodySmall, color = AppTheme.colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun TechnicalSection(title: String, icon: ImageVector, content: @Composable ColumnScope.() -> Unit) {
    val spacing = AppTheme.spacing
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(spacing.spacing12)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.spacing8)) {
            Icon(icon, contentDescription = null, tint = AppTheme.colors.onSurfaceVariant, modifier = Modifier.size(18.dp))
            Text(title, style = AppTheme.typography.heading16, color = AppTheme.colors.onSurface,
                modifier = Modifier.semantics { heading() })
        }
        Surface(
            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
            color = AppTheme.colors.surface.copy(alpha = AppTheme.effects.workspaceGlyphSurface),
            border = BorderStroke(1.dp, AppTheme.colors.outlineVariant),
        ) {
            Column(Modifier.padding(spacing.spacing16), verticalArrangement = Arrangement.spacedBy(spacing.spacing16), content = content)
        }
    }
}

@Composable
private fun TechnicalMetric(label: String, value: String, modifier: Modifier, supportingText: String? = null) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.spacing4)) {
        Text(label, style = AppTheme.typography.labelBase, color = AppTheme.colors.onSurfaceVariant)
        Text(value, style = AppTheme.typography.heading24, color = AppTheme.colors.onSurface)
        if (supportingText != null) Text(supportingText, style = AppTheme.typography.labelBase, color = AppTheme.colors.onSurfaceVariant)
    }
}

@Composable
private fun MetadataRows(entries: List<MetadataEntry>) {
    entries.forEach { (label, value) ->
        val stack = LocalDensity.current.fontScale >= 1.5f || value.length > 28 || '/' in value || ',' in value
        val labelText: @Composable (Modifier) -> Unit = { modifier ->
            Text(label, style = AppTheme.typography.labelBase, color = AppTheme.colors.onSurfaceVariant, modifier = modifier)
        }
        val valueText: @Composable (Modifier) -> Unit = { modifier ->
            Text(value, style = AppTheme.typography.bodySmall, fontWeight = FontWeight.Medium,
                color = AppTheme.colors.onSurface, modifier = modifier)
        }
        if (stack) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.spacing4)) {
                labelText(Modifier)
                valueText(Modifier)
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.spacing12), verticalAlignment = Alignment.Top) {
                labelText(Modifier.weight(.4f))
                valueText(Modifier.weight(.6f))
            }
        }
    }
}

@Composable
internal fun ModelFitExplanation(recommendationState: RecommendedModelUiState?, browseFit: BrowseFitEstimate?) {
    val unverified = recommendationState == null || recommendationState.descriptorState == DescriptorState.NEEDS_INFORMATION
    if (browseFit == null && !unverified) return
    TechnicalSection("Device fit", AppIcons.Device) {
        if (browseFit != null && !browseFit.resourceSnapshotFresh) {
            Text("Device readings outdated · refresh to check fit", style = AppTheme.typography.labelLarge, color = AppTheme.colors.onSurface)
        }
        if (unverified) {
            val explanation = when {
                browseFit == null || browseFit.memoryFit == BrowseResourceFit.UNKNOWN -> "Fit unknown · support unverified"
                browseFit.compatibility is Compatibility.Unknown -> "Memory estimate · compatibility unverified"
                else -> "Memory estimate · fit unconfirmed"
            }
            Text(explanation, style = AppTheme.typography.labelLarge, color = AppTheme.colors.onSurface)
        }
        if (browseFit != null) Text("Estimated by CaraML from model metadata, file sizes, and device resource readings.",
            style = AppTheme.typography.bodySmall, color = AppTheme.colors.onSurfaceVariant)
    }
}
