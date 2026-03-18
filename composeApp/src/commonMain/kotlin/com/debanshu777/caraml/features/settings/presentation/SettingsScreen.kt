package com.debanshu777.caraml.features.settings.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.drawer.LocalDrawerController
import kotlin.math.round

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    isRootDestination: Boolean,
    modifier: Modifier = Modifier
) {
    val settings by viewModel.settings.collectAsState()
    val drawerController = LocalDrawerController.current

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    if (isRootDestination) {
                        IconButton(onClick = { drawerController.toggle() }) {
                            Icon(Icons.Default.Menu, contentDescription = "Open menu")
                        }
                    } else {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
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

            Spacer(modifier = Modifier.weight(1f, fill = true))
        }
    }
}
