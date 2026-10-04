package com.debanshu777.caraml.features.modelhub.presentation.search.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.theme.AppTheme
import com.debanshu777.caraml.core.platform.DeviceHints
import com.debanshu777.caraml.core.recommendation.RecommendationProfile
import com.debanshu777.caraml.core.ui.components.CaraMLPane
import com.debanshu777.caraml.core.ui.motion.LocalAuroraMotionPolicy
import com.debanshu777.caraml.features.modelhub.presentation.search.StorageInfoUiState
import com.debanshu777.caraml.features.settings.presentation.label
import kotlin.math.round

@Composable
fun ModelHubContextStrip(
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
    if (!showStorage && !showDevice && profile == null) return

    val visibleItemCount = listOf(showStorage, showDevice, profile != null).count { it }
    val spacing = AppTheme.spacing
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = AppTheme.spacing.spacing48)
            .testTag("model-context"),
    ) {
        val compact = maxWidth < AppTheme.dimensions.size600
        if (compact && visibleItemCount >= 2) {
            CompactDeviceProfile(
                storageInfo = storageInfo,
                profile = profile,
                onOpenProfile = onOpenProfile,
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.spacing8),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showStorage) {
                    ContextItem(
                        icon = Icons.Outlined.Storage,
                        label = "Storage",
                        value = "${formatStorageBytes(storageInfo.availableDeviceBytes)} storage free · " +
                            "Models: ${formatStorageBytes(storageInfo.usedByModelsBytes)}",
                        modifier = Modifier.weight(1f),
                    )
                }
                storageInfo.deviceHints?.let { hints ->
                    ContextItem(
                        icon = Icons.Outlined.Memory,
                        label = "Device",
                        value = hints.summary(),
                        modifier = Modifier.weight(1f),
                    )
                }
                if (profile != null && onOpenProfile != null) {
                    RecommendationProfileContext(
                        profile = profile,
                        onClick = onOpenProfile,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactDeviceProfile(
    storageInfo: StorageInfoUiState,
    profile: RecommendationProfile?,
    onOpenProfile: (() -> Unit)?,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val spacing = AppTheme.spacing
    val motion = LocalAuroraMotionPolicy.current
    val risk = profile?.riskTolerance?.label()
    val summary = listOfNotNull(
        storageInfo.availableDeviceBytes.takeIf { it > 0L }
            ?.let { "${formatStorageBytes(it)} storage free" },
        risk,
    ).joinToString(" · ")
    val duration = motion.opacityDurationMillis
    val enter = fadeIn(tween(duration)) + if (motion.spatialTransitionsEnabled) {
        expandVertically(tween(duration))
    } else {
        expandVertically(tween(0))
    }
    val exit = fadeOut(tween(duration)) + if (motion.spatialTransitionsEnabled) {
        shrinkVertically(tween(duration))
    } else {
        shrinkVertically(tween(0))
    }

    CaraMLPane(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = AppTheme.spacing.spacing48)
                .clickable(role = Role.Button) { expanded = !expanded }
                .padding(horizontal = spacing.spacing12)
                .semantics {
                    role = Role.Button
                    stateDescription = if (expanded) "Expanded" else "Collapsed"
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.spacing8),
        ) {
            Icon(
                imageVector = Icons.Outlined.Tune,
                contentDescription = null,
                modifier = Modifier.size(AppTheme.dimensions.size18),
                tint = AppTheme.colors.onSurfaceVariant,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Device profile",
                    style = AppTheme.typography.labelLarge,
                    color = AppTheme.colors.onSurface,
                )
                if (summary.isNotBlank()) {
                    Text(
                        text = summary,
                        style = AppTheme.typography.body14,
                        color = AppTheme.colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Collapse device profile" else "Expand device profile",
                tint = AppTheme.colors.onSurfaceVariant,
            )
        }
        AnimatedVisibility(visible = expanded, enter = enter, exit = exit) {
            Column(
                modifier = Modifier.padding(
                    start = spacing.spacing12,
                    end = spacing.spacing12,
                    bottom = spacing.spacing12,
                ),
                verticalArrangement = Arrangement.spacedBy(spacing.spacing8),
            ) {
                if (storageInfo.totalDeviceBytes > 0L) {
                    ContextItem(
                        icon = Icons.Outlined.Storage,
                        label = "Storage",
                        value = "${formatStorageBytes(storageInfo.availableDeviceBytes)} storage free · " +
                            "Models: ${formatStorageBytes(storageInfo.usedByModelsBytes)}",
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                storageInfo.deviceHints?.let { hints ->
                    ContextItem(
                        icon = Icons.Outlined.Memory,
                        label = "Device",
                        value = hints.summary(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (profile != null && onOpenProfile != null) {
                    RecommendationProfileContext(
                        profile = profile,
                        onClick = onOpenProfile,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun ContextItem(
    icon: ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.heightIn(min = AppTheme.spacing.spacing48),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(AppTheme.dimensions.size18),
            tint = AppTheme.colors.onSurfaceVariant,
        )
        Spacer(Modifier.width(AppTheme.spacing.spacing8))
        Column {
            Text(
                text = label,
                style = AppTheme.typography.technical12,
                color = AppTheme.colors.onSurfaceVariant,
                maxLines = 1,
            )
            Text(
                text = value,
                style = AppTheme.typography.body14,
                color = AppTheme.colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun RecommendationProfileContext(
    profile: RecommendationProfile,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val risk = profile.riskTolerance.label()
    val priority = profile.optimizationPriority.label()
    Surface(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = AppTheme.spacing.spacing48)
            .semantics {
                contentDescription = "Recommendation profile. Selected risk: $risk. " +
                    "Selected priority: $priority. Open profile controls."
            },
        shape = AppTheme.shapes.extraSmall,
        color = AppTheme.colors.surfaceContainer.copy(alpha = AppTheme.effects.contextStripSurface),
        contentColor = AppTheme.colors.onSurfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = AppTheme.spacing.spacing12),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Tune,
                contentDescription = null,
                modifier = Modifier.size(AppTheme.dimensions.size18),
            )
            Spacer(Modifier.width(AppTheme.spacing.spacing8))
            Column {
                Text(
                    "Profile",
                    style = AppTheme.typography.technical12,
                    maxLines = 1,
                )
                Text(
                    "$risk · $priority",
                    style = AppTheme.typography.body14,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(AppTheme.dimensions.size6))
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(AppTheme.dimensions.size18),
            )
        }
    }
}

private fun DeviceHints.summary(): String {
    val ramBudgetBytes = memoryBudgetMB * 1024L * 1024L
    val gpu = if (gpuBackendAvailable) "GPU" else "CPU"
    return "$performanceCoreCount/$totalCoreCount cores · ${formatStorageBytes(ramBudgetBytes)} · $gpu"
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
