package com.debanshu777.caraml.features.settings.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.drawer.LocalDrawerController
import com.debanshu777.caraml.core.recommendation.RecommendationRolloutModeSource
import com.debanshu777.caraml.core.settings.KvQuantPreset
import com.debanshu777.caraml.core.theme.LocalSpacing
import com.debanshu777.caraml.core.theme.ThemeViewModel
import com.debanshu777.caraml.core.ui.components.CaraMLPane
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
    val drawerController = LocalDrawerController.current

    Scaffold(
        modifier = modifier,
        containerColor = Color.Transparent,
        topBar = {
            CaraMLPrimaryTopBar(
                title = "Settings",
                onMenuClick = drawerController::toggle,
            )
        },
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
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = LocalSpacing.current.m),
                verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.l),
            ) {
                AppearanceSection(viewModel = themeViewModel)

                if (profileUiState.isAvailable) {
                    CaraMLPane(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(LocalSpacing.current.l),
                            verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.m),
                        ) {
                            RecommendationProfileSection(
                                profile = profile,
                                onRiskToleranceChange = viewModel::updateRiskTolerance,
                                onOptimizationPriorityChange = viewModel::updateOptimizationPriority,
                                enabled = !profileSaving,
                            )
                            if (profileError != null) {
                                Text(
                                    text = profileError.orEmpty(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            Text(
                                text = "Optionally run a 3-second local benchmark to tune device-specific estimates.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Button(
                                onClick = { calibrationDialogVisible = true },
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) {
                                Text("Run calibration")
                            }
                        }
                    }
                }

                CaraMLPane(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(LocalSpacing.current.l),
                        verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.m),
                    ) {
                        Text(
                            text = "System prompt",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        OutlinedTextField(
                            value = settings.systemPrompt,
                            onValueChange = viewModel::updateSystemPrompt,
                            label = { Text("System prompt") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 140.dp),
                            maxLines = 5,
                        )
                    }
                }

                CaraMLPane(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(LocalSpacing.current.l),
                        verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.s),
                    ) {
                        val displayTemperature = round(settings.temperature * 10f) / 10f
                        Text(
                            text = "Temperature ($displayTemperature)",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Slider(
                            value = settings.temperature,
                            onValueChange = viewModel::updateTemperature,
                            valueRange = 0f..2f,
                            steps = 19,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .semantics {
                                    contentDescription = "Temperature $displayTemperature"
                                },
                        )
                        Text(
                            text = "Controls randomness (0 = deterministic, 2 = very creative)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                KvCacheSection(
                    selected = settings.kvQuantPreset,
                    onSelect = viewModel::updateKvQuantPreset,
                )

                GpuAccelerationSection(
                    enabled = settings.useGpu,
                    onToggle = viewModel::updateUseGpu,
                )

                Spacer(
                    modifier = Modifier.heightIn(min = LocalSpacing.current.m),
                )
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
internal fun GpuAccelerationSection(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    CaraMLPane(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
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
                .padding(LocalSpacing.current.l),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "GPU Acceleration (Vulkan)",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "Use GPU for faster inference (requires Vulkan support)",
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
    }
}

@Composable
internal fun KvCacheSection(
    selected: KvQuantPreset,
    onSelect: (KvQuantPreset) -> Unit,
    modifier: Modifier = Modifier,
) {
    CaraMLPane(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(LocalSpacing.current.l),
            verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.m),
        ) {
            Text(
                text = "KV Cache Quality",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "Current: ${selected.chipLabel()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(LocalSpacing.current.s),
                contentPadding = PaddingValues(vertical = LocalSpacing.current.xs),
            ) {
                items(KvQuantPreset.entries) { preset ->
                    SettingFilterChip(
                        selected = selected == preset,
                        onClick = { onSelect(preset) },
                        label = preset.chipLabel(),
                        selectedIndicatorContentDescription =
                            "Selected KV cache ${preset.chipLabel()}",
                        modifier = Modifier
                            .semantics {
                                stateDescription = if (selected == preset) "Selected" else "Not selected"
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
                summary = "Lower precision saves memory and speeds up prefill; higher precision improves output quality.",
                details = "The attention key/value cache stores reusable inference state. Lower precision reduces its memory footprint and can speed up prefill, while higher precision preserves more numeric detail.",
            )
        }
    }
}

private fun KvQuantPreset.chipLabel(): String = when (this) {
    KvQuantPreset.AUTO    -> "Auto"
    KvQuantPreset.Q4_F16  -> "Q4/F16"
    KvQuantPreset.Q8_Q8   -> "Q8/Q8"
    KvQuantPreset.F16_F16 -> "F16/F16"
}

private fun KvQuantPreset.description(): String = when (this) {
    KvQuantPreset.AUTO    -> "Automatic — selects Q4/F16 below 3 GB RAM, Q8/Q8 below 6 GB, F16/F16 above 6 GB."
    KvQuantPreset.Q4_F16  -> "Q4/F16 — minimum memory footprint. Slight quality trade-off on long contexts."
    KvQuantPreset.Q8_Q8   -> "Q8/Q8 — balanced precision and memory. Good for most devices."
    KvQuantPreset.F16_F16 -> "F16/F16 — highest quality. Requires the most memory; best for high-RAM devices."
}
