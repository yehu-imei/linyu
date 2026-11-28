package com.hualala.linyu.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginSmsRequestsTest {
    @Test fun `old recipient response leaves the other request and its timeout alone`() {
        val requests = LoginSmsRequests()
        val first = requireNotNull(requests.begin("first", 1, 0L))
        assertEquals(LoginSmsRequests.Status(), requests.status("second", 1_000L))
        val second = requireNotNull(requests.begin("second", 2, 1_000L))

        assertTrue(requests.complete(first, succeeded = false))
        assertEquals(LoginSmsRequests.Status(), requests.status("first", 2_000L))
        assertEquals(LoginSmsRequests.Status(pending = true, secondsLeft = 59), requests.status("second", 2_000L))
        assertTrue(requests.complete(second, succeeded = true))
        assertEquals(LoginSmsRequests.Status(secondsLeft = 59), requests.status("second", 2_000L))
    }

    @Test fun `an old failure cannot be published after editing back or toggling the mode`() {
        val requests = LoginSmsRequests()
        val original = requireNotNull(requests.begin("first", 1, 0L))

        assertTrue(original.matches("first", 1))
        assertFalse(original.matches("second", 2))
        assertFalse(original.matches("first", 3))
    }

    @Test fun `successful cooldown starts at tap and is independent per number`() {
        val requests = LoginSmsRequests()
        val first = requireNotNull(requests.begin("first", 1, 0L))
        assertEquals(60, requests.status("first", 0L).secondsLeft)
        assertNull(requests.begin("first", 1, 20_000L))
        assertTrue(requests.complete(first, succeeded = true))

        assertNotNull(requests.begin("second", 2, 20_000L))
        assertEquals(1, requests.status("first", 59_999L).secondsLeft)
        assertNull(requests.begin("first", 3, 59_999L))
        assertNotNull(requests.begin("first", 3, 60_000L))
    }

    @Test fun `completion from before reset cannot unlock a newer send for the same number`() {
        val requests = LoginSmsRequests()
        val old = requireNotNull(requests.begin("first", 1, 0L))
        requests.reset()
        assertEquals(LoginSmsRequests.Status(), requests.status("first", 1_000L))
        val new = requireNotNull(requests.begin("first", 2, 1_000L))

        assertFalse(requests.complete(old, succeeded = false))
        assertTrue(requests.status("first", 2_000L).pending)
        assertTrue(requests.complete(new, succeeded = false))
        assertNotNull(requests.begin("first", 2, 2_000L))
    }
}
