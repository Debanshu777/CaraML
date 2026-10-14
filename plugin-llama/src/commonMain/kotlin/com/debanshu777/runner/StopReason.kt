package com.debanshu777.runner

object StopReason {
    const val NONE = 0
    const val EOG = 1
    const val MAX_TOKENS = 2
    const val CONTEXT_FULL = 3
    const val CANCELLED = 4
    const val ERROR = 5
}
