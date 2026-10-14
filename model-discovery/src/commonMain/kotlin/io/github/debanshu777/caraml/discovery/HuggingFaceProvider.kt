package io.github.debanshu777.caraml.discovery

import io.github.debanshu777.caraml.core.api.ArtifactDescriptor
import io.github.debanshu777.caraml.core.api.ArtifactRef
import io.github.debanshu777.caraml.core.api.ArtifactSource
import io.github.debanshu777.caraml.core.api.ArtifactTransfer
import io.github.debanshu777.caraml.core.api.ExperimentalCaraMLApi
import io.github.debanshu777.caraml.core.api.ModelCandidate
import io.github.debanshu777.caraml.core.api.ModelPage
import io.github.debanshu777.caraml.core.api.ModelProvider
import io.github.debanshu777.caraml.core.api.ProviderId
import io.github.debanshu777.caraml.core.api.SuspendCloseable
import io.ktor.client.HttpClient
import io.ktor.client.plugins.api.SendingRequest
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.util.AttributeKey
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.plugins.pluginOrNull
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.appendPathSegments
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.cancel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

private val signedTransfer = AttributeKey<Boolean>("CaraMLSignedTransfer")
private val originAuthorization = createClientPlugin("CaraMLOriginAuthorization") {
    // Runs for each actual send, after inherited DefaultRequest/Auth plugins.
    on(SendingRequest) { request, _ ->
        if (request.attributes.getOrNull(signedTransfer) == true) {
            request.headers.remove(HttpHeaders.Authorization)
        }
    }
}

/** Built-in Hugging Face Hub adapter. It borrows the supplied Ktor client. */
@OptIn(ExperimentalCaraMLApi::class)
class HuggingFaceProvider(
    borrowedClient: HttpClient,
    private val token: String? = null,
    baseUrl: String = DEFAULT_BASE_URL,
) : ModelProvider, ArtifactSource, SuspendCloseable {
    override val id = PROVIDER_ID
    override val providerId = PROVIDER_ID
    private val origin = validateOrigin(baseUrl)
    private val json = Json { ignoreUnknownKeys = true; isLenient = false }
    private val client: HttpClient

    init {
        require(borrowedClient.pluginOrNull(HttpRequestRetry) == null) {
            "The supplied Ktor client must not install HttpRequestRetry; discovery applies bounded retries"
        }
        client = borrowedClient.config {
            followRedirects = false
            install(originAuthorization)
        }
    }

    /** Closes this derived request client. Its host-supplied parent client remains borrowed. */
    override suspend fun close() { client.close() }

    override fun search(query: String, pageToken: String?): Flow<ModelPage> = flow {
        require(query.length <= MAX_QUERY_LENGTH)
        val endpoint = URLBuilder(origin).apply {
            appendPathSegments("api", "models")
            parameters.append("limit", SEARCH_PAGE_SIZE.toString())
            if (query.isNotBlank()) parameters.append("search", query)
            pageToken?.let { token ->
                require(token.length <= MAX_PAGE_TOKEN_LENGTH)
                parameters.append("cursor", token)
            }
        }.buildString()
        val body = requestText(endpoint, MAX_METADATA_BYTES)
        val items = parseModels(body)
        emit(ModelPage(items, nextPageToken = null))
    }

    override suspend fun resolve(model: ModelCandidate): List<ArtifactDescriptor> {
        require(model.providerId == id)
        validateRepository(model.remoteId)
        val revision = model.metadata[REVISION_KEY]?.takeIf { it.isNotBlank() } ?: "main"
        require(IMMUTABLE_REVISION.matches(revision)) {
            "Hub search result has no immutable commit revision; resolve it again with a pinned revision"
        }
        val endpoint = URLBuilder(origin).apply {
            appendPathSegments("api", "models", *model.remoteId.split('/').toTypedArray(), "tree")
            revision.split('/').forEach { appendPathSegments(it) }
            parameters.append("recursive", "true")
            parameters.append("expand", "true")
        }.buildString()
        val tree = json.parseToJsonElement(requestText(endpoint, MAX_TREE_BYTES)) as? JsonArray
            ?: throw HubProtocolException("Hub returned an invalid file list")
        return tree.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            if (obj.string("type") != "file") return@mapNotNull null
            val path = obj.string("path") ?: return@mapNotNull null
            validateRepositoryPath(path)
            val size = obj.long("size")?.takeIf { it >= 0L }
            val ref = ArtifactRef(
                id = io.github.debanshu777.caraml.core.api.ArtifactId("${model.remoteId}\u0000$path"),
                providerId = id,
                revision = revision,
            )
            ArtifactDescriptor(ref, path.substringAfterLast('/'), size)
        }
    }

    override suspend fun withTransfer(
        artifact: ArtifactDescriptor,
        offsetBytes: Long,
        consume: suspend (ArtifactTransfer) -> Unit,
    ) {
        require(artifact.ref.providerId == id)
        require(offsetBytes >= 0L)
        val (repository, path) = decodeArtifactId(artifact.ref.id.value)
        validateRepository(repository)
        validateRepositoryPath(path)
        val revision = artifact.ref.revision?.takeIf { it.isNotBlank() }
            ?: throw HubProtocolException("Artifact revision is missing")
        require(IMMUTABLE_REVISION.matches(revision)) {
            "Hugging Face transfers require an immutable commit revision"
        }
        val endpoint = resolveUrl(repository, revision, path)
        request(endpoint, includeToken = true, offsetBytes) { response ->
            if (response.status.value in 300..399) {
                val redirect = response.headers[HttpHeaders.Location]
                    ?: throw HubProtocolException("Hub redirect is missing a destination")
                val target = validateSignedDownloadUrl(redirect)
                response.bodyAsChannel().cancel()
                request(target, includeToken = false, offsetBytes) { redirected ->
                    consume(transfer(redirected, artifact, offsetBytes))
                }
            } else {
                consume(transfer(response, artifact, offsetBytes))
            }
        }
    }

    private suspend fun transfer(response: HttpResponse, artifact: ArtifactDescriptor, offsetBytes: Long): ArtifactTransfer {
        if (offsetBytes == 0L && response.status != HttpStatusCode.OK) {
            response.bodyAsChannel().cancel()
            throw HubHttpException(response.status.value)
        }
        if (offsetBytes > 0L && response.status != HttpStatusCode.PartialContent) {
            response.bodyAsChannel().cancel()
            throw HubProtocolException("Hub did not honor the requested resume offset")
        }
        val length = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            ?.takeIf { it >= 0L }
        var totalBytes = artifact.sizeBytes ?: length
        if (offsetBytes > 0L) {
            val parsed = response.headers[HttpHeaders.ContentRange]?.let(::parseContentRange)
            if (parsed == null || parsed.first != offsetBytes ||
                (length != null && length != parsed.second - parsed.first + 1L) ||
                (artifact.sizeBytes != null && parsed.third != artifact.sizeBytes)
            ) {
                response.bodyAsChannel().cancel()
                throw HubProtocolException("Resumed Hub response does not match the requested artifact range")
            }
            totalBytes = parsed.third
        } else if (artifact.sizeBytes != null && length != null && length != artifact.sizeBytes) {
            response.bodyAsChannel().cancel()
            throw HubProtocolException("Hub response size does not match the artifact descriptor")
        }
        return KtorArtifactTransfer(response.bodyAsChannel(), totalBytes, response.headers[HttpHeaders.ETag])
    }

    private suspend fun requestText(url: String, maxBytes: Long): String =
        request(url, includeToken = true, offsetBytes = null) { response ->
            if (!response.status.isSuccess()) throw HubHttpException(response.status.value)
            val declaredLength = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            if (declaredLength != null && declaredLength > maxBytes) {
                throw HubProtocolException("Hub response exceeds the configured size limit")
            }
            val bytes = response.bodyAsChannel().readRemaining(maxBytes + 1L).readByteArray()
            if (bytes.size.toLong() > maxBytes) throw HubProtocolException("Hub response exceeds the configured size limit")
            try {
                bytes.decodeToString(throwOnInvalidSequence = true)
            } catch (failure: Exception) {
                throw HubProtocolException("Hub response is not valid UTF-8", failure)
            }
        }

    private suspend fun <T> request(
        url: String,
        includeToken: Boolean,
        offsetBytes: Long?,
        consume: suspend (HttpResponse) -> T,
    ): T = client.prepareGet(url) {
        expectSuccess = false
        attributes.put(signedTransfer, !includeToken)
        if (includeToken && token != null) bearerAuth(token)
        if (offsetBytes != null && offsetBytes > 0L) header(HttpHeaders.Range, "bytes=$offsetBytes-")
    }.execute { response -> consume(response) }

    private fun parseModels(body: String): List<ModelCandidate> {
        val array = json.parseToJsonElement(body) as? JsonArray
            ?: throw HubProtocolException("Hub returned an invalid model page")
        return array.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val repo = item.string("id") ?: item.string("modelId") ?: return@mapNotNull null
            try { validateRepository(repo) } catch (_: IllegalArgumentException) { return@mapNotNull null }
            val metadata = buildMap {
                item.string("sha")?.let { put(REVISION_KEY, it) }
                item.string("pipeline_tag")?.let { put("task", it) }
                item.string("library_name")?.let { put("library", it) }
            }
            ModelCandidate(id, repo, item.string("name") ?: repo, item.string("description"), metadata)
        }
    }

    private fun resolveUrl(repository: String, revision: String, path: String): String =
        URLBuilder(origin).apply {
            appendPathSegments(*repository.split('/').toTypedArray(), "resolve")
            revision.split('/').forEach { appendPathSegments(it) }
            path.split('/').forEach { appendPathSegments(it) }
        }.buildString()

    private fun validateSignedDownloadUrl(value: String): String {
        val target = try { URLBuilder(value).build() } catch (failure: Exception) {
            throw HubProtocolException("Hub returned an invalid download URL", failure)
        }
        require(target.protocol == URLProtocol.HTTPS) { "Hub download redirects must use HTTPS" }
        require(target.user == null && target.password == null && target.fragment.isEmpty()) {
            "Hub returned an unsafe download URL"
        }
        return target.toString()
    }

    private fun validateOrigin(value: String): String {
        val url = try { URLBuilder(value).build() } catch (failure: Exception) {
            throw IllegalArgumentException("Invalid Hugging Face base URL", failure)
        }
        require(url.protocol == URLProtocol.HTTPS) { "Hub requests must use HTTPS" }
        require(url.user == null && url.password == null && url.fragment.isEmpty())
        return url.toString().trimEnd('/')
    }

    private fun validateRepository(value: String) {
        require(value.length in 1..MAX_REPOSITORY_LENGTH && value.split('/').size in 1..2)
        require(value.split('/').all { it.isNotBlank() && it != "." && it != ".." })
        require(value.none { it.code < 32 || it == '\\' || it == '\u0000' })
    }

    private fun validateRepositoryPath(value: String) {
        require(value.length in 1..MAX_PATH_LENGTH)
        require(value.split('/').all { it.isNotBlank() && it != "." && it != ".." })
        require(value.none { it.code < 32 || it == '\\' || it == '\u0000' })
    }

    private fun decodeArtifactId(value: String): Pair<String, String> {
        val split = value.indexOf('\u0000')
        if (split <= 0 || split == value.lastIndex) throw HubProtocolException("Artifact identity is invalid")
        return value.substring(0, split) to value.substring(split + 1)
    }

    private fun parseContentRange(value: String): Triple<Long, Long, Long>? {
        val match = CONTENT_RANGE.matchEntire(value.trim()) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        val total = match.groupValues[3].toLongOrNull() ?: return null
        if (total <= 0L || start < 0L || end < start || end >= total) return null
        return Triple(start, end, total)
    }

    private fun JsonObject.string(name: String): String? = this[name]?.let {
        runCatching { it.jsonPrimitive.content }.getOrNull()
    }

    private fun JsonObject.long(name: String): Long? = string(name)?.toLongOrNull()

    private class KtorArtifactTransfer(
        private val channel: ByteReadChannel,
        override val totalBytes: Long?,
        override val validator: String?,
    ) : ArtifactTransfer {
        override val body: Flow<ByteArray> = flow {
            val buffer = ByteArray(TRANSFER_CHUNK_BYTES)
            while (true) {
                val count = channel.readAvailable(buffer)
                if (count == -1) break
                if (count > 0) emit(buffer.copyOf(count))
            }
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://huggingface.co"
        private val PROVIDER_ID = ProviderId("huggingface")
        private const val REVISION_KEY = "hub_revision"
        private const val MAX_QUERY_LENGTH = 512
        private const val MAX_PAGE_TOKEN_LENGTH = 2_048
        private const val SEARCH_PAGE_SIZE = 50
        private const val MAX_METADATA_BYTES = 2L * 1024L * 1024L
        private const val MAX_TREE_BYTES = 8L * 1024L * 1024L
        private const val MAX_REPOSITORY_LENGTH = 512
        private const val MAX_PATH_LENGTH = 4_096
        private const val TRANSFER_CHUNK_BYTES = 64 * 1024
        private val IMMUTABLE_REVISION = Regex("^[0-9a-fA-F]{40,64}$")
        private val CONTENT_RANGE = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)")
    }
}

class HubHttpException(val statusCode: Int) : Exception("Hugging Face Hub request failed with HTTP $statusCode")
class HubProtocolException(message: String, cause: Throwable? = null) : Exception(message, cause)
