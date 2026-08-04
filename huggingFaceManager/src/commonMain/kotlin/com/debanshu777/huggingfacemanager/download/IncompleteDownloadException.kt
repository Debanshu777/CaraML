package com.debanshu777.huggingfacemanager.download

class IncompleteDownloadException(
    val bytesReceived: Long,
    val expectedBytes: Long
) : Exception("Incomplete download: received $bytesReceived of $expectedBytes bytes")
