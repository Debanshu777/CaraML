package com.debanshu777.huggingfacemanager.model


import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SearchModelsResponse(
    @SerialName("models")
    val models: List<Model?>? = null,
    @SerialName("modelsCount")
    val modelsCount: Int? = null,
    @SerialName("q")
    val q: String? = null
) {
    @Serializable
    data class Model(
        @SerialName("_id")
        val _id: String? = null,
        @SerialName("id")
        val id: String? = null,
        @SerialName("private")
        val `private`: Boolean? = null,
        @SerialName("trendingWeight")
        val trendingWeight: Int? = null,
    )
}