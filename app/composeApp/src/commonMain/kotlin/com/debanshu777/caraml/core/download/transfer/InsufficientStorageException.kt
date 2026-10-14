package com.debanshu777.caraml.core.download.transfer

class InsufficientStorageException(
    val requiredBytes: Long,
    val availableBytes: Long
) : Exception("Insufficient storage for model download")
