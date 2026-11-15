package com.debanshu777.caraml.features.modelhub.presentation.details.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading as semanticHeading
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.theme.AppTheme
import com.debanshu777.caraml.core.ui.icons.AppIcons
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelMetric
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelAuthorAvatar
import com.debanshu777.caraml.core.ui.motion.modelIdentityTransition
import com.debanshu777.caraml.features.modelhub.presentation.search.components.formatCompactMetric
import com.debanshu777.caraml.sdk.discovery.huggingface.model.ModelDetailResponse

@Composable
internal fun ModelOverviewSection(
    model: ModelDetailResponse,
    description: String?,
    avatarUrl: String? = null,
    transitionKey: String? = null,
    fitStatus: (@Composable () -> Unit)? = null,
    onTitleBottomChanged: (Float) -> Unit = {},
) {
    val spacing = AppTheme.spacing
    val id = model.modelId ?: model.id.orEmpty()
    val heading = splitRepositoryId(id)
    val owner = heading.owner ?: model.author?.takeIf(String::isNotBlank)
    val task = model.pipelineTag?.takeIf(String::isNotBlank) ?: model.cardData?.pipelineTag?.takeIf(String::isNotBlank)
    val parameters = model.gguf?.total?.takeIf { it > 0 } ?: model.safetensors?.total?.takeIf { it > 0 }
    val downloads = model.downloads?.takeIf { it >= 0 }
    val likes = model.likes?.takeIf { it >= 0 }
    val modelCardUrl = id.takeIf { Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$").matches(it) }
        ?.let { "https://huggingface.co/$it" }
    val nameStart = owner?.let { it.length + 1 } ?: 0
    var titleLayout by remember(id) { mutableStateOf<TextLayoutResult?>(null) }
    val underlineColor = AppTheme.colors.onSurface

    Column(
        Modifier.fillMaxWidth().testTag("detail-overview"),
        verticalArrangement = Arrangement.spacedBy(spacing.spacing8),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.spacing12), verticalAlignment = Alignment.CenterVertically) {
            ModelAuthorAvatar(
                avatarUrl = avatarUrl?.takeIf(String::isNotBlank) ?: owner?.takeIf { Regex("^[A-Za-z0-9_.-]+$").matches(it) }
                    ?.let { "https://huggingface.co/api/avatars/$it" },
                author = owner ?: heading.name, task = task,
                modifier = Modifier.modelIdentityTransition(transitionKey, avatar = true),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.spacing2)) {
                Text(
                    text = buildAnnotatedString {
                        owner?.let {
                            withStyle(SpanStyle(
                                color = AppTheme.colors.onSurfaceVariant,
                                fontSize = AppTheme.typography.bodyLarge.fontSize,
                                fontWeight = FontWeight.Normal,
                            )) {
                                append(it)
                                append("/")
                            }
                        }
                        if (modelCardUrl != null) {
                            withLink(LinkAnnotation.Url(
                                modelCardUrl,
                                styles = TextLinkStyles(style = SpanStyle(textDecoration = TextDecoration.None)),
                            )) { append(heading.name) }
                        } else {
                            append(heading.name)
                        }
                    },
                    style = AppTheme.typography.itemTitle, color = AppTheme.colors.onSurface,
                    modifier = Modifier.modelIdentityTransition(transitionKey).drawWithContent {
                        drawContent()
                        if (modelCardUrl != null) titleLayout?.let { layout ->
                            // Draw only under the visible model-name span, including wrapped lines.
                            for (line in 0 until layout.lineCount) {
                                val start = maxOf(nameStart, layout.getLineStart(line))
                                val end = layout.getLineEnd(line, visibleEnd = true)
                                if (start < end) {
                                    val bounds = layout.getPathForRange(start, end).getBounds()
                                    val y = minOf(layout.getLineBaseline(line) + 3.dp.toPx(), layout.getLineBottom(line) - 1.dp.toPx())
                                    drawLine(
                                        color = underlineColor,
                                        start = Offset(bounds.left, y), end = Offset(bounds.right, y),
                                        strokeWidth = 1.dp.toPx(), cap = StrokeCap.Round,
                                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(1.dp.toPx(), 3.dp.toPx())),
                                    )
                                }
                            }
                        }
                    }.onGloballyPositioned { coordinates ->
                        onTitleBottomChanged(coordinates.positionInRoot().y + coordinates.size.height)
                    }.semantics { semanticHeading() }.testTag("detail-title"),
                    onTextLayout = { titleLayout = it },
                )
            }
        }
        if (parameters != null || downloads != null || likes != null || fitStatus != null) FlowRow(
            horizontalArrangement = Arrangement.spacedBy(spacing.spacing12),
            verticalArrangement = Arrangement.spacedBy(spacing.spacing4),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            parameters?.let {
                ModelMetric(AppIcons.Compute, formatCompactMetric(it), "$it parameters")
            }
            downloads?.let {
                ModelMetric(AppIcons.Download, formatCompactMetric(it.toLong()), "$it downloads in the last month")
            }
            likes?.let {
                ModelMetric(AppIcons.Favorite, formatCompactMetric(it.toLong()), "$it likes")
            }
            fitStatus?.invoke()
        }
        listOfNotNull(
            task?.replace('-', ' ')?.replaceFirstChar { it.uppercase() },
            model.cardData?.license?.takeIf(String::isNotBlank),
        ).takeIf { it.isNotEmpty() }?.let {
            Text(it.joinToString(" · "), style = AppTheme.typography.labelBase, color = AppTheme.colors.onSurfaceVariant)
        }
        description?.takeIf(String::isNotBlank)?.let {
            Text(
                it, style = AppTheme.typography.bodySmall, color = AppTheme.colors.onSurface,
                modifier = Modifier.widthIn(max = 720.dp).testTag("detail-description"),
            )
        }
    }
}
