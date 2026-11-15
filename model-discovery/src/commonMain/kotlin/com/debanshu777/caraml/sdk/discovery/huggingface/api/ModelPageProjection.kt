package com.debanshu777.caraml.sdk.discovery.huggingface.api

import com.debanshu777.caraml.sdk.discovery.huggingface.model.ListModelsResponse
import io.ktor.http.Url
import io.ktor.http.URLProtocol
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** Validate the website listing envelope and retain bounded, card-useful metadata. */
internal object ModelPageProjection {
    private const val MAX_FIELD_LENGTH = 256
    private const val MAX_TAGS = 64
    private val integer = Regex("(?:0|[1-9][0-9]*)")

    fun decode(json: Json, body: String, requestedPage: Int, expectedPageSize: Int? = null): ModelPage {
        val envelope = json.parseToJsonElement(body) as? JsonObject
            ?: throw SerializationException("Invalid model page")
        val pageIndex = envelope.positiveIntOrZero("pageIndex")
            ?.takeIf { it == requestedPage } ?: throw SerializationException("Invalid page index")
        val pageSize = envelope.positiveInt("numItemsPerPage")
            ?.takeIf { it <= 100 } ?: throw SerializationException("Invalid page size")
        if (expectedPageSize != null && pageSize != expectedPageSize) {
            throw SerializationException("Model page size changed; refresh the query")
        }
        val totalCount = envelope.positiveIntOrZero("numTotalItems")
            ?: throw SerializationException("Invalid result count")
        val rows = envelope["models"] as? JsonArray ?: throw SerializationException("Invalid model rows")
        if (rows.size > pageSize) throw ResponseLimitExceededException()
        if (rows.size > totalCount) throw SerializationException("Inconsistent result count")
        if (rows.isEmpty() && pageIndex.toLong() * pageSize < totalCount) {
            throw SerializationException("Model catalog did not advance")
        }
        val models = rows.map { row ->
            val item = row as? JsonObject ?: throw SerializationException("Invalid model row")
            val id = item.safeString("id", 193)?.takeIf(::validRepositoryId)
                ?: throw SerializationException("Missing model ID")
            ListModelsResponse.Model(
                id = id,
                author = item.safeString("author"),
                authorData = (item["authorData"] as? JsonObject)?.let { author ->
                    ListModelsResponse.Model.AuthorData(
                        avatarUrl = author.safeString("avatarUrl", 2_048)?.let(::normalizeAvatarUrl),
                        fullname = author.safeString("fullname"),
                        name = author.safeString("name"),
                        type = author.safeString("type"),
                    )
                },
                gated = item.gatedValue(),
                repoType = item.safeString("repoType"),
                downloads = item.positiveIntOrZero("downloads"),
                likes = item.positiveIntOrZero("likes"),
                lastModified = item.safeString("lastModified"),
                createdAt = item.safeString("createdAt"),
                libraryName = item.safeString("library_name"),
                pipelineTag = item.safeString("pipeline_tag"),
                tags = item.safeTags(),
                architecture = (item["gguf"] as? JsonObject)?.safeString("architecture"),
                contextLength = (item["gguf"] as? JsonObject)?.positiveInt("context_length"),
                numParameters = item.parameterCount(),
                `private` = item.safeBoolean("private"),
            )
        }
        return ModelPage(models, null, totalCount, pageIndex, pageSize)
    }

    private fun JsonObject.gatedValue(): String? = when (val raw = this["gated"]) {
        is JsonPrimitive -> when {
            raw.isString -> raw.content.takeIf { it in setOf("auto", "manual", "false", "true") }
            else -> raw.booleanOrNull?.toString()
        }
        else -> null
    }

    private fun normalizeAvatarUrl(value: String): String? {
        val absolute = if (value.startsWith("/") && !value.startsWith("//")) "https://huggingface.co$value" else value
        val url = try { Url(absolute) } catch (_: IllegalArgumentException) { return null }
        return absolute.takeIf {
            url.protocol == URLProtocol.HTTPS && url.host.isNotBlank() &&
                url.user == null && url.password == null && !absolute.contains('\\')
        }
    }

    private fun JsonObject.parameterCount(): Long? {
        val direct = positiveLong("numParameters")
        val gguf = (this["gguf"] as? JsonObject)?.positiveLong("total")
        val safetensors = (this["safetensors"] as? JsonObject)?.let { st ->
            val total = st.positiveLong("total")
            val parameters = when (val raw = st["parameters"]) {
                null, JsonNull -> null
                is JsonObject -> sumParameterMap(raw) ?: return null
                else -> return null
            }
            if (total != null && parameters != null && total != parameters) return null
            total ?: parameters
        }
        val counts = listOfNotNull(direct, gguf, safetensors)
        return counts.firstOrNull()?.takeIf { first -> counts.all { it == first } }
    }

    private fun JsonObject.safeString(key: String, maxLength: Int = MAX_FIELD_LENGTH): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { value ->
            value.isNotEmpty() && value.length <= maxLength &&
                value.none { it.code < 32 || it.code == 127 }
        }

    private fun validRepositoryId(id: String): Boolean = id.split('/').let { segments ->
        segments.size in 1..2 && segments.all { segment ->
            segment.isNotEmpty() && segment.length <= 96 && segment != "." && segment != ".." &&
                ".." !in segment && "--" !in segment && segment.first() !in ".-" &&
                segment.last() !in ".-" &&
                segment.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }
        }
    }

    private fun sumParameterMap(map: JsonObject): Long? {
        if (map.isEmpty() || map.size > 32) return null
        var sum = 0L
        for (value in map.values) {
            val count = value.exactPositiveLong() ?: return null
            if (sum > Long.MAX_VALUE - count) return null
            sum += count
        }
        return sum.takeIf { it > 0 }
    }

    private fun JsonObject.safeTags(): List<String>? = (this["tags"] as? JsonArray)?.let { tags ->
        if (tags.size > MAX_TAGS) return null
        tags.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.takeIf { s ->
            s.isNotEmpty() && s.length <= MAX_FIELD_LENGTH && s.none { c -> c.code < 32 || c.code == 127 }
        } }
    }

    private fun JsonObject.safeBoolean(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull

    private fun JsonObject.positiveIntOrZero(key: String): Int? =
        (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.content?.takeIf(integer::matches)
            ?.toIntOrNull()

    private fun JsonObject.positiveInt(key: String): Int? = positiveIntOrZero(key)?.takeIf { it > 0 }

    private fun JsonObject.positiveLong(key: String): Long? = this[key]?.exactPositiveLong()

    private fun JsonElement.exactPositiveLong(): Long? =
        (this as? JsonPrimitive)?.takeUnless { it.isString || it === JsonNull }?.content
            ?.takeIf(integer::matches)?.toLongOrNull()?.takeIf { it > 0 }
}
