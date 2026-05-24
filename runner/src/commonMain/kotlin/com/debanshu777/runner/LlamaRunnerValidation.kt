package com.debanshu777.runner

internal fun validateLoadModelArgs(modelPath: String) {
    require(modelPath.isNotBlank()) { "modelPath must not be blank" }
}
