package com.debanshu777.caraml.sdk.discovery.huggingface.model

enum class ModelSort(val apiValue: String) {
    TRENDING("trending"),
    LIKES("likes"),
    DOWNLOADS("downloads"),
    CREATED("created"),
    MODIFIED("modified"),
    MOST_PARAMS("most_params"),
    LEAST_PARAMS("least_params"),
    SIMILAR("similar")
}
