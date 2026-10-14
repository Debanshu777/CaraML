package com.debanshu777.huggingfacemanager.download

class InsufficientStorageException(
    val requiredBytes: Long,
    val availableBytes: Long
) : Exception("Insufficient storage for model download")
