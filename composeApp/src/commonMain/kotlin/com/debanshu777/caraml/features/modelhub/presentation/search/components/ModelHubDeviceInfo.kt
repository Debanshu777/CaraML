package com.debanshu777.caraml.features.modelhub.presentation.search.components

import com.debanshu777.caraml.core.ui.icons.AppIcons
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.debanshu777.caraml.core.drawer.LocalDrawerController
import com.debanshu777.caraml.core.drawer.LocalNavigationMenuAction
import com.debanshu777.caraml.core.platform.BackendStatus
import com.debanshu777.caraml.core.recommendation.RecommendationProfile
import com.debanshu777.caraml.core.theme.AppTheme
import com.debanshu777.caraml.core.ui.components.FrostedPageScaffold
import com.debanshu777.caraml.core.ui.components.BrandIconButton
import com.debanshu777.caraml.core.ui.components.BrandPageHeader
import com.debanshu777.caraml.core.ui.layout.ResponsiveContentPane
import com.debanshu777.caraml.core.ui.layout.AppContentKind
import com.debanshu777.caraml.features.modelhub.presentation.search.StorageInfoUiState
import com.debanshu777.caraml.features.settings.presentation.label

/** Platform readings remain optional: an unavailable measurement is never presented as zero. */
@Composable
internal fun ModelHubDeviceInfo(
    storageInfo: StorageInfoUiState,
    profile: RecommendationProfile?,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onOpenProfile: (() -> Unit)?,
    modifier: Modifier = Modifier,
    backContentDescription: String = "Back to models",
) {
    val navigationMenuAction = LocalNavigationMenuAction.current
    val drawerController = if (navigationMenuAction != null) LocalDrawerController.current else null
    if (LocalNavigationEventDispatcherOwner.current != null) {
        NavigationBackHandler(
            state = rememberNavigationEventState(NavigationEventInfo.None),
            isBackEnabled = drawerController?.isOpen != true,
            onBackCompleted = onBack,
        )
    }
    FrostedPageScaffold(
        kind = AppContentKind.ModelHub,
        modifier = modifier,
        header = {
            BrandPageHeader(
                title = "Your device",
                navigationAction = onBack,
                navigationIcon = AppIcons.Back,
                navigationContentDescription = backContentDescription,
                actions = {
                    BrandIconButton(onClick = onRefresh, modifier = Modifier.size(AppTheme.spacing.spacing48)) {
                        Icon(AppIcons.Refresh, contentDescription = "Refresh device info")
                    }
                },
            )
        },
    ) { insets ->
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(insets).padding(vertical = AppTheme.spacing.spacing16)
                .testTag("model-device-info"),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.spacing16),
        ) {
            val hasReadings = storageInfo.totalDeviceBytes > 0L || storageInfo.deviceHints != null ||
                storageInfo.hardwareProfile != null || storageInfo.resourceSnapshot != null
            if (!hasReadings) {
                ModelHubStateView(
                    kind = if (storageInfo.hasSampled) ModelHubStateKind.Error else ModelHubStateKind.Loading,
                    title = "Device info unavailable",
                    message = if (storageInfo.hasSampled) "We couldn't read your device details. You can still explore models; fit estimates may need more information." else "Reading your device…",
                    actionLabel = if (storageInfo.hasSampled) "Check again" else null,
                    onAction = if (storageInfo.hasSampled) onRefresh else null,
                )
            } else {
                DeviceStorageCard(storageInfo)
                val hints = storageInfo.deviceHints
                val resources = storageInfo.resourceSnapshot
                val budget = hints?.memoryBudgetMB?.takeIf { it > 0 }?.let { formatStorageBytes(it * 1024L * 1024L) } ?: "Unavailable"
                val available = resources?.additionalAllocatableHostBytes?.takeIf { it >= 0 }?.let(::formatStorageBytes) ?: "Unavailable"
                DevicePanel {
                    DeviceSectionLabel(AppIcons.Memory, "Memory")
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        if (LocalDensity.current.fontScale >= 1.5f || maxWidth < 280.dp) {
                            Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.spacing16)) {
                                DeviceMetric("Memory budget", budget)
                                DeviceMetric("Available to app", available)
                            }
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.spacing16)) {
                                DeviceMetric("Memory budget", budget, Modifier.weight(1f))
                                DeviceMetric("Available to app", available, Modifier.weight(1f))
                            }
                        }
                    }
                }
                val hardware = storageInfo.hardwareProfile
                val cpuCores = (hardware?.logicalCoreCount ?: hints?.totalCoreCount)?.takeIf { it > 0 }
                val backends = hardware?.backends?.filter {
                    it.status == BackendStatus.AVAILABLE && (cpuCores == null || it.kind.name != "CPU")
                }?.joinToString(" · ") { it.kind.name }.orEmpty()
                DevicePanel {
                    DeviceSectionLabel(AppIcons.Compute, "Compute")
                    Text(listOfNotNull(
                        cpuCores?.let { "$it CPU cores" },
                        backends.takeIf(String::isNotEmpty),
                    ).joinToString(" · ").ifEmpty { "Unavailable" }, style = AppTheme.typography.preferenceTitle)
                }
                Text("Available memory changes as other apps run. Model fit is an estimate.",
                    style = AppTheme.typography.bodySmall, color = AppTheme.colors.onSurfaceVariant)
            }
            if (profile != null && onOpenProfile != null) {
                Surface(onClick = onOpenProfile, shape = AppTheme.shapes.large,
                    color = AppTheme.colors.surfaceContainerLowest,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Change profile" },
                ) {
                    Row(Modifier.padding(AppTheme.spacing.spacing16), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.spacing12)) {
                        Icon(AppIcons.Profile, null, tint = AppTheme.colors.primary)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.spacing4)) {
                            Text("Your model profile", style = AppTheme.typography.preferenceTitle)
                            Text("${profile.riskTolerance.label()} · ${profile.optimizationPriority.label()}",
                                style = AppTheme.typography.bodySmall, color = AppTheme.colors.onSurfaceVariant)
                        }
                        Icon(AppIcons.ChevronRight, null)
                    }
                }
            }
        }
    }
}

@Composable
internal fun ModelHubBackHeader(onBack: () -> Unit, contentDescription: String = "Navigate back", label: String = "Models") {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.spacing12),
    ) {
        BrandIconButton(onClick = onBack, modifier = Modifier.size(AppTheme.spacing.spacing48).semantics { this.contentDescription = contentDescription }) {
            Icon(AppIcons.Back, contentDescription = null)
        }
        Text(label, style = AppTheme.typography.bodyBase, color = AppTheme.colors.onSurface)
    }
}

@Composable
private fun DeviceStorageCard(info: StorageInfoUiState) {
    DevicePanel(emphasized = true) {
        DeviceSectionLabel(AppIcons.Storage, "Storage")
        if (info.totalDeviceBytes > 0L) {
            val available = info.availableDeviceBytes.coerceIn(0L, info.totalDeviceBytes)
            Text("${formatStorageBytes(available)} free", style = AppTheme.typography.stateTitle)
            LinearProgressIndicator(
                progress = { 1f - available.toFloat() / info.totalDeviceBytes },
                modifier = Modifier.fillMaxWidth(),
                drawStopIndicator = {},
            )
            Text("${formatStorageBytes(info.totalDeviceBytes - available)} used of ${formatStorageBytes(info.totalDeviceBytes)}",
                style = AppTheme.typography.bodySmall, color = AppTheme.colors.onSurfaceVariant)
        } else {
            Text("Storage reading unavailable", style = AppTheme.typography.bodyBase)
        }
        Text("Your models use ${formatStorageBytes(info.usedByModelsBytes)}",
            style = AppTheme.typography.bodySmall, color = AppTheme.colors.onSurfaceVariant)
    }
}

@Composable
private fun DevicePanel(emphasized: Boolean = false, content: @Composable () -> Unit) {
    Surface(shape = AppTheme.shapes.large,
        color = if (emphasized) AppTheme.colors.primaryContainer else AppTheme.colors.surfaceContainerLowest,
        contentColor = AppTheme.colors.onSurface,
    ) {
        Column(Modifier.fillMaxWidth().padding(AppTheme.spacing.spacing16),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.spacing12)) { content() }
    }
}

@Composable
private fun DeviceSectionLabel(icon: ImageVector, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.spacing8)) {
        Icon(icon, null, Modifier.size(AppTheme.spacing.spacing16), tint = AppTheme.colors.primary)
        Text(label, style = AppTheme.typography.preferenceTitle, modifier = Modifier.semantics { heading() })
    }
}

@Composable
private fun DeviceMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.spacing4)) {
        Text(value, style = AppTheme.typography.sectionTitle)
        Text(label, style = AppTheme.typography.bodySmall, color = AppTheme.colors.onSurfaceVariant)
    }
}
