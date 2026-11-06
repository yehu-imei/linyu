package com.hualala.linyu.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BleSettlementCleanupTest {
    @Test fun `write ack does not prove idle`() = runBlocking {
        for (state in listOf(1, 3, 6, null)) {
            assertFalse(BleSettlementCleanup.confirm({}, { state }))
        }
    }
    @Test fun `readback follows write and proves idle`() = runBlocking {
        val calls = mutableListOf<String>()
        assertTrue(BleSettlementCleanup.confirm({ calls += "write" }, { calls += "query"; 0 }))
        assertEquals(listOf("write", "query"), calls)
    }
    @Test fun `lost write ack can still be confirmed idle`() = runBlocking {
        assertTrue(BleSettlementCleanup.confirm({ error("lost ack") }, { 0 }))
    }
    @Test fun `query failure remains unconfirmed`() = runBlocking {
        assertFalse(BleSettlementCleanup.confirm({}, { error("disconnected") }))
    }
    @Test fun `missing clear data does not invent success`() = runBlocking {
        assertFalse(BleSettlementCleanup.confirm({}, { 3 }))
        assertTrue(BleSettlementCleanup.confirm({}, { 0 }))
    }
    @Test fun `cancelled write never queries another session`() = runBlocking {
        var queried = false
        try {
            BleSettlementCleanup.confirm({ throw CancellationException() }, { queried = true; 0 })
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertFalse(queried)
    }
    @Test fun `cancelled query does not become success`() = runBlocking {
        try {
            BleSettlementCleanup.confirm({}, { throw CancellationException() })
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
    }
    @Test fun `accepted upload preserves amount even without device cleanup`() {
        val result: BleShowerController.SettleOutcome = BleShowerController.SettleOutcome.Uploaded(1840, false)
        assertTrue(result is BleShowerController.SettleOutcome.Uploaded)
        assertEquals(1840, (result as BleShowerController.SettleOutcome.Uploaded).rawUpMoney)
        assertFalse(result.recordCleared)
    }
}
