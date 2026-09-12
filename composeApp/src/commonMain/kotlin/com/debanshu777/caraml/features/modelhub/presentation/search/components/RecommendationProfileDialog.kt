package com.debanshu777.caraml.features.modelhub.presentation.search.components

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.debanshu777.caraml.core.recommendation.RecommendationProfile
import com.debanshu777.caraml.core.recommendation.CalibrationDeferralReason
import com.debanshu777.caraml.core.recommendation.CalibrationRunResult
import com.debanshu777.caraml.features.modelhub.presentation.search.QuickCalibrationUiState
import com.debanshu777.caraml.features.settings.presentation.RecommendationProfileSection

@Composable
fun RecommendationProfileDialog(
    initial: RecommendationProfile,
    onContinue: (RecommendationProfile) -> Unit,
    onDismissWithBalanced: () -> Unit,
    submitting: Boolean = false,
    errorMessage: String? = null,
) {
    var draft by remember(initial) { mutableStateOf(initial) }

    AlertDialog(
        onDismissRequest = {
            if (!submitting) onDismissWithBalanced()
        },
        title = { Text("Personalize model recommendations") },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .semantics {
                        contentDescription = "Selected risk: ${draft.riskTolerance.name.displayName()}. " +
                            "Selected priority: ${draft.optimizationPriority.name.displayName()}."
                    },
            ) {
                RecommendationProfileSection(
                    profile = draft,
                    onRiskToleranceChange = { draft = draft.copy(riskTolerance = it) },
                    onOptimizationPriorityChange = {
                        draft = draft.copy(optimizationPriority = it)
                    },
                    enabled = !submitting,
                )
                if (errorMessage != null) {
                    Text(
                        text = errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onContinue(draft) },
                enabled = !submitting,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text("Continue")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismissWithBalanced,
                enabled = !submitting,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text("Use Balanced")
            }
        },
    )
}

@Composable
fun QuickCalibrationDialog(
    state: QuickCalibrationUiState,
    onRun: () -> Unit,
    onRunWithUnknownPower: () -> Unit,
    onCancel: () -> Unit,
    onSkip: () -> Unit,
) {
    val result = state.result
    AlertDialog(
        onDismissRequest = { if (!state.running) onSkip() },
        title = { Text(if (state.running) "Optimizing for this device" else "Improve recommendations") },
        text = {
            Column {
                Text(
                    when {
                        state.running -> "A local, synthetic benchmark is running. It takes about 3 seconds and never reads prompts, model content, or file paths."
                        result == CalibrationRunResult.RequiresConfirmation ->
                            "Power status could not be verified. Running now may use more battery. Continue only if that is okay."
                        result is CalibrationRunResult.Deferred -> result.message()
                        result == CalibrationRunResult.TimedOut -> "Calibration timed out safely. No partial result was saved."
                        result == CalibrationRunResult.Cancelled -> "Calibration was cancelled. No partial result was saved."
                        result is CalibrationRunResult.Completed -> "Calibration completed. New recommendations use the local measurements immediately."
                        result == CalibrationRunResult.Failed -> "Calibration could not finish. You can retry or skip it."
                        else -> "Run an optional 3-second local benchmark to tune performance estimates for this device. Only bounded numeric timings are stored."
                    },
                )
            }
        },
        confirmButton = {
            Button(
                onClick = if (result == CalibrationRunResult.RequiresConfirmation) {
                    onRunWithUnknownPower
                } else {
                    onRun
                },
                enabled = !state.running,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(
                    when {
                        result == CalibrationRunResult.RequiresConfirmation -> "Run anyway"
                        result is CalibrationRunResult.Completed -> "Run again"
                        else -> "Run calibration"
                    },
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = if (state.running) onCancel else onSkip,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(if (state.running) "Cancel" else if (result is CalibrationRunResult.Completed) "Close" else "Skip")
            }
        },
    )
}

private fun CalibrationRunResult.Deferred.message(): String = when (reason) {
    CalibrationDeferralReason.THERMAL -> "The device is too warm to calibrate accurately. Cool it down, then retry."
    CalibrationDeferralReason.POWER_SAVER -> "Turn off power saver before calibrating."
    CalibrationDeferralReason.LOW_MEMORY -> "Memory pressure is too high to calibrate safely right now."
    CalibrationDeferralReason.BACKEND_UNAVAILABLE -> "A supported compute backend is not available right now."
    CalibrationDeferralReason.INSUFFICIENT_MEMORY -> "At least 4 MiB of safe working memory is required."
}

private fun String.displayName(): String = lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
