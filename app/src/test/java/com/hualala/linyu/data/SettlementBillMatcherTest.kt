package com.hualala.linyu.data

import com.hualala.linyu.model.BillDTO
import com.hualala.linyu.model.BillItem
import org.junit.Assert.*
import org.junit.Test

class SettlementBillMatcherTest {
    private val at = BillDateParser.toEpochMillis("2026-11-11 12:00:00")
    private val bill = BillItem(BillDTO("1", "2026-11-11 12:00:00", "0.15", "饮水机:测试楼", "O1"))

    @Test fun `known order does not borrow another device bill`() {
        assertNull(matchSettlementBill(listOf(bill), "missing", "测试楼", at - 1000, at + 1000))
    }
    @Test fun `exact order works regardless of display name`() {
        assertEquals(bill, matchSettlementBill(listOf(bill), "O1", "", 0, 0))
    }
    @Test fun `no order number requires unique device and bounded time`() {
        assertEquals(bill, matchSettlementBill(listOf(bill), "", "测试楼", at - 1000, at + 1000))
        assertNull(matchSettlementBill(listOf(bill), "", "另一栋楼", at - 1000, at + 1000))
        assertNull(matchSettlementBill(listOf(bill), "", "测试楼", at + 1000, at + 2000))
        assertNull(matchSettlementBill(listOf(bill), "", "测试楼", at - 2000, at - 1000))
        assertNull(matchSettlementBill(listOf(bill), "", "", at - 1000, at + 1000))
    }
    @Test fun `ambiguous bills remain pending`() {
        val second = BillItem(bill.consumeBillDTO.copy(orderId = "2", orderNo = "O2"))
        assertNull(matchSettlementBill(listOf(bill, second), "", "测试楼", at - 1000, at + 1000))
    }

    @Test fun `failed poll preserves placeholder bill evidence and still accepts later amount`() {
        val zero = BillItem(bill.consumeBillDTO.copy(consumeMoney = "0.00"))
        val probe = SettlementBillProbe()
            .afterLookup(succeeded = true, matched = zero)
            .afterLookup(succeeded = false)
            .afterLookup(succeeded = true)

        assertTrue(probe.sawBill)
        assertNull(probe.resolvedAmount(directZero = true))
        assertEquals(0.15, probe.afterLookup(succeeded = true, matched = bill).resolvedAmount(directZero = true) ?: -1.0, 0.0)
    }

    @Test fun `direct zero resolves only when bill lookups succeeded without a match`() {
        val empty = SettlementBillProbe().afterLookup(succeeded = true)
        assertEquals(0.0, empty.resolvedAmount(directZero = true) ?: -1.0, 0.0)
        assertNull(empty.resolvedAmount(directZero = false))
        assertNull(empty.afterLookup(succeeded = false).resolvedAmount(directZero = true))
    }
}
