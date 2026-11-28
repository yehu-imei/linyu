package com.hualala.linyu.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ManualBalanceTest {
    @Test fun `stored baselines are isolated by user project and account`() {
        val a = ManualBalance.key("1:2:3")!!
        val b = ManualBalance.key("9:2:3")!!
        val baseline = ManualBalance.create("20.10", 10)!!
        val stored = mutableMapOf("manualBalance" to "99", a to baseline.encode())
        assertNull(stored[b])
        stored[b] = ManualBalance.create("0", 20)!!.encode()
        assertEquals(baseline, ManualBalance.decode(stored.getValue(a)))
        assertNotEquals(a, ManualBalance.key("1:4:3"))
        assertNotEquals(a, ManualBalance.key("1:2:4"))
        assertNull(ManualBalance.key("1::3"))
        assertNull(ManualBalance.key("1:0:3"))
    }

    @Test fun `invalid values are unknown and only later entries are charged`() {
        for (value in listOf("9|NaN", "9|Infinity", "bad|20", "0|20", "9|")) {
            assertNull(ManualBalance.decode(value))
        }
        val balance = ManualBalance.decode("10|20.10")!!
        assertEquals(18.20, balance.estimate(listOf(9L to 1.0, 10L to 2.0, 11L to 1.9)), 0.00001)
    }
}
