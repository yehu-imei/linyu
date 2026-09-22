package com.hualala.linyu.data

import com.hualala.linyu.model.BillDTO
import com.hualala.linyu.model.BillItem
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingSettlementTest {
    private val now = epoch("2026-09-22 18:00:00")

    @Test
    fun `order number reconciles the correct device amount`() {
        val pending = listOf(
            pending("device-a", "order-a"),
            pending("device-b", "order-b")
        )

        val result = SettlementReconciler.reconcile(
            pending,
            listOf(bill("order-b", "别的设备", "2026-09-22 17:00:00", "0.72")),
            now
        )

        assertEquals(listOf(SettlementUpdate("device-b", 0.72)), result.updates)
        assertEquals(listOf("device-a"), result.remaining.map { it.snCode })
    }

    @Test
    fun `zero bill completes pending record without overwriting previous amount`() {
        val result = SettlementReconciler.reconcile(
            listOf(pending("device-a", "order-a")),
            listOf(bill("order-a", "测试热水器", "2026-09-22 17:00:00", "0.00")),
            now
        )

        assertTrue(result.updates.isEmpty())
        assertTrue(result.remaining.isEmpty())
    }

    @Test
    fun `unmatched records remain and expired records are removed`() {
        val fresh = pending("device-a", "missing")
        val expired = pending("device-old", "old", createdAt = now - 8L * 24 * 60 * 60 * 1000)

        val result = SettlementReconciler.reconcile(listOf(fresh, expired), emptyList(), now)

        assertEquals(listOf(fresh), result.remaining)
        assertTrue(result.updates.isEmpty())
    }

    @Test
    fun `legacy record can match device name inside start-time window`() {
        val startedAt = epoch("2026-09-22 17:00:05")
        val legacy = PendingSettlement(
            snCode = "device-a",
            orderNo = "",
            startedAt = startedAt,
            deviceName = "测试热水器",
            createdAt = now
        )

        val result = SettlementReconciler.reconcile(
            listOf(legacy),
            listOf(
                bill("", "其他热水器", "2026-09-22 17:00:03", "9.00"),
                bill("", "测试热水器", "2026-09-22 17:00:03", "0.48")
            ),
            now
        )

        assertEquals(listOf(SettlementUpdate("device-a", 0.48)), result.updates)
        assertTrue(result.remaining.isEmpty())
    }

    private fun pending(sn: String, order: String, createdAt: Long = now) = PendingSettlement(
        snCode = sn,
        orderNo = order,
        startedAt = epoch("2026-09-22 17:00:05"),
        deviceName = "测试热水器",
        createdAt = createdAt
    )

    private fun bill(order: String, name: String, date: String, money: String) = BillItem(
        BillDTO(
            orderId = "id-$order",
            consumeDate = date,
            consumeMoney = money,
            description = "设备:$name",
            orderNo = order.ifEmpty { null }
        )
    )

    private fun epoch(value: String): Long = LocalDateTime.parse(
        value,
        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    ).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
}
