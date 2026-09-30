package com.hualala.linyu.data

import com.hualala.linyu.model.BillItem
import kotlin.math.abs

data class PendingSettlement(
    val snCode: String,
    val orderNo: String,
    val startedAt: Long,
    val deviceName: String,
    val createdAt: Long,
    /**
     * 用水时长（秒），登记时就定下来。
     *
     * 事后对账补通知时已经拿不到准确的结束时刻了（服务早就收工、界面也退了），
     * 所以得在这儿存一份，否则补发的通知只能写「消费 ¥x.xx」而写不出用时。
     */
    val elapsedSec: Int = 0
)

data class SettlementUpdate(
    val snCode: String,
    val amount: Double
)

data class SettlementReconciliation(
    val updates: List<SettlementUpdate>,
    val remaining: List<PendingSettlement>
)

object SettlementReconciler {
    private const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
    private const val TIME_SLACK_MS = 3L * 60 * 1000

    fun reconcile(
        pending: List<PendingSettlement>,
        bills: List<BillItem>,
        nowMs: Long = System.currentTimeMillis()
    ): SettlementReconciliation {
        val available = bills.toMutableList()
        val updates = mutableListOf<SettlementUpdate>()
        val remaining = mutableListOf<PendingSettlement>()

        pending.forEach { item ->
            if (nowMs - item.createdAt > MAX_AGE_MS) return@forEach

            val matched = if (item.orderNo.isNotBlank()) {
                available.firstOrNull { it.consumeBillDTO.orderNo == item.orderNo }
            } else {
                available
                    .filter { bill ->
                        val dto = bill.consumeBillDTO
                        val billTime = BillDateParser.toEpochMillis(dto.consumeDate)
                        item.deviceName.isNotBlank() &&
                            dto.displayDesc == item.deviceName &&
                            billTime >= item.startedAt - TIME_SLACK_MS &&
                            billTime <= item.createdAt + TIME_SLACK_MS
                    }
                    .minByOrNull { bill ->
                        abs(BillDateParser.toEpochMillis(bill.consumeBillDTO.consumeDate) - item.startedAt)
                    }
            }

            val amount = matched?.consumeBillDTO?.consumeMoney?.toDoubleOrNull()
                ?.takeIf { it.isFinite() && it >= 0.0 }
            if (matched == null || amount == null) {
                remaining += item
                return@forEach
            }

            available.remove(matched)
            if (amount > 0.0) updates += SettlementUpdate(item.snCode, amount)
        }

        return SettlementReconciliation(updates, remaining)
    }
}
