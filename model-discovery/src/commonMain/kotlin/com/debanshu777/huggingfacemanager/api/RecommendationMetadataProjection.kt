package com.debanshu777.huggingfacemanager.api

import com.debanshu777.huggingfacemanager.model.ListModelsResponse
import com.debanshu777.huggingfacemanager.model.ModelDetailResponse
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

internal object RecommendationMetadataV1 {
    val schemaVersion: Int = RECOMMENDATION_METADATA_SCHEMA_VERSION

    private const val EXPECTED_SCHEMA_VERSION = 1
    private const val MAX_STRING_LENGTH = 256
    private const val MAX_COLLECTION_SIZE = 256
    private val exactInteger = Regex("-?(?:0|[1-9][0-9]*)")
    private val decoder = Json {
        ignoreUnknownKeys = true
        isLenient = false
        coerceInputValues = false
    }

    fun decodeDetail(json: Json, body: String): ModelDetailResponse {
        requireCurrentSchema()
        val root = json.parseToJsonElement(body).requiredObject()
        validateDetail(root)
        return decoder.decodeFromJsonElement(root)
    }

    fun decodeList(json: Json, body: String): ListModelsResponse {
        requireCurrentSchema()
        val root = json.parseToJsonElement(body).requiredObject()
        root.requireOnlyKeys(LIST_ROOT_KEYS)
        root.optionalArray("models")?.validateElements(::validateListModel)
        root.optionalExactInt("numItemsPerPage")
        root.optionalExactInt("numTotalItems")
        root.optionalExactInt("pageIndex")
        return decoder.decodeFromJsonElement(root)
    }

    private fun requireCurrentSchema() {
        if (schemaVersion != EXPECTED_SCHEMA_VERSION) invalidMetadata()
    }

    private fun validateDetail(root: JsonObject) {
        root.requireOnlyKeys(DETAIL_ROOT_KEYS)
        root.optionalBoundedStrings(
            "author",
            "createdAt",
            "_id",
            "id",
            "lastModified",
            "library_name",
            "modelId",
            "pipeline_tag",
            "sha",
        )
        root.optionalBoolean("disabled")
        root.optionalExactInt("downloads")
        root.optionalBoolean("gated")
        root.optionalExactInt("likes")
        root.optionalBoolean("private")
        root.optionalExactLong("usedStorage")
        root.optionalObject("cardData")?.let(::validateCardData)
        root.optionalObject("config")?.let(::validateConfig)
        root.optionalObject("gguf")?.let(::validateGguf)
        root.optionalObject("safetensors")?.let(::validateSafetensors)
        root.optionalArray("siblings")?.validateElements { sibling ->
            sibling.requireOnlyKeys(setOf("rfilename"))
            sibling.optionalBoundedStrings("rfilename")
        }
        root.optionalArray("tags")?.validateStringElements()
    }

    private fun validateCardData(value: JsonObject) {
        value.requireOnlyKeys(CARD_DATA_KEYS)
        value["base_model"]?.takeUnless { it is JsonNull }?.let { baseModel ->
            when (baseModel) {
                is JsonPrimitive -> baseModel.requireBoundedString()
                is JsonArray -> baseModel.validateStringElements()
                else -> invalidMetadata()
            }
        }
        value.optionalBoundedStrings(
            "base_model_relation",
            "library_name",
            "license",
            "pipeline_tag",
        )
        value.optionalBoolean("inference")
        value.optionalArray("language")?.validateStringElements()
        value.optionalArray("tags")?.validateStringElements()
    }

    private fun validateConfig(value: JsonObject) {
        value.requireOnlyKeys(CONFIG_KEYS)
        value.optionalArray("architectures")?.validateStringElements()
        value.optionalBoundedStrings("model_type")
        value.optionalObject("auto_map")?.let { autoMap ->
            autoMap.requireOnlyKeys(AUTO_MAP_KEYS)
            autoMap.optionalBoundedStrings("AutoConfig", "AutoModel", "AutoModelForMaskedLM")
        }
        value.optionalObject("tokenizer_config")?.let { tokenizer ->
            tokenizer.requireOnlyKeys(TOKENIZER_KEYS)
            tokenizer.optionalBoundedStrings("bos_token", "eos_token", "mask_token", "pad_token")
        }
    }

    private fun validateGguf(value: JsonObject) {
        value.requireOnlyKeys(GGUF_KEYS)
        value.optionalBoundedStrings("architecture", "bos_token", "eos_token")
        value.optionalBoolean("causal")
        value.optionalExactInt("context_length")
        value.optionalExactLong("total")
    }

    private fun validateSafetensors(value: JsonObject) {
        value.requireOnlyKeys(SAFETENSORS_KEYS)
        value.optionalExactLong("total")
        value.optionalObject("parameters")?.let { parameters ->
            parameters.requireOnlyKeys(setOf("BF16"))
            parameters.optionalExactLong("BF16")
        }
    }

    private fun validateListModel(value: JsonObject) {
        value.requireOnlyKeys(LIST_MODEL_KEYS)
        value.optionalBoundedStrings(
            "author",
            "gated",
            "id",
            "lastModified",
            "pipeline_tag",
            "repoType",
        )
        value.optionalExactInt("downloads")
        value.optionalBoolean("isLikedByUser")
        value.optionalExactInt("likes")
        value.optionalExactLong("numParameters")
        value.optionalBoolean("private")
        value.optionalObject("authorData")?.let { author ->
            author.requireOnlyKeys(AUTHOR_DATA_KEYS)
            author.optionalBoundedStrings("avatarUrl", "fullname", "_id", "name", "plan", "type")
            author.optionalExactInt("followerCount")
            author.optionalBoolean("isHf")
            author.optionalBoolean("isHfAdmin")
            author.optionalBoolean("isMod")
            author.optionalBoolean("isPro")
            author.optionalBoolean("isUserFollowing")
        }
        value.optionalArray("availableInferenceProviders")?.validateElements { provider ->
            provider.requireOnlyKeys(PROVIDER_KEYS)
            provider.optionalBoolean("isCheapestPricingOutput")
            provider.optionalBoolean("isFastestThroughput")
            provider.optionalBoolean("isModelAuthor")
            provider.optionalBoundedStrings(
                "modelStatus",
                "provider",
                "providerId",
                "providerStatus",
                "task",
            )
        }
    }

    private fun JsonElement.requiredObject(): JsonObject = this as? JsonObject ?: invalidMetadata()

    private fun JsonObject.requireOnlyKeys(allowed: Set<String>) {
        if (keys.any { it !in allowed }) invalidMetadata()
    }

    private fun JsonObject.optionalObject(key: String): JsonObject? = when (val value = this[key]) {
        null, JsonNull -> null
        is JsonObject -> value
        else -> invalidMetadata()
    }

    private fun JsonObject.optionalArray(key: String): JsonArray? = when (val value = this[key]) {
        null, JsonNull -> null
        is JsonArray -> value.also { if (it.size > MAX_COLLECTION_SIZE) invalidMetadata() }
        else -> invalidMetadata()
    }

    private fun JsonArray.validateElements(validate: (JsonObject) -> Unit) {
        if (size > MAX_COLLECTION_SIZE) invalidMetadata()
        forEach { validate(it.requiredObject()) }
    }

    private fun JsonArray.validateStringElements() {
        if (size > MAX_COLLECTION_SIZE) invalidMetadata()
        forEach { element ->
            (element as? JsonPrimitive)?.requireBoundedString() ?: invalidMetadata()
        }
    }

    private fun JsonObject.optionalBoundedStrings(vararg keys: String) {
        keys.forEach { key ->
            when (val value = this[key]) {
                null, JsonNull -> Unit
                is JsonPrimitive -> value.requireBoundedString()
                else -> invalidMetadata()
            }
        }
    }

    private fun JsonPrimitive.requireBoundedString() {
        if (!isString || content.length > MAX_STRING_LENGTH ||
            content.any { it.code < 32 || it.code == 127 }
        ) {
            invalidMetadata()
        }
    }

    private fun JsonObject.optionalBoolean(key: String) {
        val value = this[key] ?: return
        if (value is JsonNull) return
        if (value !is JsonPrimitive || value.isString || value.booleanOrNull == null) invalidMetadata()
    }

    private fun JsonObject.optionalExactInt(key: String) {
        val value = this[key] ?: return
        if (value is JsonNull) return
        if (value !is JsonPrimitive || value.isString || !exactInteger.matches(value.content) ||
            value.intOrNull == null
        ) {
            invalidMetadata()
        }
    }

    private fun JsonObject.optionalExactLong(key: String) {
        val value = this[key] ?: return
        if (value is JsonNull) return
        if (value !is JsonPrimitive || value.isString || !exactInteger.matches(value.content) ||
            value.longOrNull == null
        ) {
            invalidMetadata()
        }
    }

    private fun invalidMetadata(): Nothing =
        throw SerializationException("Invalid recommendation metadata schema")

    private val LIST_ROOT_KEYS = setOf("models", "numItemsPerPage", "numTotalItems", "pageIndex")
    private val LIST_MODEL_KEYS = setOf(
        "author",
        "authorData",
        "availableInferenceProviders",
        "downloads",
        "gated",
        "id",
        "isLikedByUser",
        "lastModified",
        "likes",
        "numParameters",
        "pipeline_tag",
        "private",
        "repoType",
    )
    private val AUTHOR_DATA_KEYS = setOf(
        "avatarUrl",
        "followerCount",
        "fullname",
        "_id",
        "isHf",
        "isHfAdmin",
        "isMod",
        "isPro",
        "isUserFollowing",
        "name",
        "plan",
        "type",
    )
    private val PROVIDER_KEYS = setOf(
        "isCheapestPricingOutput",
        "isFastestThroughput",
        "isModelAuthor",
        "modelStatus",
        "provider",
        "providerId",
        "providerStatus",
        "task",
    )
    private val DETAIL_ROOT_KEYS = setOf(
        "author",
        "cardData",
        "config",
        "createdAt",
        "disabled",
        "downloads",
        "gated",
        "gguf",
        "_id",
        "id",
        "lastModified",
        "library_name",
        "likes",
        "modelId",
        "pipeline_tag",
        "private",
        "safetensors",
        "sha",
        "siblings",
        "tags",
        "usedStorage",
    )
    private val CARD_DATA_KEYS = setOf(
        "base_model",
        "base_model_relation",
        "inference",
        "language",
        "library_name",
        "license",
        "pipeline_tag",
        "tags",
    )
    private val CONFIG_KEYS = setOf("architectures", "auto_map", "model_type", "tokenizer_config")
    private val AUTO_MAP_KEYS = setOf("AutoConfig", "AutoModel", "AutoModelForMaskedLM")
    private val TOKENIZER_KEYS = setOf("bos_token", "eos_token", "mask_token", "pad_token")
    private val GGUF_KEYS = setOf("architecture", "bos_token", "causal", "context_length", "eos_token", "total")
    private val SAFETENSORS_KEYS = setOf("parameters", "total")
}

internal class BoundedListSerializer<T>(
    elementSerializer: KSerializer<T>,
    private val maxElements: Int,
) : KSerializer<List<T>> {
    private val delegate = ListSerializer(elementSerializer)
    private val itemSerializer = elementSerializer
    override val descriptor: SerialDescriptor = delegate.descriptor

    init {
        require(maxElements >= 0) { "maxElements must not be negative" }
    }

    override fun deserialize(decoder: Decoder): List<T> {
        val composite = decoder.beginStructure(descriptor)
        val result = ArrayList<T>(maxElements.coerceAtMost(MAX_INITIAL_CAPACITY))
        while (true) {
            val index = composite.decodeElementIndex(descriptor)
            if (index == CompositeDecoder.DECODE_DONE) break
            if (index != result.size) throw SerializationException("Invalid list element index")
            if (result.size >= maxElements) throw ResponseLimitExceededException()
            result += composite.decodeSerializableElement(descriptor, index, itemSerializer)
        }
        composite.endStructure(descriptor)
        return result
    }

    override fun serialize(encoder: Encoder, value: List<T>) = delegate.serialize(encoder, value)

    private companion object {
        const val MAX_INITIAL_CAPACITY = 256
    }
}
