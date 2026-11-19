package com.hualala.linyu.data

import com.hualala.linyu.model.BaseResponse
import com.hualala.linyu.model.OrderStatus

/** 307 能确认设备被占用，查询失败时则不能确认设备是否空闲。 */
internal val BaseResponse<OrderStatus>.deviceInUse: Boolean?
    get() = when {
        errorCode == 307 || (success && data?.orderNo != null) -> true
        success -> false
        else -> null
    }

/** 只有明确归属当前账户、且设备与订单号都能对上的响应可供恢复。 */
internal fun BaseResponse<OrderStatus>.ownedOrderNo(expectedSnCode: String): String? {
    val order = data ?: return null
    if (deviceInUse != true || order.isOwner != true) return null
    if (!order.snCode.isNullOrBlank() && !order.snCode.equals(expectedSnCode, ignoreCase = true)) return null
    return order.orderNo?.takeIf { it.isNotBlank() }
}
