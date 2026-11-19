package com.hualala.linyu.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CloudCloseConfirmationTest {
    @Test fun `confirmed idle returns immediately`() = runBlocking {
        var reads = 0
        var pauses = 0
        assertTrue(CloudCloseConfirmation.confirm(pause = { pauses++ }) { reads++; false })
        assertEquals(1, reads)
        assertEquals(0, pauses)
    }
    @Test fun `accepted but still running never reports stopped`() = runBlocking {
        var reads = 0
        assertFalse(CloudCloseConfirmation.confirm(pause = {}) { reads++; true })
        assertEquals(9, reads)
    }
    @Test fun `unknown account or network status is not idle`() = runBlocking {
        assertFalse(CloudCloseConfirmation.confirm(pause = {}) { null })
    }
    @Test fun `server transition after first three reads completes in the same attempt`() = runBlocking {
        val replies = ArrayDeque(listOf(true, true, null, true, false))
        var pauses = 0
        assertTrue(CloudCloseConfirmation.confirm(pause = { pauses++ }) { replies.removeFirst() })
        assertTrue(replies.isEmpty())
        assertEquals(4, pauses)
    }
    @Test fun `cancellation is not converted to closed`() {
        assertThrows(CancellationException::class.java) {
            runBlocking { CloudCloseConfirmation.confirm(pause = {}) { throw CancellationException() } }
        }
    }
}
