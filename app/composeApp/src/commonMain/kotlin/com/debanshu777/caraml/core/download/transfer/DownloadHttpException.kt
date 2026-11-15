package com.debanshu777.caraml.core.download.transfer

class DownloadHttpException(
    val statusCode: Int,
) : Exception("Model download request failed")


class EmptyDownloadException : Exception("Model download returned no content")
