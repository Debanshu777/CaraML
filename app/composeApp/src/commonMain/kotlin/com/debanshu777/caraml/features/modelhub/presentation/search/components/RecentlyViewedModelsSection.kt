package com.debanshu777.caraml.features.modelhub.presentation.search.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.ui.motion.modelIdentityTransition
import com.debanshu777.caraml.core.theme.AppTheme
import com.debanshu777.caraml.core.data.settings.RecentlyViewedModel
import com.debanshu777.caraml.features.modelhub.presentation.search.recentlyViewedModelsForDisplay

/** A compact grid for the four most recent successful detail visits. */
@Composable
fun RecentlyViewedModelsSection(
    models: List<RecentlyViewedModel>,
    onOpen: (RecentlyViewedModel) -> Unit,
    modifier: Modifier = Modifier,
) {
    val recent = recentlyViewedModelsForDisplay(models)
    if (recent.isEmpty()) return
    Column(
        modifier.fillMaxWidth().padding(bottom = AppTheme.spacing.spacing24).testTag("recently-viewed-models"),
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.spacing8),
    ) {
        Text("Recently viewed", style = AppTheme.typography.labelLarge, color = AppTheme.colors.onSurface, modifier = Modifier.semantics { heading() })
        recent.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.spacing8)) {
                row.forEach { entry ->
                    Surface(
                        onClick = { onOpen(entry) },
                        modifier = Modifier.weight(1f).heightIn(min = AppTheme.spacing.spacing48),
                        shape = AppTheme.shapes.small,
                        color = AppTheme.colors.surfaceContainerLowest.copy(alpha = 0.8f),
                        border = BorderStroke(1.dp, AppTheme.colors.outlineVariant),
                    ) {
                        Column(
                            Modifier.padding(horizontal = AppTheme.spacing.spacing8, vertical = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.spacing4),
                        ) {
                            Text(
                                entry.modelId.substringAfterLast('/'),
                                modifier = Modifier.modelIdentityTransition("recent:${entry.identity}"),
                                style = AppTheme.typography.labelLarge,
                                color = AppTheme.colors.onSurface,
                                overflow = TextOverflow.Ellipsis,
                                maxLines = 1,
                            )
                            Text(
                                entry.author ?: entry.modelId.substringBefore('/'),
                                style = AppTheme.typography.labelSmall,
                                color = AppTheme.colors.onSurfaceVariant,
                                overflow = TextOverflow.Ellipsis,
                                maxLines = 1,
                            )
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}
