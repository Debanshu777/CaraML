package com.debanshu777.huggingfacemanager

import io.ktor.client.HttpClient

expect fun createPlatformHttpClient(block: io.ktor.client.HttpClientConfig<*>.() -> Unit): HttpClient
