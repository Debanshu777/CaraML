package com.debanshu777.caraml.features.settings.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.recommendation.RecommendationRolloutModeSource
import com.debanshu777.caraml.core.settings.AppSettings
import com.debanshu777.caraml.core.settings.KvQuantPreset
import com.debanshu777.caraml.core.theme.LocalSpacing
import com.debanshu777.caraml.core.theme.ThemeViewModel
import com.debanshu777.caraml.core.theme.auroraColors
import com.debanshu777.caraml.core.theme.prism
import com.debanshu777.caraml.core.ui.components.CaraMLPrimaryTopBar
import com.debanshu777.caraml.core.ui.layout.AppContentKind
import com.debanshu777.caraml.core.ui.layout.ResponsiveContentPane
import com.debanshu777.caraml.features.modelhub.presentation.search.components.QuickCalibrationDialog
import com.debanshu777.caraml.features.modelhub.presentation.search.profileUiState
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import kotlin.math.round

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier,
    themeViewModel: ThemeViewModel = koinViewModel(),
    rolloutModeSource: RecommendationRolloutModeSource = koinInject(),
) {
    val settings by viewModel.settings.collectAsState()
    val settingsLoaded by viewModel.settingsLoaded.collectAsState()
    val profile by viewModel.effectiveRecommendationProfile.collectAsState()
    val profileSaving by viewModel.isRecommendationProfileSaving.collectAsState()
    val profileError by viewModel.recommendationProfileError.collectAsState()
    val quickCalibration by viewModel.quickCalibration.collectAsState()
    var calibrationDialogVisible by remember { mutableStateOf(false) }
    val profileUiState = profileUiState(
        settings = settings,
        rolloutMode = rolloutModeSource.current(),
        settingsLoaded = settingsLoaded,
    )
    val spacing = LocalSpacing.current

    Scaffold(
        modifier = modifier,
        containerColor = Color.Transparent,
        topBar = { CaraMLPrimaryTopBar(title = "Settings") },
    ) { paddingValues ->
        ResponsiveContentPane(
            kind = AppContentKind.Settings,
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("settings-scroll")
                    .verticalScroll(rememberScrollState())
                    .padding(top = spacing.l, bottom = spacing.xxxl),
                verticalArrangement = Arrangement.spacedBy(spacing.xxl),
            ) {
                AppearanceSection(viewModel = themeViewModel)

                if (profileUiState.isAvailable) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("settings-section-recommendations"),
                    ) {
                        SettingsSectionHeader(
                            title = "Recommendations",
                            supportingText =
                                "Choose how CaraML balances device headroom, speed, and quality.",
                        )
                        RecommendationProfileSection(
                            profile = profile,
                            onRiskToleranceChange = viewModel::updateRiskTolerance,
                            onOptimizationPriorityChange = viewModel::updateOptimizationPriority,
                            enabled = !profileSaving,
                            listStyle = true,
                        )
                        if (profileError != null) {
                            Text(
                                text = profileError.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = spacing.s),
                            )
                        }
                        CalibrationSetting(
                            onRunCalibration = { calibrationDialogVisible = true },
                            modifier = Modifier.padding(vertical = spacing.l),
                        )
                        SettingsRowDivider(tag = "settings-divider-recommendations")
                    }
                }

                GenerationSettingsSection(
                    settings = settings,
                    onSystemPromptChange = viewModel::updateSystemPrompt,
                    onTemperatureChange = viewModel::updateTemperature,
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings-section-runtime"),
                ) {
                    SettingsSectionHeader(
                        title = "Runtime",
                        supportingText =
                            "Tune the local inference engine for this device.",
                    )
                    KvCacheSection(
                        selected = settings.kvQuantPreset,
                        onSelect = viewModel::updateKvQuantPreset,
                    )
                    GpuAccelerationSection(
                        enabled = settings.useGpu,
                        onToggle = viewModel::updateUseGpu,
                    )
                }
            }
        }
    }

    if (calibrationDialogVisible) {
        QuickCalibrationDialog(
            state = quickCalibration,
            onRun = { viewModel.runQuickCalibration() },
            onRunWithUnknownPower = { viewModel.runQuickCalibration(allowUnknownPower = true) },
            onCancel = viewModel::cancelQuickCalibration,
            onSkip = {
                viewModel.skipQuickCalibration()
                calibrationDialogVisible = false
            },
        )
    }
}

@Composable
internal fun SettingsSectionHeader(
    title: String,
    supportingText: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.xs),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.prism.sectionTitle,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = supportingText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun SettingsRowDivider(
    tag: String,
    modifier: Modifier = Modifier,
) {
    HorizontalDivider(
        modifier = modifier
            .fillMaxWidth()
            .testTag(tag),
        thickness = 1.dp,
        color = MaterialTheme.auroraColors.divider,
    )
}

@Composable
private fun CalibrationSetting(
    onRunCalibration: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("settings-calibration-group"),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(spacing.l),
            verticalArrangement = Arrangement.spacedBy(spacing.m),
        ) {
            Text(
                text = "Calibrate this device",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "Run a short local benchmark to tune device-specific estimates.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = onRunCalibration,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text("Run calibration")
            }
        }
    }
}

@Composable
private fun GenerationSettingsSection(
    settings: AppSettings,
    onSystemPromptChange: (String) -> Unit,
    onTemperatureChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    val displayTemperature = round(settings.temperature * 10f) / 10f
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("settings-section-generation"),
    ) {
        SettingsSectionHeader(
            title = "Generation",
            supportingText = "Set defaults for new local conversations.",
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = spacing.l),
            verticalArrangement = Arrangement.spacedBy(spacing.m),
        ) {
            Text(
                text = "System prompt",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "These instructions are applied when a new conversation begins.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = settings.systemPrompt,
                onValueChange = onSystemPromptChange,
                label = { Text("Instructions") },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 140.dp),
                maxLines = 5,
            )
        }
        SettingsRowDivider(tag = "settings-divider-system-prompt")
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = spacing.l),
            verticalArrangement = Arrangement.spacedBy(spacing.s),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Temperature",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = displayTemperature.toString(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .semantics(mergeDescendants = true) {
                        contentDescription = "Temperature $displayTemperature"
                    },
                contentAlignment = Alignment.Center,
            ) {
                Slider(
                    value = settings.temperature,
                    onValueChange = onTemperatureChange,
                    valueRange = 0f..2f,
                    steps = 19,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text(
                text = "0 is deterministic; 2 explores more varied responses.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SettingsRowDivider(tag = "settings-divider-generation")
    }
}

@Composable
internal fun GpuAccelerationSection(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .toggleable(
                    value = enabled,
                    role = Role.Switch,
                    onValueChange = onToggle,
                )
                .semantics(mergeDescendants = true) {
                    contentDescription = "GPU acceleration (Vulkan)"
                    stateDescription = if (enabled) {
                        "GPU acceleration enabled"
                    } else {
                        "GPU acceleration disabled"
                    }
                }
                .padding(vertical = spacing.l),
            horizontalArrangement = Arrangement.spacedBy(spacing.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                Text(
                    text = "GPU acceleration",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "Use Vulkan when available for faster local inference.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = null,
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
            )
        }
        SettingsRowDivider(tag = "settings-divider-runtime")
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun KvCacheSection(
    selected: KvQuantPreset,
    onSelect: (KvQuantPreset) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = spacing.l),
        verticalArrangement = Arrangement.spacedBy(spacing.s),
    ) {
        Text(
            text = "KV cache quality",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = "Current: ${selected.chipLabel()}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(spacing.s),
            verticalArrangement = Arrangement.spacedBy(spacing.s),
        ) {
            KvQuantPreset.entries.forEach { preset ->
                val isSelected = selected == preset
                SettingFilterChip(
                    selected = isSelected,
                    onClick = { onSelect(preset) },
                    label = preset.chipLabel(),
                    selectedIndicatorTestTag =
                        "Selected KV cache ${preset.chipLabel()}",
                    modifier = Modifier.semantics {
                        contentDescription = "KV cache ${preset.chipLabel()}, " +
                            if (isSelected) "selected" else "not selected"
                        stateDescription = if (isSelected) "Selected" else "Not selected"
                    },
                )
            }
        }
        Text(
            text = selected.description(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ExpandableSettingDescription(
            summary = "Lower precision saves memory and speeds up prefill; " +
                "higher precision improves output quality.",
            details = "The attention key/value cache stores reusable inference state. " +
                "Lower precision reduces its memory footprint and can speed up prefill, while " +
                "higher precision preserves more numeric detail.",
        )
        SettingsRowDivider(
            tag = "settings-divider-kv-cache",
            modifier = Modifier.padding(top = spacing.s),
        )
    }
}

private fun KvQuantPreset.chipLabel(): String = when (this) {
    KvQuantPreset.AUTO -> "Auto"
    KvQuantPreset.Q4_F16 -> "Q4/F16"
    KvQuantPreset.Q8_Q8 -> "Q8/Q8"
    KvQuantPreset.F16_F16 -> "F16/F16"
}

private fun KvQuantPreset.description(): String = when (this) {
    KvQuantPreset.AUTO ->
        "Automatic — selects Q4/F16 below 3 GB RAM, Q8/Q8 below 6 GB, " +
            "F16/F16 above 6 GB."
    KvQuantPreset.Q4_F16 ->
        "Q4/F16 — minimum memory footprint. Slight quality trade-off on long contexts."
    KvQuantPreset.Q8_Q8 ->
        "Q8/Q8 — balanced precision and memory. Good for most devices."
    KvQuantPreset.F16_F16 ->
        "F16/F16 — highest quality. Requires the most memory; best for high-RAM devices."
}
