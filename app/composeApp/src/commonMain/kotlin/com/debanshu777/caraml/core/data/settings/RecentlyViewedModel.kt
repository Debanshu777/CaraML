package com.debanshu777.caraml.core.data.settings

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Persisted values deliberately retain the names used by the original history format. */
@Serializable
enum class RecentModelKind(val persistedValue: String) {
    @SerialName("LanguageModels") Text("LanguageModels"),
    @SerialName("DiffusionImage") Image("DiffusionImage"),
    @SerialName("DiffusionVideo") Video("DiffusionVideo"),
}

/** A display snapshot only: repository contents and download identities are never persisted here. */
@Serializable
data class RecentlyViewedModel(
    val mode: RecentModelKind,
    val modelId: String,
    val author: String? = null,
    val task: String? = null,
    val parameters: Long? = null,
    val downloads: Int? = null,
    val likes: Int? = null,
) {
    val identity: String get() = "${mode.persistedValue}:${modelId.trim().lowercase()}"
}

internal const val RECENTLY_VIEWED_MODELS_LIMIT = 20

internal fun prependRecentlyViewed(
    current: List<RecentlyViewedModel>,
    visit: RecentlyViewedModel,
    limit: Int = RECENTLY_VIEWED_MODELS_LIMIT,
): List<RecentlyViewedModel> = (listOf(visit) + current.filterNot { it.identity == visit.identity })
    .take(limit.coerceAtLeast(0))
