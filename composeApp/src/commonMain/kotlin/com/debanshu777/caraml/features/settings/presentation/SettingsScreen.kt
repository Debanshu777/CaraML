package com.debanshu777.caraml.features.settings.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.drawer.LocalDrawerController
import com.debanshu777.caraml.core.settings.KvQuantPreset
import com.debanshu777.caraml.core.theme.LocalSpacing
import com.debanshu777.caraml.core.theme.ThemeViewModel
import org.koin.compose.viewmodel.koinViewModel
import kotlin.math.round

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier,
    themeViewModel: ThemeViewModel = koinViewModel(),
) {
    val settings by viewModel.settings.collectAsState()
    val drawerController = LocalDrawerController.current

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = { drawerController.toggle() }) {
                        Icon(Icons.Default.Menu, contentDescription = "Open menu")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = LocalSpacing.current.l, vertical = LocalSpacing.current.m),
            verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.l)
        ) {
            AppearanceSection(viewModel = themeViewModel)

            OutlinedTextField(
                value = settings.systemPrompt,
                onValueChange = viewModel::updateSystemPrompt,
                label = { Text("System prompt") },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp),
                maxLines = 5
            )

            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Temperature (${round(settings.temperature * 10f) / 10f})",
                    style = MaterialTheme.typography.bodyMedium
                )
                Slider(
                    value = settings.temperature,
                    onValueChange = viewModel::updateTemperature,
                    valueRange = 0f..2f,
                    steps = 19
                )
                Text(
                    text = "Controls randomness (0 = deterministic, 2 = very creative)",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            KvCacheSection(
                selected = settings.kvQuantPreset,
                onSelect = viewModel::updateKvQuantPreset,
            )

            GpuAccelerationSection(
                enabled = settings.useGpu,
                onToggle = viewModel::updateUseGpu,
            )

            Spacer(modifier = Modifier.weight(1f, fill = true))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GpuAccelerationSection(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(LocalSpacing.current.l),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "GPU Acceleration (Vulkan)",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "Use GPU for faster inference (requires Vulkan support)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KvCacheSection(
    selected: KvQuantPreset,
    onSelect: (KvQuantPreset) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            modifier = Modifier.padding(LocalSpacing.current.l),
            verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.m),
        ) {
            Text(
                text = "KV Cache Quality",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "Controls the numeric precision of the attention key/value cache. Lower precision saves memory and speeds up prefill; higher precision improves output quality.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(LocalSpacing.current.s),
                contentPadding = PaddingValues(vertical = LocalSpacing.current.xs),
            ) {
                items(KvQuantPreset.entries) { preset ->
                    FilterChip(
                        selected = selected == preset,
                        onClick = { onSelect(preset) },
                        label = { Text(preset.chipLabel()) },
                        colors = FilterChipDefaults.filterChipColors(),
                    )
                }
            }
            Text(
                text = selected.description(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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

