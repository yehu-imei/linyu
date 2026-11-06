package com.hualala.linyu.data

import com.hualala.linyu.model.BillDTO
import com.hualala.linyu.model.BillItem
import com.hualala.linyu.model.DeviceInfo
import org.junit.Assert.*
import org.junit.Test

class ConsumptionCachePolicyTest {
    private val raw = "热水表-北苑-3号楼-1层-320房"
    private val name = DeviceInfo.formatDeviceName(raw)
    private fun bill(raw: String = this.raw, date: String = "2026-11-06 12:00:00", amount: String = "1.25") =
        BillItem(BillDTO("id", date, amount, "设备:$raw"))
    private fun latest(bills: List<BillItem>) =
        ConsumptionCachePolicy.latestForDevice(bills, raw, name, "卫生间热水器")

    @Test fun `find matching device even when first bill belongs to another device`() {
        val result = latest(listOf(bill("热水表-其他学校-1号楼-320房", amount = "9.00"), bill()))
        assertEquals(1.25, result!!.amount, 0.001)
    }

    @Test fun `select newest matching valid date regardless of server order`() {
        val result = latest(listOf(bill(date = "2026-11-01 12:00:00", amount = "2.00"), bill()))
        assertEquals(BillDateParser.toEpochMillis("2026-11-06 12:00:00"), result!!.atMs)
    }

    @Test fun `same formatted room on different floors must not match`() {
        val another = "热水表-北苑-3号楼-2层-320房"
        assertEquals(name, DeviceInfo.formatDeviceName(another))
        assertNull(latest(listOf(bill(another))))
    }

    @Test fun `legacy display match requires both name and type`() {
        assertNotNull(ConsumptionCachePolicy.latestForDevice(listOf(bill()), "", name, "卫生间热水器"))
        assertNull(ConsumptionCachePolicy.latestForDevice(listOf(bill()), "", name, "洗手台热水器"))
        assertNull(ConsumptionCachePolicy.latestForDevice(listOf(bill()), "", name, ""))
    }

    @Test fun `old and equal time bills do not overwrite newly settled consumption`() {
        assertFalse(ConsumptionCachePolicy.shouldUpdate(9.0, 99, 100))
        assertFalse(ConsumptionCachePolicy.shouldUpdate(9.0, 100, 100))
        assertTrue(ConsumptionCachePolicy.shouldUpdate(1.0, 101, 100))
    }

    @Test fun `zero negative nonfinite or invalid date amounts never erase prior consumption`() {
        listOf("0", "-1", "NaN", "Infinity", "not-money").forEach {
            assertNull(latest(listOf(bill(amount = it))))
        }
        assertNull(latest(listOf(bill(date = "invalid"))))
        assertFalse(ConsumptionCachePolicy.shouldUpdate(1.0, 0, 0))
    }
}
