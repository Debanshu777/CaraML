package com.debanshu777.caraml.features.chat.presentation.components

import com.debanshu777.caraml.core.ui.icons.AppIcons
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.theme.AppTheme
import com.debanshu777.caraml.core.theme.AuroraSurfaceLevel
import com.debanshu777.caraml.core.ui.components.CaraMLPane
import com.debanshu777.caraml.core.ui.components.BrandPal
import com.debanshu777.caraml.core.ui.components.BrandPalState
import com.debanshu777.caraml.core.ui.graphics.decodePngToImageBitmap
import com.debanshu777.caraml.core.ui.motion.LocalAuroraMotionPolicy
import com.debanshu777.caraml.features.chat.data.ChatMessage
import com.debanshu777.caraml.features.chat.data.MessageRole
import com.debanshu777.caraml.features.chat.data.MessageDelivery
import com.debanshu777.caraml.features.chat.presentation.components.providers.ChatMessagePreviewProvider
import com.mikepenz.markdown.m3.Markdown
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Preview
@Composable
private fun MessageBubblePreview(
    @PreviewParameter(ChatMessagePreviewProvider::class) message: ChatMessage
) {
    MaterialTheme {
        Surface {
            MessageBubble(message = message, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun MessageBubble(
    message: ChatMessage,
    showMediaPending: Boolean = false,
    isStreaming: Boolean = false,
    streamingThinking: String = "",
    imageGenStep: Int = 0,
    imageGenTotalSteps: Int = 0,
    imageGenRequestedSteps: Int = 0,
    imageGenElapsedSeconds: Int = 0,
    loadMedia: suspend (String) -> ByteArray? = { null },
    modifier: Modifier = Modifier
) {
    val isUser = message.role == MessageRole.User
    val alignment = if (isUser) Alignment.End else Alignment.Start
    val textColor = AppTheme.colors.onSurface

    // For assistant messages: prefer the live streamingThinking (only set on the
    // streaming bubble); otherwise fall back to the persisted value. Output is
    // always `message.text` — the streaming list passes the live text through.
    val thinkingText = remember(message.id, streamingThinking, message.thinking) {
        when {
            streamingThinking.isNotBlank() -> streamingThinking
            !message.thinking.isNullOrBlank() -> message.thinking
            else -> ""
        }
    }
    val output = message.text
    val mediaPhase = reportedMediaPhase(imageGenStep, imageGenTotalSteps)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = AppTheme.spacing.spacing12),
        horizontalAlignment = alignment
    ) {
        MessageIdentity(message, isStreaming, thinkingText, showMediaPending, mediaPhase)
        if (message.role == MessageRole.Assistant && !showMediaPending &&
            message.delivery != null && message.delivery != MessageDelivery.Complete
        ) {
            ReplyNotice(message)
        }
        if (!isUser && thinkingText.isNotEmpty()) {
            ThoughtsDisclosure(
                thinking = thinkingText,
            )
        }

        if (isUser) {
            if (message.text.isNotEmpty()) {
                Surface(
                    modifier = Modifier.padding(start = AppTheme.spacing.spacing32),
                    color = lerp(AppTheme.colors.surface, AppTheme.brandColors.lilac, 0.24f),
                    shape = RoundedCornerShape(topStart = 21.dp, topEnd = 21.dp, bottomEnd = 5.dp, bottomStart = 21.dp),
                ) {
                    Text(
                        modifier = Modifier.padding(horizontal = 17.dp, vertical = 15.dp),
                        text = message.text,
                        style = AppTheme.typography.conversationBody,
                        color = textColor,
                    )
                }
            }
        } else if (output.isNotEmpty()) {
            val outputModifier = Modifier
                .fillMaxWidth()
            if (isStreaming) {
                Text(
                    text = output,
                    modifier = outputModifier,
                    style = AppTheme.typography.conversationBody,
                    color = textColor,
                )
            } else {
                Markdown(
                    content = output,
                    modifier = outputModifier,
                    typography = chatMarkdownTypography(),
                )
            }
        }

        if (!isUser && showMediaPending) {
            // Three phases:
            //   1. Preparing — sampler hasn't reported any step yet (text encoding, latent prep)
            //   2. Sampling — sampler is reporting step/total
            //   3. Finalizing — sampler reached total but image hasn't decoded yet (rare)
            val isSampling = imageGenStep > 0
            val isFinalizing = mediaPhase == GenerationActivityPhase.Finalizing
            val elapsed = imageGenElapsedSeconds

            val statusText = when {
                isFinalizing -> "Finalizing local output · ${elapsed}s"
                isSampling && imageGenTotalSteps > 0 -> "Step $imageGenStep / $imageGenTotalSteps · ${elapsed}s"
                isSampling -> "Step $imageGenStep · ${elapsed}s"
                else         -> if (imageGenRequestedSteps > 0)
                                    "Preparing local generation · " +
                                        "$imageGenRequestedSteps planned steps · ${elapsed}s"
                                else "Preparing local generation · ${elapsed}s"
            }

            GenerationActivity(
                label = statusText,
                progress = if (isSampling && imageGenTotalSteps > 0) {
                    imageGenStep.toFloat() / imageGenTotalSteps
                } else {
                    null
                },
                phase = mediaPhase,
                showPhaseHeader = false,
                modifier = Modifier
                    .padding(top = AppTheme.spacing.spacing8)
                    .fillMaxWidth(),
            )
        }

        if (!isUser && (message.imagePath != null || message.imageBytes?.isNotEmpty() == true)) {
            val bitmap = rememberDecodedMediaBitmap(
                key = message.id,
                path = message.imagePath,
                inlineBytes = message.imageBytes,
                loadMedia = loadMedia,
            )
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = "Generated image",
                    modifier = Modifier
                        .padding(top = AppTheme.spacing.spacing8)
                        .heightIn(max = AppTheme.dimensions.size320)
                        .clip(AppTheme.shapes.medium),
                    contentScale = ContentScale.Fit
                )
            }
        }

        val frameSources = remember(message.videoFramePaths, message.videoFrames) {
            when {
                !message.videoFramePaths.isNullOrEmpty() ->
                    message.videoFramePaths.map { path -> path to null }
                !message.videoFrames.isNullOrEmpty() ->
                    message.videoFrames.map { bytes -> null to bytes }
                else -> emptyList()
            }
        }
        if (!isUser && frameSources.isNotEmpty()) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = AppTheme.spacing.spacing8),
                horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.spacing8)
            ) {
                itemsIndexed(frameSources, key = { index, _ -> "${message.id}_$index" }) {
                        index, (framePath, frameBytes) ->
                    val frameBitmap = rememberDecodedMediaBitmap(
                        key = "${message.id}_$index",
                        path = framePath,
                        inlineBytes = frameBytes,
                        loadMedia = loadMedia,
                    )
                    if (frameBitmap != null) {
                        Image(
                            bitmap = frameBitmap,
                            contentDescription = "Generated video frame ${index + 1}",
                            modifier = Modifier
                                .size(AppTheme.dimensions.size120)
                                .clip(AppTheme.shapes.small),
                            contentScale = ContentScale.Crop
                        )
                    }
                }
            }
        }

        if (!isUser && message.inferenceMetrics != null) {
            var detailsExpanded by rememberSaveable(message.id) { mutableStateOf(false) }
            androidx.compose.material3.TextButton(onClick = { detailsExpanded = !detailsExpanded }) {
                Text(if (detailsExpanded) "Hide details" else "Reply details", style = AppTheme.typography.labelBase)
            }
            AnimatedVisibility(visible = detailsExpanded) {
                val inferenceStatsColor = AppTheme.colors.onSurfaceVariant
                androidx.compose.foundation.layout.FlowRow(
                    modifier = Modifier.padding(top = AppTheme.spacing.spacing8),
                    horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.spacing12),
                    verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.spacing8),
                ) {

                    val tokensPerSec =
                        ((message.inferenceMetrics.tokensPerSecond * 100).toInt() / 100.0)
                    StatItem(
                        icon = AppIcons.Speed,
                        text = "$tokensPerSec tokens/s",
                        textColor = inferenceStatsColor,
                    )

                    StatItem(
                        icon = AppIcons.Memory,
                        text = "${message.inferenceMetrics.tokenCount} tokens",
                        textColor = inferenceStatsColor,
                    )

                    val timeSec = ((message.inferenceMetrics.generationTimeMs / 10.0).toInt() / 100.0)
                    StatItem(
                        icon = AppIcons.Clock,
                        text = "${timeSec}s",
                        textColor = inferenceStatsColor,
                    )
                }
            }
        }
    }
}

internal fun reportedMediaPhase(step: Int, totalSteps: Int): GenerationActivityPhase = when {
    totalSteps > 0 && step >= totalSteps -> GenerationActivityPhase.Finalizing
    step > 0 -> GenerationActivityPhase.Generating
    else -> GenerationActivityPhase.Preparing
}

internal fun messageCharacterState(
    message: ChatMessage,
    isStreaming: Boolean,
    thinking: String,
    mediaPending: Boolean,
    mediaPhase: GenerationActivityPhase = GenerationActivityPhase.Preparing,
): BrandPalState = when {
    isStreaming && mediaPending -> when (mediaPhase) {
        GenerationActivityPhase.Generating -> BrandPalState.Replying
        GenerationActivityPhase.Finalizing -> BrandPalState.Thinking
        else -> BrandPalState.Loading
    }
    isStreaming && message.text.isNotEmpty() -> BrandPalState.Replying
    isStreaming && thinking.isNotEmpty() -> BrandPalState.Thinking
    isStreaming -> BrandPalState.Loading
    message.delivery == MessageDelivery.Error -> BrandPalState.Error
    message.delivery in setOf(MessageDelivery.Stopped, MessageDelivery.TokenLimit, MessageDelivery.ContextLimit) -> BrandPalState.Paused
    message.delivery == MessageDelivery.NoAnswer -> BrandPalState.Error
    message.delivery == MessageDelivery.Complete -> BrandPalState.Success
    else -> BrandPalState.Idle
}

@Composable
private fun ReplyNotice(message: ChatMessage) {
    val hasAnswer = message.text.isNotBlank()
    val title = when (message.delivery) {
        MessageDelivery.TokenLimit -> if (hasAnswer) "Reply paused at the length limit" else "Length limit reached before an answer"
        MessageDelivery.ContextLimit -> if (hasAnswer) "Reply paused: context is full" else "Context filled before an answer"
        MessageDelivery.NoAnswer -> "The model finished without an answer"
        MessageDelivery.Stopped -> "Reply stopped"
        else -> "Couldn’t finish this reply"
    }
    val note = when (message.delivery) {
        MessageDelivery.TokenLimit -> "Continue the reply, or try a shorter request."
        MessageDelivery.ContextLimit -> "Continue in a refreshed context, or try another model."
        MessageDelivery.NoAnswer -> "Try again or choose another model. Thoughts are kept below."
        MessageDelivery.Stopped -> "Your partial reply is saved. Continue whenever you’re ready."
        else -> "Your message is still here. Try again."
    }
    Column(
        Modifier.fillMaxWidth().padding(vertical = AppTheme.spacing.spacing12)
            .semantics(mergeDescendants = true) {
                // These outcomes replace the identity's live status with this notice.
                if (message.delivery in setOf(MessageDelivery.TokenLimit, MessageDelivery.ContextLimit, MessageDelivery.NoAnswer)) {
                    liveRegion = LiveRegionMode.Polite
                }
            },
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.spacing4),
    ) {
        Text(title, style = AppTheme.typography.labelLarge, color = AppTheme.colors.onSurface)
        Text(note, style = AppTheme.typography.bodySmall, color = AppTheme.colors.onSurfaceVariant)
    }
}

@Composable
private fun MessageIdentity(
    message: ChatMessage,
    isStreaming: Boolean,
    thinking: String,
    mediaPending: Boolean,
    mediaPhase: GenerationActivityPhase,
) {
    val isAssistant = message.role == MessageRole.Assistant
    val state = messageCharacterState(message, isStreaming, thinking, mediaPending, mediaPhase)
    Row(
        modifier = Modifier.then(if (isAssistant) Modifier.fillMaxWidth() else Modifier).padding(bottom = AppTheme.spacing.spacing8),
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.spacing8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isAssistant) BrandPal(state, Modifier.size(32.dp))
        Text(
            modifier = if (isAssistant) Modifier.weight(1f) else Modifier,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            text = when (message.role) {
                MessageRole.User -> "You"
                MessageRole.Assistant -> "CaraML"
                MessageRole.System -> "Notice"
            },
            style = AppTheme.typography.labelLarge,
            color = AppTheme.colors.onSurface,
        )
        val status = if (isStreaming && mediaPending) {
            when (mediaPhase) {
                GenerationActivityPhase.Generating -> "Generating"
                GenerationActivityPhase.Finalizing -> "Finalizing"
                else -> "Getting ready"
            }
        } else when {
            message.delivery in setOf(MessageDelivery.TokenLimit, MessageDelivery.ContextLimit, MessageDelivery.NoAnswer) -> null
            else -> when (state) {
                BrandPalState.Loading -> "Getting ready"
                BrandPalState.Thinking -> "Thinking"
                BrandPalState.Replying -> "Writing…"
                BrandPalState.Success -> "Done"
                BrandPalState.Error -> "Couldn’t reply"
                BrandPalState.Paused -> "Stopped"
                BrandPalState.Idle -> null
            }
        }
        if (isAssistant && status != null) {
            Text(
                text = status,
                style = AppTheme.typography.labelSmall,
                color = if (state == BrandPalState.Error) AppTheme.colors.error else AppTheme.colors.onSurface,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

@Composable
private fun rememberDecodedMediaBitmap(
    key: String,
    path: String?,
    inlineBytes: ByteArray?,
    loadMedia: suspend (String) -> ByteArray?,
): ImageBitmap? {
    val bitmap by produceState<ImageBitmap?>(
        initialValue = null,
        key1 = key,
        key2 = path,
        key3 = inlineBytes,
    ) {
        val encoded = when {
            path != null -> loadMedia(path)
            inlineBytes?.isNotEmpty() == true -> inlineBytes
            else -> null
        }
        value = encoded?.let { bytes ->
            withContext(Dispatchers.Default) { decodePngToImageBitmap(bytes) }
        }
    }
    return bitmap
}

/**
 * Collapsible "Thoughts" panel that surfaces a model's `<think>...</think>` block.
 *
 * Starts collapsed so live reasoning cannot overwhelm the reply activity. The user's
 * explicit expansion choice is retained for the lifetime of this message.
 */
@Composable
private fun ThoughtsDisclosure(
    thinking: String,
    modifier: Modifier = Modifier,
) {
    val motion = LocalAuroraMotionPolicy.current
    var override by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val expanded = override ?: false

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = AppTheme.spacing.spacing8)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = AppTheme.spacing.spacing48)
                .clip(AppTheme.shapes.small)
                .clickable(
                    onClickLabel = if (expanded) "Collapse thoughts" else "Expand thoughts",
                    role = Role.Button,
                    onClick = { override = !expanded },
                )
                .semantics {
                    stateDescription = if (expanded) "Expanded" else "Collapsed"
                }
                .padding(horizontal = AppTheme.spacing.spacing8, vertical = AppTheme.spacing.spacing4),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.spacing8),
        ) {
            Icon(
                imageVector = AppIcons.Compute,
                contentDescription = null,
                modifier = Modifier.size(AppTheme.dimensions.size14),
                tint = AppTheme.colors.onSurfaceVariant,
            )
            Text(
                text = "Thoughts",
                style = AppTheme.typography.labelBase,
                color = AppTheme.colors.onSurfaceVariant,
                modifier = Modifier.weight(1f, fill = false),
            )
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else AppIcons.ChevronDown,
                contentDescription = null,
                modifier = Modifier.size(AppTheme.spacing.spacing16),
                tint = AppTheme.colors.onSurfaceVariant,
            )
        }

        AnimatedVisibility(
            visible = expanded && thinking.isNotEmpty(),
            enter = if (motion.spatialTransitionsEnabled) {
                expandVertically(animationSpec = tween(motion.peerTransitionMillis)) +
                    fadeIn(animationSpec = tween(motion.opacityDurationMillis))
            } else {
                fadeIn(animationSpec = tween(motion.opacityDurationMillis))
            },
            exit = if (motion.spatialTransitionsEnabled) {
                shrinkVertically(animationSpec = tween(motion.exitMillis)) +
                    fadeOut(animationSpec = tween(motion.exitMillis))
            } else {
                fadeOut(animationSpec = tween(motion.opacityDurationMillis))
            },
        ) {
            Text(
                text = thinking,
                style = AppTheme.typography.bodySmallItalic,
                color = AppTheme.colors.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = AppTheme.spacing.spacing4)
                    .clip(AppTheme.shapes.medium)
                    .background(AppTheme.colors.surfaceContainerLow)
                    .padding(AppTheme.spacing.spacing12),
            )
        }
    }
}

@Composable
private fun StatItem(
    icon: ImageVector,
    text: String,
    textColor: Color,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.spacing2),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = textColor,
            modifier = Modifier.size(AppTheme.spacing.spacing12)
        )
        Text(
            text = text,
            style = AppTheme.typography.labelSmall,
            color = textColor,
        )
    }
}
