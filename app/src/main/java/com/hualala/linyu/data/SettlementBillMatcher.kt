package com.hualala.linyu.data

import com.hualala.linyu.model.BillItem

internal fun BillItem.confirmedSettlementAmount(): Double? =
    consumeBillDTO.consumeMoney?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }

/** 账单占位、查询失败和成功查到空列表各有不同含义。 */
internal data class SettlementBillProbe(
    val amount: Double? = null,
    val sawBill: Boolean = false,
    val lookupFailed: Boolean = false
) {
    fun afterLookup(succeeded: Boolean, matched: BillItem? = null): SettlementBillProbe = when {
        !succeeded -> copy(lookupFailed = true)
        matched == null -> this
        else -> copy(amount = matched.confirmedSettlementAmount() ?: amount, sawBill = true)
    }

    fun resolvedAmount(directZero: Boolean): Double? =
        amount ?: 0.0.takeIf { directZero && !sawBill && !lookupFailed }
}

/** 在正确项目的账单中匹配当前订单；不把另一台设备的最近消费冒充本次消费。 */
internal fun matchSettlementBill(
    bills: List<BillItem>, orderNo: String, deviceName: String, fromMs: Long, untilMs: Long
): BillItem? {
    if (orderNo.isNotBlank()) {
        return bills.firstOrNull { it.consumeBillDTO.orderNo == orderNo }
            ?: bills.firstOrNull { it.consumeBillDTO.orderId == orderNo }
    }
    if (fromMs <= 0 || deviceName.isBlank()) return null
    return bills.filter {
        it.consumeBillDTO.displayDesc == deviceName &&
            BillDateParser.toEpochMillis(it.consumeBillDTO.consumeDate) in fromMs..untilMs
    }.singleOrNull()
}
