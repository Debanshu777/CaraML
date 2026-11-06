package com.debanshu777.caraml.sdk.discovery.huggingface.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonIgnoreUnknownKeys

/** A typed projection of the full Transformers config; known dimensions remain strictly typed. */
@OptIn(ExperimentalSerializationApi::class)
@JsonIgnoreUnknownKeys
@Serializable
data class TransformerConfigResponse(
    @SerialName("num_hidden_layers") val numHiddenLayers: Int? = null,
    @SerialName("num_key_value_heads") val numKeyValueHeads: Int? = null,
    @SerialName("num_attention_heads") val numAttentionHeads: Int? = null,
    @SerialName("hidden_size") val hiddenSize: Int? = null,
    @SerialName("head_dim") val headDim: Int? = null,
    @SerialName("max_position_embeddings") val maxPositionEmbeddings: Int? = null,
    @SerialName("model_type") val modelType: String? = null,
)
