package com.debanshu777.caraml.sdk.discovery.huggingface

import io.ktor.client.HttpClient

expect fun createPlatformHttpClient(block: io.ktor.client.HttpClientConfig<*>.() -> Unit): HttpClient
