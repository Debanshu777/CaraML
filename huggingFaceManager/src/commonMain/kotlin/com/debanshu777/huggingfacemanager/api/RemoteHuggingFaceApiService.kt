package com.debanshu777.huggingfacemanager.api

import com.debanshu777.huggingfacemanager.api.error.DataError
import com.debanshu777.huggingfacemanager.api.error.Result
import com.debanshu777.huggingfacemanager.model.ListModelsResponse
import com.debanshu777.huggingfacemanager.model.ModelDetailResponse
import com.debanshu777.huggingfacemanager.model.ModelFileTreeResponse
import com.debanshu777.huggingfacemanager.model.SearchModelsResponse
import com.debanshu777.huggingfacemanager.model.TransformerConfigResponse
import io.ktor.client.HttpClient
import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.appendPathSegments
import kotlinx.serialization.json.Json

private const val MAX_TREE_PAGES = 64
private const val MAX_TREE_ENTRIES = 4_096
private const val MAX_CURSOR_LENGTH = 2_048
private const val MAX_LINK_HEADER_LENGTH = 4_096
private const val CONFIG_RESPONSE_LIMIT_BYTES = 1L * 1024L * 1024L

internal const val RECOMMENDATION_METADATA_SCHEMA_VERSION = 1
internal val IMMUTABLE_REVISION_PATTERN = Regex("^[0-9a-fA-F]{40,64}$")

internal fun isImmutableRevision(revision: String): Boolean =
    IMMUTABLE_REVISION_PATTERN.matches(revision)

class RemoteHuggingFaceApiService private constructor(
    client: HttpClient,
    json: Json,
    private val trustedOrigin: Url,
) {
    constructor(
        client: HttpClient,
        json: Json,
        baseUrl: String,
    ) : this(client, json, validateHubOrigin(baseUrl, allowTestLoopback = false))

    internal constructor(
        client: HttpClient,
        json: Json,
        baseUrl: String,
        allowTestLoopback: Boolean,
    ) : this(client, json, validateHubOrigin(baseUrl, allowTestLoopback))

    private val clientWrapper = ClientWrapper(client, json)
    private val strictJson = Json(from = json) {
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
    }

    suspend fun listModels(params: ListModelsParams): Result<ListModelsResponse, DataError.Network> {
        return clientWrapper.networkGetUsecase(endpoint = listModelsUrl(params).toString())
    }

    suspend fun listRecommendationModels(
        params: ListModelsParams,
    ): Result<ListModelsResponse, DataError.Network> = clientWrapper.networkGetUsecase(
        endpoint = listModelsUrl(params).toString(),
        decode = { body -> RecommendationMetadataV1.decodeList(strictJson, body) },
    )

    private fun listModelsUrl(params: ListModelsParams): Url = URLBuilder(trustedOrigin).apply {
        appendPathSegments("models-json")
        parameters.apply {
            append(
                "num_parameters",
                "min:${params.minParams.apiValue},max:${params.maxParams.apiValue}",
            )
            append("library", params.library.joinToString(","))
            append("apps", params.apps.joinToString(","))
            append("sort", params.sort.apiValue)
            append("withCount", params.withCount.toString())
            append("p", params.page.toString())
        }
    }.build()

    suspend fun searchModels(params: SearchModelsParams): Result<SearchModelsResponse, DataError.Network> {
        val url = URLBuilder(trustedOrigin).apply {
            appendPathSegments("api", "quicksearch")
            parameters.apply {
                append("q", params.query)
                append("type", "model")
                append("limit", params.limit.toString())
            }
        }.build()

        return clientWrapper.networkGetUsecase(endpoint = url.toString())
    }

    suspend fun getModelDetail(modelId: String): Result<ModelDetailResponse, DataError.Network> {
        val url = modelDetailUrl(modelId) ?: return Result.Error(DataError.Network.Unknown)

        return clientWrapper.networkGetUsecase(endpoint = url.toString())
    }

    suspend fun getRecommendationModelDetail(
        modelId: String,
    ): Result<ModelDetailResponse, DataError.Network> {
        val url = recommendationModelDetailUrl(modelId) ?: return Result.Error(DataError.Network.Unknown)

        return clientWrapper.networkGetUsecase(
            endpoint = url.toString(),
            decode = { body -> RecommendationMetadataV1.decodeDetail(strictJson, body) },
        )
    }

    private fun modelDetailUrl(modelId: String): Url? {
        val segments = validatedModelSegments(modelId) ?: return null
        return URLBuilder(trustedOrigin).apply {
            appendPathSegments("api", "models")
            appendPathSegments(segments, encodeSlash = true)
        }.build()
    }

    private fun recommendationModelDetailUrl(modelId: String): Url? {
        val url = modelDetailUrl(modelId) ?: return null
        return URLBuilder(url).apply {
            RECOMMENDATION_DETAIL_EXPANSIONS.forEach { parameters.append("expand", it) }
        }.build()
    }

    suspend fun getModelConfig(
        modelId: String,
        revision: String,
    ): Result<TransformerConfigResponse, DataError.Network> {
        val segments = validatedModelSegments(modelId)
            ?: return Result.Error(DataError.Network.Unknown)
        if (!isImmutableRevision(revision)) return Result.Error(DataError.Network.Unknown)
        val url = URLBuilder(trustedOrigin).apply {
            appendPathSegments(segments, encodeSlash = true)
            appendPathSegments("resolve", revision, "config.json")
        }.build()
        return clientWrapper.networkGetUsecase(
            endpoint = url.toString(),
            maxResponseBytes = CONFIG_RESPONSE_LIMIT_BYTES,
            decode = { body -> strictJson.decodeFromString<TransformerConfigResponse>(body) },
        )
    }

    suspend fun getModelFileTree(
        modelId: String,
        revision: String,
    ): Result<List<ModelFileTreeResponse>, DataError.Network> {
        val segments = validatedModelSegments(modelId)
            ?: return Result.Error(DataError.Network.Unknown)
        if (!isImmutableRevision(revision)) return Result.Error(DataError.Network.Unknown)

        val expectedPath = treeUrl(segments, revision, cursor = null).encodedPath
        val entries = ArrayList<ModelFileTreeResponse>()
        val seenPaths = HashSet<String>()
        val seenCursors = HashSet<String>()
        var cursor: String? = null

        repeat(MAX_TREE_PAGES) { pageIndex ->
            val pageUrl = treeUrl(segments, revision, cursor)
            when (
                val page = clientWrapper.networkGetBounded(
                    endpoint = pageUrl.toString(),
                    decode = { body ->
                        strictJson.decodeFromString(
                            BoundedListSerializer(
                                ModelFileTreeResponse.serializer(),
                                MAX_TREE_ENTRIES - entries.size,
                            ),
                            body,
                        )
                    },
                )
            ) {
                is Result.Error -> return Result.Error(page.error)
                is Result.Success -> {
                    val pageEntries = page.data.data
                    if (pageEntries.size > MAX_TREE_ENTRIES - entries.size) {
                        return Result.Error(DataError.Network.PayloadTooLarge)
                    }
                    for (entry in pageEntries) {
                        val path = entry.path
                            ?: return Result.Error(DataError.Network.Serialization)
                        if (!isValidRelativeModelPath(path)) {
                            return Result.Error(DataError.Network.Serialization)
                        }
                        if (!seenPaths.add(path)) {
                            return Result.Error(DataError.Network.Serialization)
                        }
                    }
                    entries += pageEntries

                    val link = page.data.linkHeader ?: return Result.Success(entries.toList())
                    if (entries.size == MAX_TREE_ENTRIES) {
                        return Result.Error(DataError.Network.PayloadTooLarge)
                    }
                    if (pageIndex == MAX_TREE_PAGES - 1) {
                        return Result.Error(DataError.Network.PayloadTooLarge)
                    }
                    val nextCursor = extractTrustedCursor(link, expectedPath)
                        ?: return Result.Error(DataError.Network.Serialization)
                    if (!seenCursors.add(nextCursor)) {
                        return Result.Error(DataError.Network.Serialization)
                    }
                    cursor = nextCursor
                }
            }
        }
        return Result.Error(DataError.Network.PayloadTooLarge)
    }

    private fun treeUrl(
        modelSegments: List<String>,
        revision: String,
        cursor: String?,
    ): Url = URLBuilder(trustedOrigin).apply {
        appendPathSegments("api", "models")
        appendPathSegments(modelSegments, encodeSlash = true)
        appendPathSegments("tree", revision)
        parameters.append("recursive", "true")
        cursor?.let { parameters.append("cursor", it) }
    }.build()

    private fun extractTrustedCursor(linkHeader: String, expectedPath: String): String? {
        if (linkHeader.isEmpty() || linkHeader.length > MAX_LINK_HEADER_LENGTH ||
            linkHeader.any { it.code < 32 && it != '\t' }
        ) {
            return null
        }
        val target = NEXT_LINK.matchEntire(linkHeader.trim())?.groupValues?.get(1) ?: return null
        val link = try {
            Url(target)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (link.protocol != trustedOrigin.protocol ||
            !link.host.equals(trustedOrigin.host, ignoreCase = true) ||
            link.port != trustedOrigin.port ||
            link.user != null || link.password != null || link.fragment.isNotEmpty() ||
            link.encodedPath != expectedPath
        ) {
            return null
        }
        val cursors = link.parameters.getAll("cursor") ?: return null
        if (cursors.size != 1) return null
        return cursors.single().takeIf { value ->
            value.isNotEmpty() && value.length <= MAX_CURSOR_LENGTH &&
                value.none { it.code < 32 || it.code == 127 }
        }
    }

    private fun validatedModelSegments(modelId: String): List<String>? {
        if (modelId.isEmpty() || modelId != modelId.trim() || modelId.length > MAX_MODEL_ID_LENGTH ||
            '\\' in modelId
        ) {
            return null
        }
        return modelId.split('/').takeIf { segments ->
            segments.size in 1..2 && segments.all(::isSafeRepositorySegment)
        }
    }

    private fun isValidRelativeModelPath(path: String): Boolean {
        if (path.isEmpty() || path != path.trim() || path.length > MAX_RELATIVE_PATH_LENGTH ||
            path.startsWith('/') || path.startsWith('\\') || '\\' in path
        ) {
            return false
        }
        return path.split('/').all { segment ->
            segment.isNotEmpty() && segment != "." && segment != ".." &&
                segment.length <= MAX_PATH_SEGMENT_LENGTH &&
                segment.none { it.code < 32 || it.code == 127 || it in ":*?\"<>|" }
        }
    }

    private fun isSafeRepositorySegment(segment: String): Boolean =
        segment.isNotEmpty() && segment.length <= MAX_REPOSITORY_SEGMENT_LENGTH &&
            segment != "." && segment != ".." && ".." !in segment && "--" !in segment &&
            segment.first() != '.' && segment.first() != '-' &&
            segment.last() != '.' && segment.last() != '-' &&
            segment.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }

    private companion object {
        val RECOMMENDATION_DETAIL_EXPANSIONS = listOf("library_name", "pipeline_tag", "sha", "tags")
        val NEXT_LINK = Regex("^<([^<>]+)>;\\s*rel=\\\"?next\\\"?$", RegexOption.IGNORE_CASE)
        const val MAX_MODEL_ID_LENGTH = 193
        const val MAX_REPOSITORY_SEGMENT_LENGTH = 96
        const val MAX_RELATIVE_PATH_LENGTH = 1_024
        const val MAX_PATH_SEGMENT_LENGTH = 255
    }
}

private fun validateHubOrigin(baseUrl: String, allowTestLoopback: Boolean): Url {
    val origin = try {
        Url(baseUrl)
    } catch (_: IllegalArgumentException) {
        throw IllegalArgumentException("Invalid Hugging Face origin")
    }
    val emptyOriginPath = origin.encodedPath.isEmpty() || origin.encodedPath == "/"
    val cleanOrigin = emptyOriginPath && origin.parameters.isEmpty() && origin.fragment.isEmpty() &&
        origin.user == null && origin.password == null
    val productionOrigin = origin.protocol == URLProtocol.HTTPS &&
        origin.host.equals("huggingface.co", ignoreCase = true) && origin.port == 443
    val loopbackOrigin = allowTestLoopback &&
        (origin.host == "127.0.0.1" || origin.host == "localhost" || origin.host == "::1") &&
        (origin.protocol == URLProtocol.HTTP || origin.protocol == URLProtocol.HTTPS)
    require(cleanOrigin && (productionOrigin || loopbackOrigin)) {
        "Untrusted Hugging Face origin"
    }
    return origin
}
