package com.hualala.linyu.model

data class ActiveOrder(
    val snCode: String,
    val orderNo: String,
    val deviceName: String,
    val deviceMac: String,
    val deviceEmoji: String,
    val preDeduct: Double,
    val projectId: String? = null
)
