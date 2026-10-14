package com.debanshu777.huggingfacemanager.model

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
