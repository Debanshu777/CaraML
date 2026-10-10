package com.debanshu777.huggingfacemanager.download

import com.debanshu777.huggingfacemanager.createPlatformHttpClient
import com.sun.net.httpserver.HttpServer
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class ArtifactDownloadTimeoutTest {
    @Test
    fun wholeRequestDeadlineInterruptsAnActivelyProgressingBody() = runBlocking {
        val seen = mutableListOf<Int>()
        val result = consumeSlowBody(fixedDeadline = 300L, seen)
        assertTrue(result.isFailure, "Absolute deadline should interrupt the streaming body")
        val failure = result.exceptionOrNull()!!
        assertTrue(generateSequence(failure) { it.cause }.take(8).any { it is HttpRequestTimeoutException }, "Expected typed request timeout")
        assertEquals(DownloadTransportFailure.REQUEST_TIMEOUT, downloadTransportFailure(failure))
        println("synthetic_stream control=absoluteDeadline requestMs=300 chunkMs=100 bytesRead=${seen.size} reason=REQUEST_TIMEOUT")
        assertTrue(seen.isNotEmpty() && seen.size < 8, "Body must make progress before the deadline")
    }

    @Test
    fun productionPolicyCompletesBeyondTheOldScaledDeadline() = runBlocking {
        val seen = mutableListOf<Int>()
        val result = consumeSlowBody(fixedDeadline = null, seen)
        assertTrue(result.isSuccess)
        assertEquals(8, seen.size)
        println("synthetic_stream control=productionPolicy chunkMs=100 bytesRead=${seen.size} outcome=COMPLETED")
    }

    @Test
    fun inactivityStillTimesOutWithAnInfiniteTotalDeadline() = runBlocking {
        val seen = mutableListOf<Int>()
        val result = consumeSlowBody(fixedDeadline = null, seen, socketTimeout = 150L, chunkDelay = 400L)
        assertTrue(result.isFailure)
        assertEquals(DownloadTransportFailure.SOCKET_TIMEOUT, downloadTransportFailure(result.exceptionOrNull()!!))
    }

    @Test
    fun callerCancellationStillInterruptsTheStreamingBody() = runBlocking {
        val seen = mutableListOf<Int>()
        val result = consumeSlowBody(fixedDeadline = null, seen, cancellationAfter = 300L)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is TimeoutCancellationException)
        assertTrue(seen.isNotEmpty() && seen.size < 8)
    }

    @Test
    fun unrelatedSensitiveExceptionProducesOnlyTheStaticOtherCode() {
        assertEquals(DownloadTransportFailure.OTHER,
            downloadTransportFailure(IllegalStateException("secret https://private.invalid/token prompt")))
    }

    private suspend fun consumeSlowBody(fixedDeadline: Long?, received: MutableList<Int>,
        socketTimeout: Long? = null, chunkDelay: Long = 100L, cancellationAfter: Long? = null): Result<Unit> {
        val executor = Executors.newSingleThreadExecutor()
        val server = HttpServer.create(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0), 0)
        server.executor = executor
        server.createContext("/warm") { exchange ->
            exchange.sendResponseHeaders(200, 4)
            exchange.responseBody.use { it.write("warm".toByteArray()) }
        }
        server.createContext("/stream") { exchange ->
            exchange.sendResponseHeaders(200, 8)
            try { exchange.responseBody.use { body -> repeat(8) { Thread.sleep(chunkDelay); body.write('x'.code); body.flush() } } }
            catch (_: java.io.IOException) { }
            catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
        server.start()
        val client = createPlatformHttpClient {
            configureArtifactDownloadTimeouts()
        }
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            client.get("$base/warm").bodyAsText()
            val consume: suspend () -> Unit = {
                client.prepareGet("$base/stream") { timeout {
                    if (fixedDeadline != null) requestTimeoutMillis = fixedDeadline
                    if (socketTimeout != null) socketTimeoutMillis = socketTimeout
                } }.execute { response ->
                    val channel = response.bodyAsChannel()
                    val bytes = ByteArray(1)
                    while (true) {
                        val count = channel.readAvailable(bytes)
                        if (count < 0) break
                        if (count > 0) received += count
                    }
                }
            }
            return runCatching {
                if (cancellationAfter == null) consume() else withTimeout(cancellationAfter) { consume() }
            }
        } finally {
            client.close()
            server.stop(0)
            executor.shutdownNow()
        }
    }
}
