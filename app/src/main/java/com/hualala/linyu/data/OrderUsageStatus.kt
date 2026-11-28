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

/** Whether this account's original cloud order still occupies the queried device. */
internal sealed interface OrderPresence {
    data class Original(val orderNo: String) : OrderPresence
    data class Ended(val replacingOrderOwner: Boolean? = null) : OrderPresence
    data object Unknown : OrderPresence
}

internal fun BaseResponse<OrderStatus>.orderPresence(snCode: String, orderNo: String): OrderPresence {
    val responseSn = data?.snCode
    if (!responseSn.isNullOrBlank() && !responseSn.equals(snCode, ignoreCase = true)) {
        return OrderPresence.Unknown
    }
    when (deviceInUse) {
        false -> return OrderPresence.Ended()
        null -> return OrderPresence.Unknown
        true -> Unit
    }
    if (data?.isOwner == false) return OrderPresence.Ended(replacingOrderOwner = false)
    val active = ownedOrderNo(snCode) ?: return OrderPresence.Unknown
    if (orderNo.isNotBlank() && orderNo != active) return OrderPresence.Ended(replacingOrderOwner = true)
    return OrderPresence.Original(active)
}
