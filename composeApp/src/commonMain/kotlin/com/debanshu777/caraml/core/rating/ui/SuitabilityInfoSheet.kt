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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.platform.DeviceHints
import com.debanshu777.caraml.core.rating.SuitabilityRating
import com.debanshu777.caraml.core.rating.SuitabilityResult

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
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Header
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Device suitability",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = modelId,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SuitabilityChip(rating = result.rating)
            }

            HorizontalDivider()

            // Footprint vs budget — only meaningful when we have an estimate
            if (result.estimatedBytes != null) {
                FootprintSection(result = result)
                HorizontalDivider()
            } else {
                Text(
                    text = "We couldn't estimate memory usage for this model. " +
                        "The model card doesn't expose enough metadata " +
                        "(parameter count or file size).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun FootprintSection(result: SuitabilityResult) {
    val estimated = result.estimatedBytes ?: return
    val ratio = (estimated.toDouble() / result.budgetBytes.toDouble())
        .coerceIn(0.0, 1.5)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "Memory footprint",
            style = MaterialTheme.typography.titleSmall,
        )

        // Stacked breakdown bar (weights / KV / overhead)
        StackedFootprintBar(
            weightsBytes = result.weightsBytes,
            kvBytes = result.kvBytes,
            overheadBytes = result.overheadBytes,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FootprintLegendItem(
                label = "Weights",
                value = formatBytesHuman(result.weightsBytes),
                color = MaterialTheme.colorScheme.primary,
            )
            FootprintLegendItem(
                label = "KV cache",
                value = formatBytesHuman(result.kvBytes),
                color = MaterialTheme.colorScheme.tertiary,
            )
            FootprintLegendItem(
                label = "Overhead",
                value = formatBytesHuman(result.overheadBytes),
                color = MaterialTheme.colorScheme.secondary,
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "Estimated total vs budget",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LinearProgressIndicator(
            progress = { ratio.toFloat().coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
            color = result.rating.foregroundColor(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "${formatBytesHuman(estimated)} estimated",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "${formatBytesHuman(result.budgetBytes)} budget",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (result.quantAssumed != null && result.isEstimate) {
            Text(
                text = "Estimated assuming ${result.quantAssumed} quantization. " +
                    "Select a specific variant for an accurate rating.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            .height(10.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        if (wFrac > 0f) Box(
            modifier = Modifier
                .weight(wFrac)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.primary),
        )
        if (kFrac > 0f) Box(
            modifier = Modifier
                .weight(kFrac)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.tertiary),
        )
        if (oFrac > 0f) Box(
            modifier = Modifier
                .weight(oFrac)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.secondary),
        )
    }
}

@Composable
private fun FootprintLegendItem(label: String, value: String, color: androidx.compose.ui.graphics.Color) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color),
        )
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DeviceSnapshotSection(hints: DeviceHints?) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "This device",
            style = MaterialTheme.typography.titleSmall,
        )
        if (hints == null) {
            Text(
                text = "Device profile unavailable.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun LegendSection() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "What the colors mean",
            style = MaterialTheme.typography.titleSmall,
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
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SuitabilityChip(rating = rating)
        Spacer(modifier = Modifier.width(2.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AlgorithmSummarySection(result: SuitabilityResult) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "How we computed this",
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = "We estimate memory as weights + KV cache + ~20% overhead, " +
                "then compare against your device's RAM budget. " +
                "GPU support bumps large models up a tier; few CPU cores bump them down.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = result.reason,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "This is an estimate, not a measurement — actual usage depends on " +
                "your OS, other running apps, and runtime settings.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
