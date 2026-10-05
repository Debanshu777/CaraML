package com.debanshu777.caraml.features.modelhub.presentation.search.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.debanshu777.caraml.core.platform.BackendStatus
import com.debanshu777.caraml.core.recommendation.RecommendationProfile
import com.debanshu777.caraml.core.theme.AppTheme
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
    backLabel: String = "Models",
    backContentDescription: String = "Back to models",
) {
    if (LocalNavigationEventDispatcherOwner.current != null) {
        NavigationBackHandler(
            state = rememberNavigationEventState(NavigationEventInfo.None),
            isBackEnabled = true,
            onBackCompleted = onBack,
        )
    }
    Scaffold(modifier = modifier, containerColor = Color.Transparent) { insets ->
        ResponsiveContentPane(kind = AppContentKind.ModelHub, modifier = Modifier.fillMaxSize().padding(insets)) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("model-device-info")) {
                ModelHubBackHeader(onBack = onBack, contentDescription = backContentDescription, label = backLabel)
                Column(Modifier.fillMaxWidth().padding(vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Text("Know your limits.\nThen make things.", style = AppTheme.typography.pageTitle32, color = AppTheme.colors.onSurface,
                        modifier = Modifier.semantics { heading() })
                    Text("A little look at the space and power your models can use.",
                        style = AppTheme.typography.bodySmall, color = AppTheme.colors.onSurface)
                    val hasReadings = storageInfo.totalDeviceBytes > 0L || storageInfo.deviceHints != null ||
                        storageInfo.hardwareProfile != null || storageInfo.resourceSnapshot != null
                    if (!hasReadings) {
                        ModelHubStateView(
                            kind = if (storageInfo.hasSampled) ModelHubStateKind.Error else ModelHubStateKind.Loading,
                            title = "A little mystery device",
                            message = if (storageInfo.hasSampled) "We couldn't read your device details. You can still explore models; fit estimates may need more information." else "Reading your device…",
                            actionLabel = if (storageInfo.hasSampled) "Check again" else null,
                            onAction = if (storageInfo.hasSampled) onRefresh else null,
                        )
                    } else {
                        DeviceStorageCard(storageInfo)
                        val hints = storageInfo.deviceHints
                        val resources = storageInfo.resourceSnapshot
                        DeviceInfoRow("Memory budget", hints?.memoryBudgetMB?.takeIf { it > 0 }?.let { formatStorageBytes(it * 1024L * 1024L) } ?: "Unavailable")
                        DeviceInfoRow("Available to app", resources?.additionalAllocatableHostBytes?.takeIf { it >= 0 }?.let(::formatStorageBytes) ?: "Unavailable")
                        val hardware = storageInfo.hardwareProfile
                        val backends = hardware?.backends?.filter { it.status == BackendStatus.AVAILABLE }
                            ?.joinToString(" · ") { it.kind.name }.orEmpty()
                        DeviceInfoRow("Compute", listOfNotNull(
                            (hardware?.logicalCoreCount ?: hints?.totalCoreCount)?.takeIf { it > 0 }?.let { "$it CPU cores" },
                            backends.takeIf(String::isNotEmpty),
                        ).joinToString(" · ").ifEmpty { "Unavailable" })
                        Text("These readings can change as other apps use memory. Fit estimates aren't a guarantee.",
                            style = AppTheme.typography.bodySmall, color = AppTheme.colors.onSurface)
                        ModelHubAction("Check again", onRefresh)
                    }
                    if (profile != null && onOpenProfile != null) {
                        Surface(shape = AppTheme.shapes.large, border = BorderStroke(1.dp, AppTheme.colors.outlineVariant), color = AppTheme.colors.surfaceContainerLowest) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Your model profile", style = AppTheme.typography.labelLarge)
                                Text("${profile.riskTolerance.label()} · ${profile.optimizationPriority.label()}", style = AppTheme.typography.bodySmall, color = AppTheme.colors.onSurfaceVariant)
                                ModelHubAction("Change profile", onOpenProfile)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ModelHubBackHeader(onBack: () -> Unit, contentDescription: String = "Navigate back", label: String = "Models") {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, modifier = Modifier.size(AppTheme.spacing.spacing48).semantics { this.contentDescription = contentDescription }) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = AppTheme.colors.onSurface)
        }
        Text(label, style = AppTheme.typography.bodyBase, color = AppTheme.colors.onSurface)
    }
}

@Composable
private fun DeviceStorageCard(info: StorageInfoUiState) {
    Surface(shape = AppTheme.shapes.large, border = BorderStroke(1.dp, AppTheme.colors.outlineVariant), color = AppTheme.colors.surfaceContainerLowest) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Room to play", style = AppTheme.typography.labelLarge)
            if (info.totalDeviceBytes > 0L) {
                val available = info.availableDeviceBytes.coerceIn(0L, info.totalDeviceBytes)
                Text("${formatStorageBytes(available)} free", style = AppTheme.typography.stateTitle26)
                Text("${formatStorageBytes(info.totalDeviceBytes - available)} used of ${formatStorageBytes(info.totalDeviceBytes)}", style = AppTheme.typography.bodySmall, color = AppTheme.colors.onSurfaceVariant)
                LinearProgressIndicator(progress = { 1f - available.toFloat() / info.totalDeviceBytes }, modifier = Modifier.fillMaxWidth())
            } else {
                Text("Storage reading unavailable", style = AppTheme.typography.bodyBase)
            }
            Text("Your models use ${formatStorageBytes(info.usedByModelsBytes)}", style = AppTheme.typography.bodySmall, color = AppTheme.colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun DeviceInfoRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(label, style = AppTheme.typography.bodySmall, color = AppTheme.colors.onSurface, modifier = Modifier.weight(1f))
            Text(value, style = AppTheme.typography.bodySmall, color = AppTheme.colors.onSurface, modifier = Modifier.weight(1f))
        }
        HorizontalDivider(color = AppTheme.colors.outlineVariant)
    }
}
