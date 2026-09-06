package com.debanshu777.caraml.features.modelhub.presentation.search

import com.debanshu777.huggingfacemanager.model.ModelSort

sealed interface ModelOrdering {
    data object Personalized : ModelOrdering
    data class Server(val value: ModelSort) : ModelOrdering
}

internal fun ModelOrdering.serverSortOrNull(): ModelSort? = (this as? ModelOrdering.Server)?.value
