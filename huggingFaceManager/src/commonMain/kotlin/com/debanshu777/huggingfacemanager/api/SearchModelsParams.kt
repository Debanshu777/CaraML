package com.debanshu777.huggingfacemanager.api

/**
 * Search parameters for Hugging Face quicksearch API.
 * Supports text-based search with configurable result limit.
 *
 * @param query Search query string. Must not be blank.
 * @param limit Maximum number of results to return (default: 20, range: 1-100)
 */
data class SearchModelsParams(
    val query: String,
    val limit: Int = DEFAULT_LIMIT
) {
    init {
        require(query.isNotBlank()) {
            "query must not be blank"
        }
        require(query.length <= MAX_QUERY_LENGTH && query.none { it.isISOControl() }) {
            "query is invalid"
        }
        require(limit in 1..100) {
            "limit must be between 1 and 100"
        }
    }

    companion object {
        private const val DEFAULT_LIMIT = 20
        private const val MAX_QUERY_LENGTH = 256
    }
}
