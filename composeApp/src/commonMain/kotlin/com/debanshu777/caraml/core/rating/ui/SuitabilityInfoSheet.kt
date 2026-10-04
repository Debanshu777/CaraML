package com.debanshu777.caraml.core.rating.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.theme.AppTheme
import com.debanshu777.caraml.core.platform.DeviceHints
import com.debanshu777.caraml.core.rating.SuitabilityRating
import com.debanshu777.caraml.core.rating.SuitabilityResult
import com.debanshu777.caraml.core.theme.AuroraSurfaceLevel

/**
 * Modal bottom sheet that explains the suitability rating to the user.
 *
 * Shows:
 *   - Header with the model id + its computed rating chip
 *   - Footprint breakdown (weights / KV cache / overhead) as a stacked bar
 *   - Ratio bar vs device memory budget
 *   - Device snapshot (cores, RAM budget, GPU backend)
 *   - Algorithm summary (one paragraph)
 *   - Color legend
 *   - Caveats — estimate vs measurement, snapshot, KV proxy when arch unknown
 *
 * Caller owns the visibility state (see SearchScreen hoisting).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SuitabilityInfoSheet(
    modelId: String,
    result: SuitabilityResult,
    deviceHints: DeviceHints?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier,
        shape = AppTheme.shapes.extraLarge,
        containerColor = AuroraSurfaceLevel.Floating.containerColor(AppTheme.colors),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppTheme.dimensions.size20, vertical = AppTheme.spacing.spacing8),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.spacing16),
        ) {
            // Header
            Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.spacing8)) {
                Text(
                    text = "Device suitability",
                    style = AppTheme.typography.headingSmall,
                )
                Text(
                    text = modelId,
                    style = AppTheme.typography.bodySmall,
                    color = AppTheme.colors.onSurfaceVariant,
                )
                SuitabilityChip(rating = result.rating)
            }

            HorizontalDivider()

            // Runnability warnings
            if (result.warnings.isNotEmpty()) {
                WarningsSection(result.warnings)
                HorizontalDivider()
            }

            // Footprint vs budget — only meaningful when we have an estimate
            if (result.estimatedBytes != null) {
                FootprintSection(result = result)
                HorizontalDivider()
            } else {
                Text(
                    text = "We couldn't estimate memory usage for this model. " +
                        "The model card doesn't expose enough metadata " +
                        "(parameter count or file size).",
                    style = AppTheme.typography.bodySmall,
                    color = AppTheme.colors.onSurfaceVariant,
                )
                HorizontalDivider()
            }

            // Device snapshot
            DeviceSnapshotSection(hints = deviceHints)

            HorizontalDivider()

            // Color legend
            LegendSection()

            HorizontalDivider()

            // Algorithm summary + caveats
            AlgorithmSummarySection(result = result)

            Spacer(modifier = Modifier.height(AppTheme.spacing.spacing8))
        }
    }
}

@Composable
private fun WarningsSection(warnings: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.spacing8)) {
        Text(
            text = "Runnability",
            style = AppTheme.typography.headingXSmall,
            color = AppTheme.colors.error,
        )
        warnings.forEach { w ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.spacing8),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = AppTheme.colors.error,
                    modifier = Modifier.size(AppTheme.dimensions.size18),
                )
                Text(
                    text = w,
                    style = AppTheme.typography.bodySmall,
                    color = AppTheme.colors.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun FootprintSection(result: SuitabilityResult) {
    val estimated = result.estimatedBytes ?: return
    val ratio = (estimated.toDouble() / result.budgetBytes.toDouble())
        .coerceIn(0.0, 1.5)

    Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.spacing12)) {
        Text(
            text = "Memory footprint",
            style = AppTheme.typography.headingXSmall,
        )

        // Stacked breakdown bar (weights / KV / overhead)
        StackedFootprintBar(
            weightsBytes = result.weightsBytes,
            kvBytes = result.kvBytes,
            overheadBytes = result.overheadBytes,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.spacing12),
        ) {
            FootprintLegendItem(
                label = "Weights",
                value = formatBytesHuman(result.weightsBytes),
                color = AppTheme.colors.primary,
            )
            FootprintLegendItem(
                label = "KV cache",
                value = formatBytesHuman(result.kvBytes),
                color = AppTheme.colors.tertiary,
            )
            FootprintLegendItem(
                label = "Overhead",
                value = formatBytesHuman(result.overheadBytes),
                color = AppTheme.colors.secondary,
            )
        }

        Spacer(modifier = Modifier.height(AppTheme.spacing.spacing4))

        Text(
            text = "Estimated total vs budget",
            style = AppTheme.typography.labelBase,
            color = AppTheme.colors.onSurfaceVariant,
        )
        LinearProgressIndicator(
            progress = { ratio.toFloat().coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(AppTheme.spacing.spacing8)
                .clip(AppTheme.shapes.extraSmall),
            color = result.rating.foregroundColor(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "${formatBytesHuman(estimated)} estimated",
                style = AppTheme.typography.labelSmall,
                color = AppTheme.colors.onSurfaceVariant,
            )
            Text(
                text = "${formatBytesHuman(result.budgetBytes)} budget",
                style = AppTheme.typography.labelSmall,
                color = AppTheme.colors.onSurfaceVariant,
            )
        }
        if (result.quantAssumed != null && result.isEstimate) {
            Text(
                text = "Estimated assuming ${result.quantAssumed} quantization. " +
                    "Select a specific variant for an accurate rating.",
                style = AppTheme.typography.labelSmall,
                color = AppTheme.colors.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StackedFootprintBar(
    weightsBytes: Long,
    kvBytes: Long,
    overheadBytes: Long,
) {
    val total = (weightsBytes + kvBytes + overheadBytes).coerceAtLeast(1L)
    val wFrac = weightsBytes.toFloat() / total
    val kFrac = kvBytes.toFloat() / total
    val oFrac = overheadBytes.toFloat() / total

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(AppTheme.dimensions.size10)
            .clip(AppTheme.shapes.extraSmall)
            .background(AppTheme.colors.surfaceVariant),
    ) {
        if (wFrac > 0f) Box(
            modifier = Modifier
                .weight(wFrac)
                .fillMaxHeight()
                .background(AppTheme.colors.primary),
        )
        if (kFrac > 0f) Box(
            modifier = Modifier
                .weight(kFrac)
                .fillMaxHeight()
                .background(AppTheme.colors.tertiary),
        )
        if (oFrac > 0f) Box(
            modifier = Modifier
                .weight(oFrac)
                .fillMaxHeight()
                .background(AppTheme.colors.secondary),
        )
    }
}

@Composable
private fun FootprintLegendItem(label: String, value: String, color: androidx.compose.ui.graphics.Color) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.spacing8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(AppTheme.spacing.spacing8)
                .clip(CircleShape)
                .background(color),
        )
        Column {
            Text(
                text = label,
                style = AppTheme.typography.labelSmall,
            )
            Text(
                text = value,
                style = AppTheme.typography.labelSmall,
                color = AppTheme.colors.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DeviceSnapshotSection(hints: DeviceHints?) {
    Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.spacing8)) {
        Text(
            text = "This device",
            style = AppTheme.typography.headingXSmall,
        )
        if (hints == null) {
            Text(
                text = "Device profile unavailable.",
                style = AppTheme.typography.bodySmall,
                color = AppTheme.colors.onSurfaceVariant,
            )
            return@Column
        }
        DeviceLine(
            label = "Performance cores",
            value = "${hints.performanceCoreCount} of ${hints.totalCoreCount}",
        )
        DeviceLine(
            label = "RAM budget",
            value = formatBytesHuman(hints.memoryBudgetMB * 1024L * 1024L),
        )
        DeviceLine(
            label = "GPU backend",
            value = if (hints.gpuBackendAvailable) "Available" else "Unavailable",
        )
    }
}

@Composable
private fun DeviceLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = AppTheme.typography.bodySmall,
            color = AppTheme.colors.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = AppTheme.typography.bodySmallMedium,
        )
    }
}

@Composable
private fun LegendSection() {
    Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.spacing8)) {
        Text(
            text = "What the colors mean",
            style = AppTheme.typography.headingXSmall,
        )
        LegendRow(SuitabilityRating.BEST, "Plenty of headroom — runs comfortably")
        LegendRow(SuitabilityRating.GOOD, "Fits well — should run smoothly")
        LegendRow(SuitabilityRating.AVERAGE, "Tight fit — may swap or run slowly")
        LegendRow(SuitabilityRating.POOR, "Likely too large for this device")
    }
}

@Composable
private fun LegendRow(rating: SuitabilityRating, description: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.spacing8),
    ) {
        SuitabilityChip(rating = rating)
        Spacer(modifier = Modifier.width(AppTheme.spacing.spacing4))
        Text(
            text = description,
            style = AppTheme.typography.bodySmall,
            color = AppTheme.colors.onSurfaceVariant,
        )
    }
}

@Composable
private fun AlgorithmSummarySection(result: SuitabilityResult) {
    Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.spacing8)) {
        Text(
            text = "How we computed this",
            style = AppTheme.typography.headingXSmall,
        )
        Text(
            text = "We estimate memory as weights + KV cache + ~20% overhead, " +
                "then compare against your device's RAM budget. " +
                "GPU support bumps large models up a tier; few CPU cores bump them down.",
            style = AppTheme.typography.bodySmall,
            color = AppTheme.colors.onSurfaceVariant,
        )
        Text(
            text = result.reason,
            style = AppTheme.typography.label12,
            color = AppTheme.colors.onSurface,
        )
        Text(
            text = "This is an estimate, not a measurement — actual usage depends on " +
                "your OS, other running apps, and runtime settings.",
            style = AppTheme.typography.labelSmall,
            color = AppTheme.colors.onSurfaceVariant,
        )
    }
}
