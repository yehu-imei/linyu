package com.hualala.linyu.data

import com.hualala.linyu.model.BillItem
import java.time.LocalDate

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
        today: LocalDate = LocalDate.now()
    ): SpendingSummary {
        val start = when (range) {
            TrendRange.LAST_7_DAYS -> today.minusDays(6)
            TrendRange.THIS_MONTH -> today.withDayOfMonth(1)
        }
        val byDate = bills.mapNotNull { bill ->
            val dto = bill.consumeBillDTO
            val date = BillDateParser.parseLocalDateTime(dto.consumeDate)?.toLocalDate()
                ?: return@mapNotNull null
            val amount = dto.consumeMoney.toDoubleOrNull()
                ?.takeIf { it.isFinite() && it >= 0.0 }
                ?: return@mapNotNull null
            if (date < start || date > today) return@mapNotNull null
            date to amount
        }.groupBy({ it.first }, { it.second })

        val points = generateSequence(start) { date ->
            date.plusDays(1).takeIf { it <= today }
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
