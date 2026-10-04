package com.debanshu777.caraml.features.modelhub.presentation.details.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.theme.AppTheme
import com.debanshu777.caraml.core.theme.CaraMLTheme
import com.debanshu777.caraml.core.theme.ThemePreferences
import com.debanshu777.caraml.core.ui.components.CaraMLSectionHeader
import com.debanshu777.caraml.core.ui.components.StatusMark
import com.debanshu777.caraml.core.ui.motion.LocalAuroraMotionPolicy
import com.debanshu777.caraml.features.modelhub.presentation.search.InstallBundleUiState
import com.debanshu777.caraml.features.modelhub.presentation.search.GgufFileUiState
import com.debanshu777.caraml.features.modelhub.presentation.search.SetupComponentUiState
import com.debanshu777.caraml.features.modelhub.presentation.search.DurableDownloadControlUiState
import com.debanshu777.caraml.core.download.DownloadBatchState
import com.debanshu777.huggingfacemanager.sdcpp.ComponentRole

private fun formatBytes(bytes: Long): String {
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
        val rounded = kotlin.math.round(value * 10.0) / 10.0
        if (rounded % 1.0 == 0.0) rounded.toInt().toString() else rounded.toString()
    }
    return "$display ${units[unitIndex]}"
}

/**
 * Unified install workspace for diffusion models.
 * Shows variant rows, required components, and a single "Smart Install" action.
 */
@Composable
fun InstallBundleCard(
    modelId: String,
    state: InstallBundleUiState,
    familyLabel: String?,
    modelDescription: String?,
    onVariantSelected: (path: String) -> Unit,
    onInstall: () -> Unit,
    modifier: Modifier = Modifier,
    recommendedVariantPath: String? = null,
    installEnabled: Boolean = true,
    durableControl: DurableDownloadControlUiState? = null,
    onPause: () -> Unit = {},
    onResume: () -> Unit = {},
    onCancel: () -> Unit = {},
    onRetry: () -> Unit = {},
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.spacing12),
    ) {
        InstallBundleSummaryContent(
            state = state,
            familyLabel = familyLabel,
            modelDescription = modelDescription,
            onVariantSelected = onVariantSelected,
            recommendedVariantPath = recommendedVariantPath,
        )
        InstallBundleActionContent(
            state = state,
            onInstall = onInstall,
            installEnabled = installEnabled,
            modifier = Modifier.testTag("detail-action"),
            durableControl = durableControl,
            onPause = onPause,
            onResume = onResume,
            onCancel = onCancel,
            onRetry = onRetry,
        )
    }
}

@Composable
internal fun InstallBundleSummaryCard(
    state: InstallBundleUiState,
    familyLabel: String?,
    modelDescription: String?,
    onVariantSelected: (path: String) -> Unit,
    modifier: Modifier = Modifier,
    recommendedVariantPath: String? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.spacing12),
    ) {
        InstallBundleSummaryContent(
            state = state,
            familyLabel = familyLabel,
            modelDescription = modelDescription,
            onVariantSelected = onVariantSelected,
            recommendedVariantPath = recommendedVariantPath,
        )
    }
}

@Composable
internal fun InstallBundleActionFooter(
    state: InstallBundleUiState,
    onInstall: () -> Unit,
    modifier: Modifier = Modifier,
    installEnabled: Boolean = true,
    durableControl: DurableDownloadControlUiState? = null,
    onPause: () -> Unit = {},
    onResume: () -> Unit = {},
    onCancel: () -> Unit = {},
    onRetry: () -> Unit = {},
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = AppTheme.colors.surfaceContainerHigh,
        tonalElevation = AppTheme.spacing.spacing2,
    ) {
        InstallBundleActionContent(
            state = state,
            onInstall = onInstall,
            installEnabled = installEnabled,
            modifier = Modifier.padding(AppTheme.spacing.spacing12),
            durableControl = durableControl,
            onPause = onPause,
            onResume = onResume,
            onCancel = onCancel,
            onRetry = onRetry,
        )
    }
}

@Composable
private fun InstallBundleSummaryContent(
    state: InstallBundleUiState,
    familyLabel: String?,
    modelDescription: String?,
    onVariantSelected: (path: String) -> Unit,
    recommendedVariantPath: String?,
) {
    CaraMLSectionHeader(
        title = "Install summary",
        supportingText = familyLabel?.takeIf { it.isNotBlank() },
    )
    if (!modelDescription.isNullOrBlank()) {
        Text(
            text = modelDescription,
            style = AppTheme.typography.bodySmall,
            color = AppTheme.colors.onSurfaceVariant,
        )
    }

    if (state.variants.isNotEmpty() && !state.variants.all { it.isDownloaded }) {
        CaraMLSectionHeader(
            title = "Variants",
            supportingText = "Select quantization",
        )
        VariantPickerRow(
            variants = state.variants,
            selectedVariantPath = state.selectedVariantPath,
            onVariantSelected = onVariantSelected,
            recommendedVariantPath = recommendedVariantPath,
        )
    } else if (state.variants.any { it.isDownloaded }) {
        StatusMark(
            label = "Model downloaded",
            contentDescription = "Model downloaded",
            icon = Icons.Default.CheckCircle,
        )
    }

    if (!state.isSelfContained && state.components.isNotEmpty()) {
        CaraMLSectionHeader(title = "Required components")
        state.components.forEach { component ->
            ComponentRow(component = component)
        }
    } else if (state.isSelfContained && state.components.isEmpty()) {
        StatusMark(
            label = "Self-contained",
            contentDescription = "Self-contained. No extra downloads needed.",
            icon = Icons.Default.CheckCircle,
        )
    }
}

@Composable
private fun InstallBundleActionContent(
    state: InstallBundleUiState,
    onInstall: () -> Unit,
    installEnabled: Boolean,
    modifier: Modifier = Modifier,
    durableControl: DurableDownloadControlUiState?,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
) {
    val motion = LocalAuroraMotionPolicy.current
    val reportedOverallProgress = state.overallProgress?.coerceIn(0f, 1f)
    val animatedOverallProgress by animateFloatAsState(
        targetValue = reportedOverallProgress ?: 0f,
        animationSpec = tween(durationMillis = if (motion.spatialTransitionsEnabled) 180 else 0),
    )
    val displayedOverallProgress = if (motion.spatialTransitionsEnabled) {
        animatedOverallProgress
    } else {
        reportedOverallProgress ?: 0f
    }

    val command = when (durableControl?.batchState) {
        DownloadBatchState.QUEUED,
        DownloadBatchState.RUNNING,
        DownloadBatchState.WAITING_FOR_NETWORK,
        -> "Pause" to onPause
        DownloadBatchState.PAUSED -> "Resume" to onResume
        DownloadBatchState.FAILED_RETRYABLE -> "Retry" to onRetry
        else -> null
    }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.spacing8),
    ) {
        Button(
            onClick = command?.second ?: onInstall,
            enabled = command != null || (installEnabled && !state.isInstalling && !state.isReady),
            modifier = Modifier.fillMaxWidth(),
        ) {
            when {
                command != null -> Text(command.first)
                state.isReady -> {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(AppTheme.dimensions.size18))
                    Text("  Ready to use")
                }
                state.isInstalling -> Text("Installing…")
                state.totalNewDownloadBytes > 0L -> {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(AppTheme.dimensions.size18))
                    Text("  Install  ·  ${formatBytes(state.totalNewDownloadBytes)}")
                }
                else -> {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(AppTheme.dimensions.size18))
                    Text("  Install")
                }
            }
        }
        if (durableControl?.batchState in setOf(
                DownloadBatchState.QUEUED,
                DownloadBatchState.RUNNING,
                DownloadBatchState.PAUSED,
                DownloadBatchState.WAITING_FOR_NETWORK,
                DownloadBatchState.FAILED_RETRYABLE,
            )
        ) {
            TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                Text("Cancel download")
            }
        }
        if (!installEnabled && !state.isReady && !state.isInstalling) {
            Text(
                text = "Select the recommended assessed variant to continue.",
                style = AppTheme.typography.labelSmall,
                color = AppTheme.colors.onSurfaceVariant,
            )
        }

        if (state.isInstalling) {
            if (reportedOverallProgress != null) {
                LinearProgressIndicator(
                    progress = { displayedOverallProgress },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.currentDownloadLabel != null) {
                    Text(
                        text = state.currentDownloadLabel,
                        style = AppTheme.typography.labelSmall,
                        color = AppTheme.colors.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (state.overallBytesTotal > 0L) {
                    Text(
                        text = "${formatBytes(state.overallBytesReceived)} / ${formatBytes(state.overallBytesTotal)}",
                        style = AppTheme.typography.labelSmall,
                        color = AppTheme.colors.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ComponentRow(
    component: SetupComponentUiState,
    modifier: Modifier = Modifier,
) {
    val spacing = AppTheme.spacing
    val motion = LocalAuroraMotionPolicy.current
    val reportedProgress = component.progress
        ?.takeIf { it >= 0f }
        ?.coerceIn(0f, 100f)
        ?.div(100f)
    val animatedProgress by animateFloatAsState(
        targetValue = reportedProgress ?: 0f,
        animationSpec = tween(durationMillis = if (motion.spatialTransitionsEnabled) 180 else 0),
    )
    val displayedProgress = if (motion.spatialTransitionsEnabled) {
        animatedProgress
    } else {
        reportedProgress ?: 0f
    }
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = spacing.spacing12),
            horizontalArrangement = Arrangement.spacedBy(spacing.spacing12),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                component.isDownloaded -> Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = "Downloaded",
                    modifier = Modifier.size(AppTheme.dimensions.size18),
                    tint = AppTheme.colors.primary,
                )
                reportedProgress != null -> CircularProgressIndicator(
                    progress = { displayedProgress },
                    modifier = Modifier.size(AppTheme.dimensions.size18),
                    strokeWidth = AppTheme.spacing.spacing2,
                )
                else -> Icon(
                    Icons.Default.Circle,
                    contentDescription = "Not downloaded",
                    modifier = Modifier.size(AppTheme.dimensions.size18),
                    tint = AppTheme.colors.outlineVariant,
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = component.role.displayLabel,
                    style = AppTheme.typography.bodySmall,
                    color = AppTheme.colors.onSurface,
                )
                Text(
                    text = component.filePath,
                    style = AppTheme.typography.technical12,
                    color = AppTheme.colors.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(spacing.spacing8),
                    verticalArrangement = Arrangement.spacedBy(spacing.spacing8),
                ) {
                    Text(
                        text = component.repoId.substringAfterLast('/'),
                        style = AppTheme.typography.technical12,
                        color = AppTheme.colors.onSurfaceVariant,
                    )
                    component.sizeHint?.let { size ->
                        Text(
                            text = "· $size",
                            style = AppTheme.typography.technical12,
                            color = AppTheme.colors.onSurfaceVariant,
                        )
                    }
                    if (component.sharedFrom != null) {
                        StatusMark(
                            label = "Already have it",
                            contentDescription = "Already downloaded with another model",
                            icon = Icons.Default.CheckCircle,
                        )
                    } else if (component.required && !component.isDownloaded) {
                        StatusMark(
                            label = "Required",
                            contentDescription = "Required component",
                            icon = Icons.Default.Circle,
                        )
                    }
                }
                if (reportedProgress != null) {
                    LinearProgressIndicator(
                        progress = { displayedProgress },
                        modifier = Modifier.fillMaxWidth().padding(top = AppTheme.dimensions.size3),
                    )
                }
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(start = AppTheme.dimensions.size28),
            thickness = AppTheme.dimensions.size1,
            color = AppTheme.auroraColors.divider,
        )
    }
}

@Preview(name = "Install bundle - ready to install", widthDp = 412, heightDp = 720)
@Composable
private fun InstallBundleReadyToInstallPreview() {
    InstallBundlePreviewContent(installing = false)
}

@Preview(name = "Install bundle - downloading", widthDp = 412, heightDp = 720)
@Composable
private fun InstallBundleDownloadingPreview() {
    InstallBundlePreviewContent(installing = true)
}

@Preview(name = "Install bundle - large text", widthDp = 360, heightDp = 950, fontScale = 2f)
@Composable
private fun InstallBundleLargeTextPreview() {
    InstallBundlePreviewContent(installing = false)
}

@Composable
private fun InstallBundlePreviewContent(installing: Boolean) {
    val selectedPath = "flux1-dev-Q4_K_M.gguf"
    val state = InstallBundleUiState(
        variants = listOf(
            GgufFileUiState(
                path = selectedPath,
                filename = selectedPath,
                sizeBytes = 7_516_192_768L,
                isDownloaded = false,
                progress = if (installing) 0.42f else null,
            ),
            GgufFileUiState(
                path = "flux1-dev-Q5_K_M.gguf",
                filename = "flux1-dev-Q5_K_M.gguf",
                sizeBytes = 9_663_676_416L,
                isDownloaded = false,
                progress = null,
            ),
        ),
        selectedVariantPath = selectedPath,
        components = listOf(
            SetupComponentUiState(
                role = ComponentRole.VAE,
                repoId = "sample/flux-components",
                filePath = "ae.safetensors",
                sizeHint = "335 MB",
                isDownloaded = false,
                progress = if (installing) 42f else null,
                required = true,
            ),
            SetupComponentUiState(
                role = ComponentRole.CLIP_L,
                repoId = "sample/flux-components",
                filePath = "clip_l.safetensors",
                sizeHint = "246 MB",
                isDownloaded = true,
                progress = null,
                required = true,
                sharedFrom = "sample/another-model",
            ),
        ),
        totalNewDownloadBytes = 7_867_305_984L,
        isInstalling = installing,
        isSelfContained = false,
        overallProgress = if (installing) 0.42f else null,
        overallBytesReceived = if (installing) 3_304_268_800L else 0L,
        overallBytesTotal = 7_867_305_984L,
        currentDownloadLabel = if (installing) selectedPath else null,
    )
    CaraMLTheme(ThemePreferences()) {
        Surface {
            InstallBundleCard(
                modelId = "sample/flux-model",
                state = state,
                familyLabel = "FLUX.1",
                modelDescription = "Image generation model with two required components.",
                onVariantSelected = {},
                onInstall = {},
                modifier = Modifier.padding(AppTheme.spacing.spacing16),
                recommendedVariantPath = selectedPath,
            )
        }
    }
}
