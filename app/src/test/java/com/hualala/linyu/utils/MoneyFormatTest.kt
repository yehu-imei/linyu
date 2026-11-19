package com.hualala.linyu.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class MoneyFormatTest {
    @Test
    fun billAmountsAlwaysDisplayTwoDecimalsOrThreeWhenNeeded() {
        assertEquals("-¥ 1.90", MoneyFormat.billAmount("1.9"))
        assertEquals("-¥ 2.00", MoneyFormat.billAmount("2"))
        assertEquals("-¥ 2.10", MoneyFormat.billAmount("2.100"))
        assertEquals("-¥ 0.125", MoneyFormat.billAmount("0.125"))
    }

    @Test
    fun unknownAmountsAreNotShownAsZero() {
        assertEquals("¥ —", MoneyFormat.billAmount(null))
        assertEquals("¥ —", MoneyFormat.billAmount("NaN"))
    }
}
