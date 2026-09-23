package com.hualala.linyu.data

import com.hualala.linyu.model.BillDTO
import com.hualala.linyu.model.BillItem
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class SpendingAnalyticsTest {

    @Test
    fun `uses explicit zone when converting a bill timestamp`() {
        val bill = bill("2026-09-23 00:30:00", "1.20")

        val summary = SpendingAnalytics.summarize(
            listOf(bill),
            TrendRange.LAST_7_DAYS,
            today = LocalDate.of(2026, 9, 23),
            zoneId = ZoneId.of("America/Los_Angeles")
        )

        assertEquals(1, summary.points.last().count)
        assertEquals(1.20, summary.points.last().amount, 0.001)
    }
    private val today = LocalDate.of(2026, 9, 22)

    @Test
    fun `last seven days include zero days and aggregate same-day bills`() {
        val summary = SpendingAnalytics.summarize(
            listOf(
                bill("2026-09-20 08:00:00", "0.40"),
                bill("2026-09-20 18:00:00", "0.60"),
                bill("2026-09-16 12:00:00", "1.50"),
                bill("2026-09-15 12:00:00", "9.99")
            ),
            TrendRange.LAST_7_DAYS,
            today
        )

        assertEquals(7, summary.points.size)
        assertEquals(LocalDate.of(2026, 9, 16), summary.points.first().date)
        assertEquals(today, summary.points.last().date)
        assertEquals(1.0, summary.points.first { it.date.dayOfMonth == 20 }.amount, 0.0001)
        assertEquals(2, summary.points.first { it.date.dayOfMonth == 20 }.count)
        assertEquals(2.5, summary.total, 0.0001)
        assertEquals(3, summary.count)
        assertEquals(2.5 / 3.0, summary.average, 0.0001)
    }

    @Test
    fun `this month starts on day one and excludes adjacent months`() {
        val summary = SpendingAnalytics.summarize(
            listOf(
                bill("2026-09-01T08:00:00", "1.20"),
                bill("2026-09-22 09:00:00.123", "0.80"),
                bill("2026-08-31 23:59:59", "5.00")
            ),
            TrendRange.THIS_MONTH,
            today
        )

        assertEquals(22, summary.points.size)
        assertEquals(LocalDate.of(2026, 9, 1), summary.points.first().date)
        assertEquals(2.0, summary.total, 0.0001)
        assertEquals(2, summary.count)
    }

    @Test
    fun `invalid dates non-numeric amounts and negative amounts are ignored`() {
        val summary = SpendingAnalytics.summarize(
            listOf(
                bill("bad-date", "1.00"),
                bill("2026-09-22 10:00:00", "bad"),
                bill("2026-09-22 11:00:00", "-1.00"),
                bill("2026-09-22 12:00:00", "0.00")
            ),
            TrendRange.LAST_7_DAYS,
            today
        )

        assertEquals(0.0, summary.total, 0.0001)
        assertEquals(1, summary.count)
        assertEquals(0.0, summary.average, 0.0001)
    }

    private fun bill(date: String, money: String) = BillItem(
        BillDTO(
            orderId = "id-$date-$money",
            consumeDate = date,
            consumeMoney = money,
            description = "设备:测试热水器",
            orderNo = "order-$date-$money"
        )
    )
}
