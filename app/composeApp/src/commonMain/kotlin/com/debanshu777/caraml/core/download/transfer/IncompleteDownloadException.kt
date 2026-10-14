package com.debanshu777.caraml.core.download.transfer

class IncompleteDownloadException(
    val bytesReceived: Long,
    val expectedBytes: Long
) : Exception("Model download was incomplete")
