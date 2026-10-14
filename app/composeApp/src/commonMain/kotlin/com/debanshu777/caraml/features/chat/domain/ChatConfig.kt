package com.debanshu777.caraml.features.chat.domain

data class ChatConfig(
    val topModelCount: Int = 3,
    val lastExchangeCount: Int = 2,
    val fallbackSummaryMessageCount: Int = 4,
    val fallbackSummaryCharLimit: Int = 100,
)
