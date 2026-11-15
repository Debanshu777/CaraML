package com.debanshu777.caraml.core.data.settings

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Legacy decoding projects display fields directly, without materializing repository contents. */
internal class RecentModelHistoryCodec {
    private val json = Json { ignoreUnknownKeys = true }

    data class Decoded(val models: List<RecentlyViewedModel>, val legacy: Boolean)

    fun decode(encoded: String): Decoded? = runCatching {
        val legacy = encoded.trimStart().startsWith('[')
        val models = if (legacy) {
            json.decodeFromString(ListSerializer(LegacyVisit.serializer()), encoded).map { visit ->
                val detail = visit.detail
                RecentlyViewedModel(
                    mode = visit.mode,
                    modelId = detail.modelId?.takeIf(String::isNotBlank) ?: detail.id.orEmpty(),
                    author = detail.author, task = detail.pipelineTag,
                    parameters = detail.gguf?.total ?: detail.safetensors?.total,
                    downloads = detail.downloads, likes = detail.likes,
                )
            }
        } else {
            val history = json.decodeFromString(History.serializer(), encoded)
            require(history.version == 1) { "Unsupported history version" }
            history.models
        }
        require(models.none { it.modelId.isBlank() }) { "History entry has no model identity" }
        Decoded(models.distinctBy { it.identity }.take(RECENTLY_VIEWED_MODELS_LIMIT), legacy)
    }.getOrNull()

    fun encode(models: List<RecentlyViewedModel>): String = json.encodeToString(History.serializer(), History(1, models))

    @Serializable
    private data class History(val version: Int, val models: List<RecentlyViewedModel>)

    @Serializable
    private data class LegacyVisit(val mode: RecentModelKind, val detail: LegacyDetail)

    @Serializable
    private data class LegacyDetail(
        val modelId: String? = null,
        val id: String? = null,
        val author: String? = null,
        @SerialName("pipeline_tag") val pipelineTag: String? = null,
        val gguf: LegacyParameters? = null,
        val safetensors: LegacyParameters? = null,
        val downloads: Int? = null,
        val likes: Int? = null,
    )

    @Serializable
    private data class LegacyParameters(val total: Long? = null)
}
