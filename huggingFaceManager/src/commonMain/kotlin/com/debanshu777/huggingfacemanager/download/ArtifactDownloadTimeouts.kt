package com.debanshu777.huggingfacemanager.download

import io.ktor.client.HttpClientConfig
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig

/** Artifact bodies may stream for hours; connectivity and inactivity remain bounded. */
internal fun HttpClientConfig<*>.configureArtifactDownloadTimeouts() {
    install(HttpTimeout) {
        requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
        connectTimeoutMillis = 30_000L
        socketTimeoutMillis = 300_000L
    }
}

/** Fixed transport codes only: exception messages can contain remote URLs. */
enum class DownloadTransportFailure { REQUEST_TIMEOUT, CONNECT_TIMEOUT, SOCKET_TIMEOUT, OTHER }

fun downloadTransportFailure(error: Throwable): DownloadTransportFailure {
    var cause: Throwable? = error
    repeat(8) {
        when (cause) {
            is HttpRequestTimeoutException -> return DownloadTransportFailure.REQUEST_TIMEOUT
            is ConnectTimeoutException -> return DownloadTransportFailure.CONNECT_TIMEOUT
            is SocketTimeoutException -> return DownloadTransportFailure.SOCKET_TIMEOUT
        }
        val next = cause?.cause
        if (next === cause) return DownloadTransportFailure.OTHER
        cause = next
    }
    return DownloadTransportFailure.OTHER
}
