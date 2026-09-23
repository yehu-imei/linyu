package com.hualala.linyu.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class TrendDisplayPolicyTest {
    @Test
    fun `every daily point remains visible in a dense month`() {
        assertEquals((0 until 31).toSet(), TrendDisplayPolicy.visiblePointIndices(31))
    }

    @Test
    fun `axis labels are evenly distributed`() {
        assertEquals(listOf(0, 7, 15, 22, 30), TrendDisplayPolicy.axisIndices(31))
    }

    @Test
    fun `plot positions keep first and last points inside horizontal inset`() {
        assertEquals(5f, TrendDisplayPolicy.plotX(0, 31, 100f, 5f))
        assertEquals(95f, TrendDisplayPolicy.plotX(30, 31, 100f, 5f))
    }
}
