package com.debanshu777.huggingfacemanager.download

class DownloadHttpException(
    val statusCode: Int,
) : Exception("Model download request failed")

class DownloadCommitException : Exception("Model download could not be finalized")

class EmptyDownloadException : Exception("Model download returned no content")
