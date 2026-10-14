package com.debanshu777.huggingfacemanager.api

import com.debanshu777.huggingfacemanager.model.ListModelsResponse
import com.debanshu777.huggingfacemanager.model.ModelSort
import com.debanshu777.huggingfacemanager.model.ParameterRange

/** A continuation token is tied to the exact query that produced it. */
class ModelPageCursor internal constructor(
    internal val token: String,
    internal val queryKey: String,
) {
    override fun equals(other: Any?): Boolean =
        other is ModelPageCursor && token == other.token && queryKey == other.queryKey

    override fun hashCode(): Int = 31 * token.hashCode() + queryKey.hashCode()
}

data class ModelPageRequest(
    val search: String? = null,
    val sort: ModelSort = ModelSort.TRENDING,
    val minParams: ParameterRange? = null,
    val maxParams: ParameterRange? = null,
    val filter: List<String> = listOf("gguf"),
    val apps: List<String> = listOf("llama.cpp"),
    val limit: Int = 24,
    val cursor: ModelPageCursor? = null,
) {
    init {
        require(search == null || (search.isNotBlank() && search.length <= 128 &&
            search.none { it.code < 32 || it.code == 127 })) { "Invalid model search" }
        require(sort in SUPPORTED_SORTS) { "Unsupported model sort" }
        require(minParams == null || maxParams == null || minParams.ordinal <= maxParams.ordinal) {
            "Invalid parameter range"
        }
        require(limit in 1..100) { "Invalid page size" }
        require(filter.size <= 4 && filter.all(::safeFilter)) { "Invalid model filter" }
        require(apps.size <= 4 && apps.all(::safeFilter)) { "Invalid model app filter" }
    }

    private companion object {
        val SUPPORTED_SORTS = setOf(
            ModelSort.TRENDING, ModelSort.DOWNLOADS, ModelSort.LIKES,
            ModelSort.CREATED, ModelSort.MODIFIED,
        )

        fun safeFilter(value: String): Boolean = value.isNotEmpty() && value.length <= 64 &&
            value.all { it.isLetterOrDigit() || it in "_-.:/" }
    }
}

data class ModelPage(
    val models: List<ListModelsResponse.Model>,
    val nextCursor: ModelPageCursor?,
    /** The public list endpoint does not currently provide a verified catalog total. */
    val totalCount: Int? = null,
)
