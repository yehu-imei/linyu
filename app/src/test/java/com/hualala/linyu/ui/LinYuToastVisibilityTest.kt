package com.hualala.linyu.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinYuToastVisibilityTest {
    @Test
    fun `cleared message hides a toast whose timer is still visible`() {
        assertFalse(isToastVisible(timerVisible = true, message = null))
        assertFalse(isToastVisible(timerVisible = true, message = ""))
    }

    @Test
    fun `non-empty message follows timer visibility`() {
        assertTrue(isToastVisible(timerVisible = true, message = "操作成功"))
        assertFalse(isToastVisible(timerVisible = false, message = "操作成功"))
    }
}
