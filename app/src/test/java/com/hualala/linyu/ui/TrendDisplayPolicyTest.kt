package com.hualala.linyu.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrendDisplayPolicyTest {
    @Test
    fun `dense chart keeps endpoints and limits visible points`() {
        val indices = TrendDisplayPolicy.pointIndices(31)

        assertTrue(indices.contains(0))
        assertTrue(indices.contains(30))
        assertTrue(indices.size <= 14)
    }

    @Test
    fun `axis labels are evenly distributed`() {
        assertEquals(listOf(0, 7, 15, 22, 30), TrendDisplayPolicy.axisIndices(31))
    }
}
