package com.debanshu777.caraml.features.modelhub.presentation.details

import com.debanshu777.caraml.sdk.discovery.huggingface.api.error.DataError
import com.debanshu777.caraml.sdk.discovery.huggingface.model.ModelCardDocument
import com.debanshu777.caraml.features.modelhub.presentation.details.components.modelCardDisplayMarkdown
import com.mikepenz.markdown.model.State as MarkdownRenderState
import com.mikepenz.markdown.model.parseMarkdown

sealed interface ModelCardUiState {
    data object Loading : ModelCardUiState
    data object Missing : ModelCardUiState
    data class Ready(val document: ModelCardDocument, val rendered: MarkdownRenderState) : ModelCardUiState
    data class Failed(val error: DataError.Network) : ModelCardUiState
}

/** CPU work performed once by the request owner, never from a pager-page composition. */
internal fun prepareModelCard(document: ModelCardDocument): ModelCardUiState.Ready =
    ModelCardUiState.Ready(document, parseMarkdown(modelCardDisplayMarkdown(document.markdown)))
