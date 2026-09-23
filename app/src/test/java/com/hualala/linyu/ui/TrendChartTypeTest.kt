package com.hualala.linyu.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrendChartTypeTest {
    @Test
    fun `exposes the three supported chart types`() {
        assertEquals(
            listOf(TrendChartType.BAR, TrendChartType.LINE, TrendChartType.COMBINED),
            TrendChartType.entries.toList()
        )
    }

    @Test
    fun `peak point is retained when dense points are reduced`() {
        val values = listOf(0.0, 1.0, 2.0, 99.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0, 11.0, 12.0, 13.0)
        assertTrue(TrendDisplayPolicy.pointIndices(values.size, values).contains(3))
    }
}
