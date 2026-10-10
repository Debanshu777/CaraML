package com.debanshu777.caraml.features.chat.presentation.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.debanshu777.caraml.core.theme.AppTheme
import com.debanshu777.caraml.core.ui.components.BrandButton
import com.debanshu777.caraml.core.ui.components.BrandButtonStyle
import com.debanshu777.caraml.core.ui.icons.AppIcons
import com.debanshu777.caraml.features.chat.data.LiveGenerationStats
import com.debanshu777.caraml.features.chat.domain.GenerationMode

/** Conversation controls keep a constant footprint while live context changes. */
@Composable
internal fun ConversationComposerControls(
    generationMode: GenerationMode,
    modelName: String?,
    isGenerating: Boolean,
    canSend: Boolean,
    onModelClick: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    liveStats: LiveGenerationStats?,
    contextIndicator: @Composable RowScope.() -> Unit,
) {
    var detailsOpen by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(start = AppTheme.spacing.spacing8, end = AppTheme.spacing.spacing12, bottom = AppTheme.spacing.spacing8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.spacing8),
    ) {
        BrandButton(
            onClick = onModelClick,
            enabled = !isGenerating,
            style = BrandButtonStyle.Secondary,
            modifier = Modifier.weight(1f).heightIn(min = AppTheme.spacing.spacing48).testTag("chat-model-picker")
                .semantics { contentDescription = "Select model. Current model ${modelName ?: "none"}" },
            contentPadding = PaddingValues(horizontal = AppTheme.spacing.spacing8),
        ) {
            Text(modelName ?: "Select model", Modifier.weight(1f, fill = false), maxLines = 1,
                overflow = TextOverflow.Ellipsis, style = AppTheme.typography.labelBase)
            Spacer(Modifier.width(AppTheme.spacing.spacing8))
            Icon(AppIcons.ChevronDown, null, Modifier.size(AppTheme.dimensions.size20))
        }
        if (generationMode == GenerationMode.Text && liveStats != null) {
            IconButton(
                onClick = { detailsOpen = true },
                modifier = Modifier.size(AppTheme.spacing.spacing48).testTag("chat-generation-details"),
            ) {
                Icon(AppIcons.Info, contentDescription = "Generation details", Modifier.size(AppTheme.dimensions.size20))
            }
        }
        BrandButton(
            onClick = if (isGenerating) onStop else onSend,
            enabled = isGenerating || canSend,
            style = if (isGenerating) BrandButtonStyle.Secondary else BrandButtonStyle.Primary,
            modifier = Modifier.widthIn(min = AppTheme.dimensions.size96).heightIn(min = AppTheme.spacing.spacing48).semantics {
                contentDescription = if (isGenerating) "Stop generation" else "Send message"
            },
            contentPadding = PaddingValues(AppTheme.spacing.spacing12),
        ) {
            Text(if (isGenerating) "Stop" else "Send", style = AppTheme.typography.labelBase)
            Spacer(Modifier.width(AppTheme.spacing.spacing8))
            Icon(if (isGenerating) AppIcons.Stop else AppIcons.Send, null, Modifier.size(AppTheme.dimensions.size20))
        }
    }
    if (detailsOpen) {
        AlertDialog(
            onDismissRequest = { detailsOpen = false },
            title = { Text("Generation details") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.spacing12)) {
                    liveStats?.let { stats ->
                        GenerationStatsBar(stats)
                        Text("Context: ${stats.contextUsed} / ${stats.contextLimit} tokens", style = AppTheme.typography.bodySmall)
                        Text("Generated tokens include reasoning and the answer.", style = AppTheme.typography.bodySmall)
                    }
                    Row { contextIndicator() }
                }
            },
            confirmButton = { TextButton(onClick = { detailsOpen = false }) { Text("Close") } },
        )
    }
}
