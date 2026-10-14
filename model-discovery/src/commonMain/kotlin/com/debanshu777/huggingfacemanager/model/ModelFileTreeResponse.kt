package com.debanshu777.huggingfacemanager.model


import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ModelFileTreeResponse(
    @SerialName("lfs")
    val lfs: Lfs? = null,
    @SerialName("oid")
    val oid: String? = null,
    @SerialName("path")
    val path: String? = null,
    @SerialName("size")
    val size: Long? = null,
    @SerialName("type")
    val type: String? = null,
    @SerialName("xetHash")
    val xetHash: String? = null
) {
    @Serializable
    data class Lfs(
        @SerialName("oid")
        val oid: String? = null,
        @SerialName("pointerSize")
        val pointerSize: Int? = null,
        @SerialName("size")
        val size: Long? = null
    )
}