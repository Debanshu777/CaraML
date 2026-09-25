package com.debanshu777.huggingfacemanager.download

import io.ktor.http.URLBuilder

/** Bounded response provenance derived from an actual completed platform response. */
@ConsistentCopyVisibility
data class DownloadResponseProvenance private constructor(
    val origin: String,
    val statusCode: Int,
) {
    companion object {
        fun validate(value: String, statusCode: Int): DownloadResponseProvenance? {
            if (statusCode !in 200..299 || value.length !in 1..2_048) return null
            val url = runCatching { URLBuilder(value).build() }.getOrNull() ?: return null
            if (url.protocol.name != "https" || url.port != 443 || url.host.isBlank()) return null
            val host = url.host.lowercase()
            if (!isAllowedHost(host)) return null
            val origin = "https://$host"
            return DownloadResponseProvenance(origin, statusCode)
        }

        private fun isAllowedHost(host: String): Boolean =
            host == "huggingface.co" || host.endsWith(".huggingface.co") ||
                host == "hf.co" || host.endsWith(".hf.co") ||
                host == "xethub.hf.co" || host.endsWith(".xethub.hf.co")
    }

}
