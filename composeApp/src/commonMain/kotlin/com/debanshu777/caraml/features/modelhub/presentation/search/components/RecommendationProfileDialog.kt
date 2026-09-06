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
import com.debanshu777.caraml.core.recommendation.RecommendationProfile
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
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
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
