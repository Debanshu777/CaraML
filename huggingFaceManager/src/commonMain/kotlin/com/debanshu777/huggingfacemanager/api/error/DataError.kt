package com.debanshu777.huggingfacemanager.api.error

sealed interface DataError : RootError {
    sealed interface Network : DataError {
        data object NoInternet : Network
        data object Serialization : Network
        data object Unauthorized : Network
        data object NotFound : Network
        data object Conflict : Network
        data object RequestTimeout : Network
        data object PayloadTooLarge : Network
        data object ServerError : Network
        data object Unknown : Network
    }
}
