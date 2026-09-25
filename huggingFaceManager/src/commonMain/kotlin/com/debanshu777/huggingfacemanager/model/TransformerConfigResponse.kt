package com.debanshu777.huggingfacemanager.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TransformerConfigResponse(
    @SerialName("num_hidden_layers") val numHiddenLayers: Int? = null,
    @SerialName("num_key_value_heads") val numKeyValueHeads: Int? = null,
    @SerialName("num_attention_heads") val numAttentionHeads: Int? = null,
    @SerialName("hidden_size") val hiddenSize: Int? = null,
    @SerialName("head_dim") val headDim: Int? = null,
    @SerialName("max_position_embeddings") val maxPositionEmbeddings: Int? = null,
)
