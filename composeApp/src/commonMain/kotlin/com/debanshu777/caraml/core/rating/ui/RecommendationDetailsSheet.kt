package com.debanshu777.caraml.core.rating.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.recommendation.AssessmentReason
import com.debanshu777.caraml.core.recommendation.Confidence
import com.debanshu777.caraml.core.recommendation.DiffusionRunPlan
import com.debanshu777.caraml.core.recommendation.DiffusionWorkloadConfig
import com.debanshu777.caraml.core.recommendation.EstimateRange
import com.debanshu777.caraml.core.recommendation.LlmRunPlan
import com.debanshu777.caraml.core.recommendation.LlmWorkloadConfig
import com.debanshu777.caraml.core.recommendation.PerformanceEstimate
import com.debanshu777.caraml.core.recommendation.PersonalizedRecommendation
import com.debanshu777.caraml.core.recommendation.RecommendationCategory
import com.debanshu777.caraml.core.recommendation.RiskTolerance
import com.debanshu777.caraml.core.recommendation.WorkloadConfig
import com.debanshu777.caraml.core.theme.AuroraSurfaceLevel
import com.debanshu777.caraml.core.theme.prismShapes

data class RecommendationPresentation(
    val selectedVariant: String,
    val assumedWorkload: String,
    val memoryInterval: String,
    val storageInterval: String,
    val expectedSpeed: String,
    val confidence: String,
    val selectedPlan: String,
    val fallbackPlan: String,
    val reasons: List<String>,
)

fun recommendationCategoryLabel(category: RecommendationCategory): String = when (category) {
    RecommendationCategory.RECOMMENDED -> "Recommended"
    RecommendationCategory.USABLE -> "Usable"
    RecommendationCategory.RISKY -> "Risky"
    RecommendationCategory.NOT_SUITABLE -> "Not suitable"
    RecommendationCategory.INCOMPATIBLE -> "Incompatible"
    RecommendationCategory.NEEDS_INFORMATION -> "Needs information"
}

fun recommendationPrimaryReasonLabel(reason: AssessmentReason): String =
    reason.name.lowercase().split('_').joinToString(" ").replaceFirstChar { it.uppercase() }

fun recommendationSemantics(recommendation: PersonalizedRecommendation): String {
    val safetyConfidence = recommendation.confidence?.let {
        listOf(it.compatibility, it.memory, it.storage).minByOrNull(Confidence::ordinal)
    }
    val reason = recommendation.reasons.firstOrNull()?.let(::recommendationPrimaryReasonLabel)
        ?: "No reason available"
    return "${recommendationCategoryLabel(recommendation.category)}. " +
        "${safetyConfidence?.name?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "Unavailable"} confidence. $reason."
}

fun recommendationPresentation(
    recommendation: PersonalizedRecommendation,
    selectedVariant: String?,
    workload: WorkloadConfig?,
): RecommendationPresentation {
    val assessment = recommendation.selectedPlanAssessment
    return RecommendationPresentation(
        selectedVariant = selectedVariant ?: "Variant unavailable",
        assumedWorkload = workload?.displayLabel() ?: "Workload unavailable",
        memoryInterval = assessment?.let {
            listOfNotNull(it.hostMemoryBytes, it.gpuMemoryBytes, it.sharedMemoryBytes)
                .maxByOrNull(EstimateRange::highBytes)?.displayBytes()
        } ?: "Memory estimate unavailable",
        storageInterval = assessment?.storageBytes?.displayBytes() ?: "Storage estimate unavailable",
        expectedSpeed = assessment?.performance?.displayLabel() ?: "Speed not verified",
        confidence = recommendation.confidence?.let { confidence ->
            listOf(confidence.compatibility, confidence.memory, confidence.storage)
                .minByOrNull(Confidence::ordinal)?.name?.lowercase()?.replaceFirstChar { it.uppercase() }
        } ?: "Unavailable",
        selectedPlan = recommendation.selectedPlan.displayLabel(),
        fallbackPlan = recommendation.fallbackPlan.displayLabel(),
        reasons = recommendation.reasons.map(::recommendationPrimaryReasonLabel),
    )
}

@Composable
fun RecommendationDetailsContent(
    modelId: String,
    recommendation: PersonalizedRecommendation,
    presentation: RecommendationPresentation,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .widthIn(max = 840.dp)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Recommendation details", style = MaterialTheme.typography.titleLarge)
        Text(modelId, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SuitabilityChip(recommendation = recommendation)
        if (recommendation.profile.riskTolerance == RiskTolerance.EXPERIMENTAL &&
            AssessmentReason.TIGHT_MEMORY_FIT in recommendation.reasons
        ) {
            Text(
                "Experimental tight fit: memory headroom may be small.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        HorizontalDivider()
        DetailLine("Selected variant", presentation.selectedVariant)
        DetailLine("Assumed workload", presentation.assumedWorkload)
        DetailLine("Memory", presentation.memoryInterval)
        DetailLine("Storage", presentation.storageInterval)
        DetailLine("Expected speed", presentation.expectedSpeed)
        DetailLine("Confidence", presentation.confidence)
        DetailLine("Selected plan", presentation.selectedPlan)
        DetailLine("Fallback plan", presentation.fallbackPlan)
        Text("Reasons", style = MaterialTheme.typography.titleSmall)
        presentation.reasons.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecommendationDetailsSheet(
    modelId: String,
    recommendation: PersonalizedRecommendation,
    presentation: RecommendationPresentation,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        shape = MaterialTheme.prismShapes.modal,
        containerColor = AuroraSurfaceLevel.Floating.containerColor(MaterialTheme.colorScheme),
    ) {
        RecommendationDetailsContent(modelId, recommendation, presentation)
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun WorkloadConfig.displayLabel(): String = when (this) {
    is LlmWorkloadConfig -> "${contextTokens.withCommas()} context tokens, batch $batchSize"
    is DiffusionWorkloadConfig -> "${width}×$height, $steps steps, $frameCount frame${if (frameCount == 1) "" else "s"}"
}

private fun Any?.displayLabel(): String = when (this) {
    is LlmRunPlan -> buildString {
        append("${contextTokens.withCommas()} context, batch $batchSize, ${backend.name}")
        if (compromises.isNotEmpty()) append("; ").append(compromises.joinToString { it.name.humanize() })
    }
    is DiffusionRunPlan -> buildString {
        append("${width}×$height, $steps steps, ${backend.name}")
        if (compromises.isNotEmpty()) append("; ").append(compromises.joinToString { it.name.humanize() })
    }
    null -> "Plan unavailable"
    else -> toString()
}

private fun PerformanceEstimate.displayLabel(): String = when (this) {
    is PerformanceEstimate.Llm -> "${decodeTokensPerSecond.low}–${decodeTokensPerSecond.high} tokens/s"
    is PerformanceEstimate.DiffusionImage -> "${totalTimeSeconds.low}–${totalTimeSeconds.high} seconds"
    is PerformanceEstimate.DiffusionVideo -> "${totalTimeSeconds.low}–${totalTimeSeconds.high} seconds"
    is PerformanceEstimate.Unknown -> recommendationPrimaryReasonLabel(reason)
}

private fun EstimateRange.displayBytes(): String = "${lowBytes.humanBytes()}–${highBytes.humanBytes()}"
private fun Long.humanBytes(): String = when {
    this >= 1024L * 1024L * 1024L -> "${this / (1024L * 1024L * 1024L)} GiB"
    this >= 1024L * 1024L -> "${this / (1024L * 1024L)} MiB"
    else -> "$this bytes"
}
private fun Int.withCommas(): String = toString().reversed().chunked(3).joinToString(",").reversed()
private fun String.humanize(): String = lowercase().replace('_', ' ')
