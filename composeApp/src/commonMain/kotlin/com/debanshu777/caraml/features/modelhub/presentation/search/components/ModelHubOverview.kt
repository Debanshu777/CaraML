package com.debanshu777.caraml.features.modelhub.presentation.search.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.platform.DeviceHints
import com.debanshu777.caraml.core.recommendation.RecommendationProfile
import com.debanshu777.caraml.core.theme.AuroraSurfaceLevel
import com.debanshu777.caraml.core.theme.LocalSpacing
import com.debanshu777.caraml.core.ui.components.CaraMLPane
import com.debanshu777.caraml.core.ui.components.AuroraFocalSurface
import com.debanshu777.caraml.core.ui.motion.LocalAuroraMotionPolicy
import com.debanshu777.caraml.features.modelhub.presentation.search.StorageInfoUiState
import com.debanshu777.caraml.features.settings.presentation.label
import kotlin.math.round

@Composable
fun ModelHubOverview(
    storageInfo: StorageInfoUiState,
    profile: RecommendationProfile?,
    onOpenProfile: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    require((profile == null) == (onOpenProfile == null)) {
        "profile and onOpenProfile must either both be provided or both be null"
    }

    val showStorage = storageInfo.totalDeviceBytes > 0L
    val showDevice = storageInfo.deviceHints != null
    val showProfile = profile != null
    if (!showStorage && !showDevice && !showProfile) return

    val spacing = LocalSpacing.current
    AuroraFocalSurface(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = spacing.s),
        shape = MaterialTheme.shapes.medium,
    ) {
        CaraMLPane(
            modifier = Modifier.fillMaxWidth().padding(1.dp),
            level = AuroraSurfaceLevel.Pane,
            shape = MaterialTheme.shapes.medium,
        ) {
            if (showStorage) {
                StorageInfoBar(storageInfo)
            }
            if (showStorage && showDevice) {
                HorizontalDivider(modifier = Modifier.padding(horizontal = spacing.m))
            }
            storageInfo.deviceHints?.let { deviceHints ->
                DeviceInfoSection(deviceHints)
            }
            if ((showStorage || showDevice) && showProfile) {
                HorizontalDivider(modifier = Modifier.padding(horizontal = spacing.m))
            }
            if (profile != null && onOpenProfile != null) {
                RecommendationProfileAction(profile, onOpenProfile)
            }
        }
    }
}

@Composable
private fun StorageInfoBar(storageInfo: StorageInfoUiState) {
    val usedFraction = (
        storageInfo.usedByModelsBytes.toFloat() / storageInfo.totalDeviceBytes
        ).coerceIn(0f, 1f)
    val animatedProgress by animateFloatAsState(
        targetValue = usedFraction,
        animationSpec = tween(durationMillis = 180),
    )
    val spacing = LocalSpacing.current

    Column(modifier = Modifier.padding(spacing.m)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Device Storage",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "${formatStorageBytes(storageInfo.availableDeviceBytes)} free of " +
                    formatStorageBytes(storageInfo.totalDeviceBytes),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { animatedProgress },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(MaterialTheme.shapes.extraSmall),
            color = if (usedFraction > 0.85f) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.primary
            },
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Models: ${formatStorageBytes(storageInfo.usedByModelsBytes)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun DeviceInfoSection(deviceHints: DeviceHints) {
    var expanded by remember { mutableStateOf(false) }
    val spacing = LocalSpacing.current
    val motion = LocalAuroraMotionPolicy.current
    val ramBudgetBytes = deviceHints.memoryBudgetMB * 1024 * 1024
    val gpuText = if (deviceHints.gpuBackendAvailable) "Available" else "Unavailable"
    val summary = "${deviceHints.performanceCoreCount}P/${deviceHints.totalCoreCount} cores · " +
        "${formatStorageBytes(ramBudgetBytes)} RAM · GPU $gpuText"
    val expandEnter = fadeIn(tween(motion.opacityDurationMillis)) +
        if (motion.spatialTransitionsEnabled) {
            expandVertically(tween(motion.peerTransitionMillis))
        } else {
            EnterTransition.None
        }
    val expandExit = fadeOut(tween(motion.opacityDurationMillis)) +
        if (motion.spatialTransitionsEnabled) {
            shrinkVertically(tween(motion.peerTransitionMillis))
        } else {
            ExitTransition.None
        }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(spacing.m),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Device",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (!expanded) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandEnter,
            exit = expandExit,
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Spacer(modifier = Modifier.height(8.dp))
                DeviceInfoRow(
                    label = "Performance cores",
                    value = "${deviceHints.performanceCoreCount} of ${deviceHints.totalCoreCount}",
                )
                if (deviceHints.perfCoreMask.isNotBlank()) {
                    DeviceInfoRow(label = "Perf core mask", value = deviceHints.perfCoreMask)
                }
                DeviceInfoRow(label = "Total cores", value = "${deviceHints.totalCoreCount}")
                DeviceInfoRow(label = "RAM budget", value = formatStorageBytes(ramBudgetBytes))
                DeviceInfoRow(
                    label = "GPU backend",
                    value = gpuText,
                    valueColor = if (deviceHints.gpuBackendAvailable) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Personalized recommendations",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Compatibility, current memory and storage, workload, and expected " +
                        "speed are checked together. Open a model's recommendation for the " +
                        "evidence and fallback plan.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DeviceInfoRow(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.labelSmall, color = valueColor)
    }
}

@Composable
private fun RecommendationProfileAction(
    profile: RecommendationProfile,
    onClick: () -> Unit,
) {
    val risk = profile.riskTolerance.label()
    val priority = profile.optimizationPriority.label()
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .semantics {
                contentDescription = "Recommendation profile. Selected risk: $risk. " +
                    "Selected priority: $priority. Open profile controls."
            },
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.72f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Tune,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Recommendation profile",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "$risk · $priority",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatStorageBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
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
        val rounded = round(value * 10.0) / 10.0
        if (rounded % 1.0 == 0.0) rounded.toInt().toString() else rounded.toString()
    }
    return "$display ${units[unitIndex]}"
}
