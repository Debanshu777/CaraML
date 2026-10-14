package com.debanshu777.caraml.features.modelhub.presentation.search

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.debanshu777.huggingfacemanager.api.ModelPage
import com.debanshu777.huggingfacemanager.api.ModelPageCursor
import com.debanshu777.huggingfacemanager.api.ModelPageRequest
import com.debanshu777.huggingfacemanager.api.error.DataError
import com.debanshu777.huggingfacemanager.api.error.Result
import com.debanshu777.huggingfacemanager.model.ListModelsResponse

/** Paging owns cursor advancement; the request object still validates every external query. */
internal class ModelHubPagingSource(
    private val request: ModelPageRequest,
    private val fetch: suspend (ModelPageRequest) -> Result<ModelPage, DataError.Network>,
    private val onPage: (List<ListModelsResponse.Model>, ModelPageCursor?, Int?, Boolean) -> Unit,
    private val onLoadStart: (Boolean) -> Unit,
    private val validId: (String) -> Boolean,
) : PagingSource<ModelPageCursor, ListModelsResponse.Model>() {
    private val seenIds = mutableSetOf<String>()
    private val seenCursors = mutableSetOf<ModelPageCursor>()
    private var duplicateOnlyPages = 0
    private var loaded = 0

    override suspend fun load(params: LoadParams<ModelPageCursor>): LoadResult<ModelPageCursor, ListModelsResponse.Model> {
        if (loaded >= 256) return LoadResult.Page(emptyList(), null, null)
        val cursor = params.key
        if (cursor != null && !seenCursors.add(cursor)) {
            return LoadResult.Page(emptyList(), null, null)
        }
        val pageRequest = try {
            // Cursor provenance includes the page size, so it must stay fixed across pages.
            request.copy(cursor = cursor)
        } catch (error: IllegalArgumentException) {
            return LoadResult.Error(error)
        }
        onLoadStart(cursor != null)
        return when (val result = fetch(pageRequest)) {
            is Result.Error -> {
                if (cursor != null) seenCursors.remove(cursor)
                LoadResult.Error(ModelHubPageException(result.error))
            }

            is Result.Success -> {
                val rows = result.data.models.filter { row ->
                    row.id?.let { validId(it) && seenIds.add(it) } == true
                }.take(256 - loaded)
                loaded += rows.size
                duplicateOnlyPages = if (rows.isEmpty()) duplicateOnlyPages + 1 else 0
                val next = result.data.nextCursor
                val stalled =
                    next != null && (next == cursor || next in seenCursors || duplicateOnlyPages >= 2)
                val nextKey = next.takeUnless { loaded >= 256 || stalled }
                onPage(rows, nextKey, result.data.totalCount, stalled)
                LoadResult.Page(rows, null, nextKey)
            }
        }
    }

    override fun getRefreshKey(state: PagingState<ModelPageCursor, ListModelsResponse.Model>): ModelPageCursor? =
        null
}

internal class ModelHubPageException(val networkError: DataError.Network) : Exception()
