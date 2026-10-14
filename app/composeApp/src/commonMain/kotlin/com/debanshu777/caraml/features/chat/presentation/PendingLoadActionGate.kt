package com.debanshu777.caraml.features.chat.presentation

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

@OptIn(ExperimentalAtomicApi::class)
internal class PendingLoadActionGate {
    private val consumed = AtomicBoolean(true)

    fun open() {
        consumed.store(false)
    }

    fun close() {
        consumed.store(true)
    }

    fun tryConsume(): Boolean = consumed.compareAndSet(expectedValue = false, newValue = true)
}
