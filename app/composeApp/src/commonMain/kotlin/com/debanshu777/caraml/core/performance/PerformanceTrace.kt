package com.debanshu777.caraml.core.performance

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Trace names are fixed phase labels only: never pass a prompt, path, model ID, or user text. */
expect object PerformanceTrace {
    fun isEnabled(): Boolean
    fun nextCookie(): Int
    fun begin(name: String, cookie: Int)
    fun end(name: String, cookie: Int)
}

private class TraceOperation(val cookie: Int) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<TraceOperation>
}

/** Async spans may finish on a different dispatcher; each span must be closed exactly once. */
class PerformanceSpan internal constructor(
    private val name: String,
    private val cookie: Int,
    val operationId: Int,
) {
    fun close() = PerformanceTrace.end(name, cookie)
}

suspend fun beginPerformanceSpan(name: String, operationId: Int? = null): PerformanceSpan? {
    if (!PerformanceTrace.isEnabled()) return null
    val cookie = PerformanceTrace.nextCookie()
    val operation = operationId ?: currentCoroutineContext()[TraceOperation]?.cookie ?: cookie
    val label = "CaraML.$name#$operation"
    PerformanceTrace.begin(label, cookie)
    return PerformanceSpan(label, cookie, operation)
}

suspend inline fun <T> tracePerformanceStage(name: String, block: () -> T): T {
    val span = beginPerformanceSpan(name)
    return try { block() } finally { span?.close() }
}

suspend fun currentPerformanceOperationId(): Int? = currentCoroutineContext()[TraceOperation]?.cookie

/** Blocking file checks can be attributed by temporal containment, without thread-local state. */
inline fun <T> tracePerformanceBlockingStage(name: String, block: () -> T): T {
    if (!PerformanceTrace.isEnabled()) return block()
    val cookie = PerformanceTrace.nextCookie()
    val label = "CaraML.$name#$cookie"
    PerformanceTrace.begin(label, cookie)
    return try { block() } finally { PerformanceTrace.end(label, cookie) }
}

/** Adds correlation to nested phases without relying on thread-local state across suspension. */
suspend fun <T> tracePerformanceOperation(name: String, block: suspend () -> T): T {
    if (!PerformanceTrace.isEnabled()) return block()
    val operation = currentCoroutineContext()[TraceOperation] ?: TraceOperation(PerformanceTrace.nextCookie())
    return withContext(operation) { tracePerformanceStage(name) { block() } }
}
