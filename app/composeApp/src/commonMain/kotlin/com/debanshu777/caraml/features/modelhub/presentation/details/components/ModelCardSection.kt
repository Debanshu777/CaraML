package com.debanshu777.caraml.features.modelhub.presentation.details.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import com.debanshu777.caraml.core.theme.AppTheme
import com.debanshu777.caraml.features.modelhub.presentation.details.ModelCardUiState
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelHubStateKind
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelHubStateView
import com.debanshu777.caraml.sdk.discovery.huggingface.api.error.DataError
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownTable
import com.mikepenz.markdown.compose.elements.MarkdownTableHeader
import com.mikepenz.markdown.compose.elements.MarkdownTableRow
import com.mikepenz.markdown.compose.LocalReferenceLinkHandler
import com.mikepenz.markdown.utils.resolveImageAlt
import com.mikepenz.markdown.utils.resolveImageLink
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.State as MarkdownRenderState

@Composable
internal fun ModelCardSection(state: ModelCardUiState, onRetry: () -> Unit, modifier: Modifier = Modifier, sourceUrl: String? = null) {
    val browser = LocalUriHandler.current
    Column(modifier.testTag("detail-model-card")) {
        when (state) {
            ModelCardUiState.Loading -> ModelHubStateView(ModelHubStateKind.Loading, "Loading model card")
            ModelCardUiState.Missing -> Text("No README.md available for this model.",
                style = AppTheme.typography.bodySmall, color = AppTheme.colors.onSurfaceVariant)
            is ModelCardUiState.Failed -> {
                val retryable = state.error != DataError.Network.PayloadTooLarge && state.error != DataError.Network.Unauthorized
                ModelHubStateView(
                    kind = ModelHubStateKind.Error,
                    title = "Model card unavailable",
                    message = when (state.error) {
                        DataError.Network.PayloadTooLarge -> "This model card is too large to display here."
                        DataError.Network.Unauthorized -> "This model card requires access on Hugging Face."
                        else -> "Couldn’t load the model card."
                    },
                    actionLabel = if (retryable) "Retry" else sourceUrl?.let { "Open on Hugging Face" },
                    onAction = if (retryable) onRetry else sourceUrl?.let { url -> ({ browser.openUri(url) }) },
                )
            }
            is ModelCardUiState.Ready -> {
                val document = state.document
                val platformUriHandler = LocalUriHandler.current
                val uriHandler = remember(document, platformUriHandler) {
                    object : UriHandler {
                        override fun openUri(uri: String) {
                            document.resolveCardUrl(uri)?.let(platformUriHandler::openUri)
                        }
                    }
                }
                val images = remember(document) { ModelCardImageTransformer(document) }
                val content = (state.rendered as? MarkdownRenderState.Success)?.content.orEmpty()
                val t = AppTheme.typography
                CompositionLocalProvider(LocalUriHandler provides uriHandler) {
                    SelectionContainer {
                        Markdown(
                            state = state.rendered,
                            modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth().testTag("model-card-markdown"),
                            imageTransformer = images,
                            typography = markdownTypography(
                                h1 = t.heading24, h2 = t.itemTitle, h3 = t.heading16,
                                h4 = t.heading16, h5 = t.heading16, h6 = t.heading16,
                                text = t.bodyBase, paragraph = t.bodyBase, list = t.bodyBase,
                                ordered = t.bodyBase, bullet = t.bodyBase,
                                code = t.bodyLargeCode, inlineCode = t.bodyLargeCode, quote = t.bodyLargeQuote,
                            ),
                            components = markdownComponents(
                                image = { model ->
                                    val link = model.node.resolveImageLink(model.content, LocalReferenceLinkHandler.current)
                                    if (link != null) ModelCardImage(link, model.node.resolveImageAlt(model.content), images)
                                },
                                inlineImage = { model ->
                                    ModelCardImage(model.content, model.node.resolveImageAlt(content), images)
                                },
                                table = { model ->
                                    MarkdownTable(
                                        model.content, model.node, model.typography.table,
                                        headerBlock = { content, header, width, style ->
                                            MarkdownTableHeader(content, header, width, style, maxLines = Int.MAX_VALUE)
                                        },
                                        rowBlock = { content, row, width, style ->
                                            MarkdownTableRow(content, row, width, style, maxLines = Int.MAX_VALUE)
                                        },
                                    )
                                },
                            ),
                        )
                    }
                }
            }
        }
    }
}
