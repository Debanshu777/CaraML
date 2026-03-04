package com.debanshu777.runner

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

fun LlamaRunner.generateFlowTokens(): Flow<String> = flow {
    while (true) {
        val token = nextToken() ?: break
        if (token.isNotEmpty()) {
            emit(token)
        }
    }
}
