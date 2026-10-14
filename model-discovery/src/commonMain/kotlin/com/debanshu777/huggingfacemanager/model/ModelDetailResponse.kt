package com.debanshu777.huggingfacemanager.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

internal object StringOrStringListSerializer : KSerializer<List<String>> {
    private val listSerializer = ListSerializer(String.serializer())
    override val descriptor: SerialDescriptor = listSerializer.descriptor

    override fun deserialize(decoder: Decoder): List<String> {
        val jsonDecoder = decoder as JsonDecoder
        val element = jsonDecoder.decodeJsonElement()
        return when (element) {
            is JsonArray -> element.map { it.jsonPrimitive.content }
            is JsonPrimitive -> listOf(element.content)
            else -> emptyList()
        }
    }

    override fun serialize(encoder: Encoder, value: List<String>) {
        listSerializer.serialize(encoder, value)
    }
}

@Serializable
data class ModelDetailResponse(
    @SerialName("author")
    val author: String? = null,
    @SerialName("cardData")
    val cardData: CardData? = null,
    @SerialName("config")
    val config: Config? = null,
    @SerialName("createdAt")
    val createdAt: String? = null,
    @SerialName("disabled")
    val disabled: Boolean? = null,
    @SerialName("downloads")
    val downloads: Int? = null,
    @SerialName("gated")
    val gated: Boolean? = null,
    @SerialName("gguf")
    val gguf: Gguf? = null,
    @SerialName("_id")
    val _id: String? = null,
    @SerialName("id")
    val id: String? = null,
    @SerialName("lastModified")
    val lastModified: String? = null,
    @SerialName("library_name")
    val libraryName: String? = null,
    @SerialName("likes")
    val likes: Int? = null,
    @SerialName("modelId")
    val modelId: String? = null,
    @SerialName("pipeline_tag")
    val pipelineTag: String? = null,
    @SerialName("private")
    val `private`: Boolean? = null,
    @SerialName("safetensors")
    val safetensors: Safetensors? = null,
    @SerialName("sha")
    val sha: String? = null,
    @SerialName("siblings")
    val siblings: List<Sibling?>? = null,
    @SerialName("tags")
    val tags: List<String?>? = null,
    @SerialName("usedStorage")
    val usedStorage: Long? = null,
) {
    @Serializable
    data class CardData(
        @SerialName("base_model")
        @Serializable(with = StringOrStringListSerializer::class)
        val baseModel: List<String>? = null,
        @SerialName("base_model_relation")
        val baseModelRelation: String? = null,
        @SerialName("inference")
        val inference: Boolean? = null,
        @SerialName("language")
        val language: List<String?>? = null,
        @SerialName("library_name")
        val libraryName: String? = null,
        @SerialName("license")
        val license: String? = null,
        @SerialName("pipeline_tag")
        val pipelineTag: String? = null,
        @SerialName("tags")
        val tags: List<String?>? = null,
    )

    @Serializable
    data class Config(
        @SerialName("architectures")
        val architectures: List<String?>? = null,
        @SerialName("auto_map")
        val autoMap: AutoMap? = null,
        @SerialName("model_type")
        val modelType: String? = null,
        @SerialName("tokenizer_config")
        val tokenizerConfig: TokenizerConfig? = null,
    ) {
        @Serializable
        data class AutoMap(
            @SerialName("AutoConfig")
            val autoConfig: String? = null,
            @SerialName("AutoModel")
            val autoModel: String? = null,
            @SerialName("AutoModelForMaskedLM")
            val autoModelForMaskedLM: String? = null,
        )

        @Serializable
        data class TokenizerConfig(
            @SerialName("bos_token")
            val bosToken: String? = null,
            @SerialName("eos_token")
            val eosToken: String? = null,
            @SerialName("mask_token")
            val maskToken: String? = null,
            @SerialName("pad_token")
            val padToken: String? = null,
        )
    }

    @Serializable
    data class Gguf(
        @SerialName("architecture")
        val architecture: String? = null,
        @SerialName("bos_token")
        val bosToken: String? = null,
        @SerialName("causal")
        val causal: Boolean? = null,
        @SerialName("context_length")
        val contextLength: Int? = null,
        @SerialName("eos_token")
        val eosToken: String? = null,
        @SerialName("total")
        val total: Long? = null,
    )

    @Serializable
    data class Safetensors(
        @SerialName("parameters")
        val parameters: Parameters? = null,
        @SerialName("total")
        val total: Long? = null,
    ) {
        @Serializable
        data class Parameters(
            @SerialName("BF16")
            val bF16: Long? = null,
        )
    }

    @Serializable
    data class Sibling(
        @SerialName("rfilename")
        val rfilename: String? = null,
    )
}