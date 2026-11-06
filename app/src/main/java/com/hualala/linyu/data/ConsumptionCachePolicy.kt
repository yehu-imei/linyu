package com.hualala.linyu.data

import com.hualala.linyu.model.BillItem

data class ConsumptionSnapshot(val amount: Double, val atMs: Long)

/** 账单列表没有 SN，只在完整原名或“显示名 + 类型”明确匹配时回填。 */
object ConsumptionCachePolicy {
    fun shouldUpdate(amount: Double, atMs: Long, cachedAtMs: Long): Boolean =
        amount.isFinite() && amount > 0 && atMs > 0 && atMs > cachedAtMs

    fun latestForDevice(
        bills: List<BillItem>, rawName: String, displayName: String, typeName: String
    ): ConsumptionSnapshot? = bills.mapNotNull { bill ->
        val dto = bill.consumeBillDTO
        val exactRaw = rawName.isNotBlank() && dto.description.substringAfter(':').trim() == rawName.trim()
        val sameDisplayAndType = displayName.isNotBlank() && typeName.isNotBlank() &&
            dto.displayDesc == displayName && dto.deviceTypeLabel == typeName
        // 有原名时不降级成短名匹配，避免不同楼层同房号被格式化成同一名称。
        if (!(if (rawName.isNotBlank()) exactRaw else sameDisplayAndType)) return@mapNotNull null
        val amount = dto.consumeMoney.toDoubleOrNull() ?: return@mapNotNull null
        val atMs = BillDateParser.toEpochMillis(dto.consumeDate)
        if (!shouldUpdate(amount, atMs, 0)) return@mapNotNull null
        ConsumptionSnapshot(amount, atMs)
    }.maxByOrNull { it.atMs }
}
