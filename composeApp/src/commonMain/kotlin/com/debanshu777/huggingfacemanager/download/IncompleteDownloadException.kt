package com.debanshu777.huggingfacemanager.download

class IncompleteDownloadException(
    val bytesReceived: Long,
    val expectedBytes: Long
) : Exception("Model download was incomplete")
