package com.debanshu777.caraml.sdk.discovery.huggingface.api

import com.debanshu777.caraml.sdk.discovery.huggingface.model.ListModelsResponse
import com.debanshu777.caraml.sdk.discovery.huggingface.model.ModelSort
import com.debanshu777.caraml.sdk.discovery.huggingface.model.ParameterRange

/** A server page continuation is tied to the exact query that produced it. */
class ModelPageCursor internal constructor(
    internal val pageIndex: Int,
    internal val queryKey: String,
    internal val expectedPageSize: Int = ModelPageRequest.DEFAULT_PAGE_SIZE,
) {
    override fun equals(other: Any?): Boolean =
        other is ModelPageCursor && pageIndex == other.pageIndex && queryKey == other.queryKey &&
            expectedPageSize == other.expectedPageSize

    override fun hashCode(): Int = 31 * (31 * pageIndex.hashCode() + queryKey.hashCode()) + expectedPageSize
}

data class ModelPageRequest(
    val search: String? = null,
    val sort: ModelSort = ModelSort.TRENDING,
    val minParams: ParameterRange? = null,
    val maxParams: ParameterRange? = null,
    val filter: List<String> = listOf("gguf"),
    val apps: List<String> = listOf("llama.cpp"),
    val cursor: ModelPageCursor? = null,
    val pipelineTag: String? = null,
) {
    init {
        require(search == null || (search.isNotBlank() && search.length <= 128 &&
            search.none { it.code < 32 || it.code == 127 })) { "Invalid model search" }
        require(sort in SUPPORTED_SORTS) { "Unsupported model sort" }
        require(minParams == null || maxParams == null || minParams.ordinal <= maxParams.ordinal) {
            "Invalid parameter range"
        }
        require(filter.size <= 4 && filter.all(::safeFilter)) { "Invalid model filter" }
        require(apps.size <= 4 && apps.all(::safeFilter)) { "Invalid model app filter" }
        require(pipelineTag == null || safeFilter(pipelineTag)) { "Invalid model pipeline" }
    }

    /** Initial paging hint; the response owns the actual page size. */
    val pageSize: Int get() = DEFAULT_PAGE_SIZE

    companion object {
        const val DEFAULT_PAGE_SIZE = 30
        private val SUPPORTED_SORTS = setOf(
            ModelSort.TRENDING, ModelSort.DOWNLOADS, ModelSort.LIKES,
            ModelSort.CREATED, ModelSort.MODIFIED,
        )

        private fun safeFilter(value: String): Boolean = value.isNotEmpty() && value.length <= 64 &&
            value.all { it.isLetterOrDigit() || it in "_-.:/" }
    }
}

data class ModelPage(
    val models: List<ListModelsResponse.Model>,
    val nextCursor: ModelPageCursor?,
    /** Server matches, independent of locally loaded or assessed candidates. */
    val totalCount: Int? = null,
    val pageIndex: Int = 0,
    val pageSize: Int = ModelPageRequest.DEFAULT_PAGE_SIZE,
)
