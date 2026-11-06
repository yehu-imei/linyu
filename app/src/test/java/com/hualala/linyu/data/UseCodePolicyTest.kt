package com.hualala.linyu.data

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class UseCodePolicyTest {
    private val today = LocalDate.of(2026, 11, 6)

    @Test fun `exhausted quota blocks reopening and another generation`() {
        val remaining = UseCodePolicy.remainingToday(UseCodeQuota(0, today.toString()), today)
        assertEquals(UseCodePolicy.EXHAUSTED_MESSAGE, UseCodePolicy.blockReason(1, remaining))
        assertFalse(UseCodePolicy.canGenerate(remaining, false))
    }

    @Test fun `last generated code can still be claimed when quota reaches zero`() {
        assertFalse(UseCodePolicy.canGenerate(0, false))
        assertTrue(UseCodePolicy.canClaim("12345123", 180, false))
        assertFalse(UseCodePolicy.canClaim("12345123", 0, false))
        assertFalse(UseCodePolicy.canClaim(null, 180, false))
        assertFalse(UseCodePolicy.canClaim("12345123", 180, true))
    }

    @Test fun `quota expires at next local calendar day`() {
        assertNull(UseCodePolicy.remainingToday(UseCodeQuota(0, today.minusDays(1).toString()), today))
        assertNull(UseCodePolicy.remainingToday(UseCodeQuota(0, "invalid"), today))
        assertTrue(UseCodePolicy.canGenerate(null, false))
        assertNull(UseCodePolicy.blockReason(null, null))
    }

    @Test fun `server exhaustion learned on other clients is persisted`() {
        assertEquals(UseCodeQuota(0, today.toString()), UseCodePolicy.quotaFromResponse(null, 79, today))
        assertNull(UseCodePolicy.quotaFromResponse(null, 12, today))
        assertNull(UseCodePolicy.quotaFromResponse(null, 0, today))
        assertEquals(UseCodeQuota(19, today.toString()), UseCodePolicy.quotaFromResponse(19, 0, today))
    }

    @Test fun `daily claim limit and concurrent request both block generation`() {
        assertEquals("每天只能领取一次使用码", UseCodePolicy.blockReason(0, 20))
        assertFalse(UseCodePolicy.canGenerate(20, true))
        assertTrue(UseCodePolicy.canGenerate(1, false))
    }
}
