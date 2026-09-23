package com.hualala.linyu.data

import com.hualala.linyu.model.BillItem
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class TrendRange { LAST_7_DAYS, THIS_MONTH }

data class DailySpend(
    val date: LocalDate,
    val amount: Double,
    val count: Int
)

data class SpendingSummary(
    val points: List<DailySpend>,
    val total: Double,
    val count: Int,
    val average: Double
)

object SpendingAnalytics {
    fun summarize(
        bills: List<BillItem>,
        range: TrendRange,
        today: LocalDate? = null,
        zoneId: ZoneId = BillDateParser.defaultZoneId()
    ): SpendingSummary {
        val targetDay = today ?: LocalDate.now(zoneId)
        val start = when (range) {
            TrendRange.LAST_7_DAYS -> targetDay.minusDays(6)
            TrendRange.THIS_MONTH -> targetDay.withDayOfMonth(1)
        }
        val byDate = bills.mapNotNull { bill ->
            val dto = bill.consumeBillDTO
            val timestamp = BillDateParser.toEpochMillis(dto.consumeDate, zoneId)
            if (timestamp == 0L) return@mapNotNull null
            val date = Instant.ofEpochMilli(timestamp).atZone(zoneId).toLocalDate()
            val amount = dto.consumeMoney.toDoubleOrNull()
                ?.takeIf { it.isFinite() && it >= 0.0 }
                ?: return@mapNotNull null
            if (date < start || date > targetDay) return@mapNotNull null
            date to amount
        }.groupBy({ it.first }, { it.second })

        val points = generateSequence(start) { date ->
            date.plusDays(1).takeIf { it <= targetDay }
        }.map { date ->
            val amounts = byDate[date].orEmpty()
            DailySpend(date, amounts.sum(), amounts.size)
        }.toList()

        val total = points.sumOf(DailySpend::amount)
        val count = points.sumOf(DailySpend::count)
        return SpendingSummary(
            points = points,
            total = total,
            count = count,
            average = if (count == 0) 0.0 else total / count
        )
    }
}
