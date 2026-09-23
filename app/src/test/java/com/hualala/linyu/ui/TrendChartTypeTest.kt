package com.hualala.linyu.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class TrendChartTypeTest {
    @Test
    fun `exposes only bar and line chart types`() {
        assertEquals(
            listOf(TrendChartType.BAR, TrendChartType.LINE),
            TrendChartType.entries.toList()
        )
    }
}
