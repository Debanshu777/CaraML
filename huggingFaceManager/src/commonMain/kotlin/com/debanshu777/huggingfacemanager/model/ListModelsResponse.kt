package com.debanshu777.huggingfacemanager.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ListModelsResponse(
    @SerialName("models")
    val models: List<Model?>? = null,
    @SerialName("numItemsPerPage")
    val numItemsPerPage: Int? = null,
    @SerialName("numTotalItems")
    val numTotalItems: Int? = null,
    @SerialName("pageIndex")
    val pageIndex: Int? = null
) {
    @Serializable
    data class Model(
        @SerialName("author")
        val author: String? = null,
        @SerialName("authorData")
        val authorData: AuthorData? = null,
        @SerialName("availableInferenceProviders")
        val availableInferenceProviders: List<AvailableInferenceProvider?>? = null,
        @SerialName("downloads")
        val downloads: Int? = null,
        @SerialName("gated")
        val gated: String? = null,
        @SerialName("id")
        val id: String? = null,
        @SerialName("isLikedByUser")
        val isLikedByUser: Boolean? = null,
        @SerialName("lastModified")
        val lastModified: String? = null,
        @SerialName("createdAt")
        val createdAt: String? = null,
        @SerialName("library_name")
        val libraryName: String? = null,
        @SerialName("tags")
        val tags: List<String>? = null,
        /** GGUF architecture, when the public list projection provides it. */
        val architecture: String? = null,
        val contextLength: Int? = null,
        @SerialName("likes")
        val likes: Int? = null,
        @SerialName("numParameters")
        val numParameters: Long? = null,
        @SerialName("pipeline_tag")
        val pipelineTag: String? = null,
        @SerialName("private")
        val `private`: Boolean? = null,
        @SerialName("repoType")
        val repoType: String? = null
    ) {
        @Serializable
        data class AuthorData(
            @SerialName("avatarUrl")
            val avatarUrl: String? = null,
            @SerialName("followerCount")
            val followerCount: Int? = null,
            @SerialName("fullname")
            val fullname: String? = null,
            @SerialName("_id")
            val id: String? = null,
            @SerialName("isHf")
            val isHf: Boolean? = null,
            @SerialName("isHfAdmin")
            val isHfAdmin: Boolean? = null,
            @SerialName("isMod")
            val isMod: Boolean? = null,
            @SerialName("isPro")
            val isPro: Boolean? = null,
            @SerialName("isUserFollowing")
            val isUserFollowing: Boolean? = null,
            @SerialName("name")
            val name: String? = null,
            @SerialName("plan")
            val plan: String? = null,
            @SerialName("type")
            val type: String? = null
        )

        @Serializable
        data class AvailableInferenceProvider(
            @SerialName("isCheapestPricingOutput")
            val isCheapestPricingOutput: Boolean? = null,
            @SerialName("isFastestThroughput")
            val isFastestThroughput: Boolean? = null,
            @SerialName("isModelAuthor")
            val isModelAuthor: Boolean? = null,
            @SerialName("modelStatus")
            val modelStatus: String? = null,
            @SerialName("provider")
            val provider: String? = null,
            @SerialName("providerId")
            val providerId: String? = null,
            @SerialName("providerStatus")
            val providerStatus: String? = null,
            @SerialName("task")
            val task: String? = null
        )
    }
}
