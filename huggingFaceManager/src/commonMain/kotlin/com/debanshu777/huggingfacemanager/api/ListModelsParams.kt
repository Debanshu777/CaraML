package com.debanshu777.huggingfacemanager.api

import com.debanshu777.huggingfacemanager.model.ModelSort
import com.debanshu777.huggingfacemanager.model.ParameterRange

/**
 * Validated listing parameters for Hugging Face models API.
 * Supports filters (parameter range, library), sorting, and pagination.
 *
 * @param minParams Minimum parameter range (default: ZERO)
 * @param maxParams Maximum parameter range (default: SIX_B). Must be >= minParams.
 * @param library Library filters (default: gguf, onnx)
 * @param sort Sort order (default: TRENDING)
 * @param page Page index, 0-based (default: 0)
 * @param withCount Include total count in response (default: true)
 */
data class ListModelsParams(
    val minParams: ParameterRange = ParameterRange.ZERO,
    val maxParams: ParameterRange = ParameterRange.SIX_B,
    val library: List<String> = DEFAULT_LIBRARY,
    val apps: List<String> = DEFAULT_APP,
    val sort: ModelSort = ModelSort.TRENDING,
    val page: Int = 0,
    val withCount: Boolean = true
) {
    init {
        require(minParams.ordinal <= maxParams.ordinal) {
            "minParams cannot be greater than maxParams"
        }
        require(page >= 0) {
            "page must be non-negative"
        }
        require(page <= MAX_PAGE) {
            "page is too large"
        }
        require(library.size <= MAX_FILTERS && library.all(::isSafeFilter)) {
            "library filters are invalid"
        }
        require(apps.size <= MAX_FILTERS && apps.all(::isSafeFilter)) {
            "app filters are invalid"
        }
    }

    companion object {
        private val DEFAULT_LIBRARY = listOf("gguf")
        private val DEFAULT_APP = listOf("llama.cpp")
        private const val MAX_PAGE = 10_000
        private const val MAX_FILTERS = 16

        private fun isSafeFilter(value: String): Boolean =
            value.isNotEmpty() && value.length <= 64 && value.all { char ->
                char.isLetterOrDigit() || char == '_' || char == '-' || char == '.'
            }
    }
}
