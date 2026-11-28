package com.hualala.linyu.data

import com.hualala.linyu.model.ActiveOrder
import com.hualala.linyu.model.BaseResponse
import com.hualala.linyu.model.OrderStatus
import org.junit.Assert.*
import org.junit.Test

class ShowerOrderTargetTest {
    private val owner = "user:project:account"
    private val order = ActiveOrder("A", "order-a", "A", "001122334455", "", 5.0, authOwner = owner)
    private val target = ShowerOrderTarget(order.snCode, order.orderNo, 100L, owner)

    @Test fun `restore with an ended original order never authorizes opening`() {
        val idle = BaseResponse<OrderStatus>(success = true, data = null)
        assertEquals(CloudOpenStep.InvalidRestore, idle.cloudOpenStep("A", "order-a"))
        assertEquals(CloudOpenStep.InvalidRestore, idle.cloudOpenStep("A", ""))
        assertEquals(CloudOpenStep.RequestNew, idle.cloudOpenStep("A", null))
    }

    @Test fun `restore requires the original verified order on this device`() {
        val active = BaseResponse(success = true, data = OrderStatus("order-a", snCode = "A", isOwner = true))
        assertEquals(CloudOpenStep.Resume("order-a"), active.cloudOpenStep("A", "order-a"))
        assertEquals(CloudOpenStep.InvalidRestore, active.cloudOpenStep("A", "old-order"))
        assertEquals(CloudOpenStep.InvalidRestore, active.cloudOpenStep("A", ""))
        assertEquals(CloudOpenStep.Occupied, active.cloudOpenStep("B", "order-a"))
        assertEquals(CloudOpenStep.FailedStatus, active.copy(success = false).cloudOpenStep("A", "order-a"))
    }

    @Test fun `a BLE session for A does not change the stop channel for cloud B`() {
        assertTrue(usesBluetoothForStop("A", "A", false))
        assertFalse(usesBluetoothForStop("B", "A", false))
        assertTrue(usesBluetoothForStop("B", "A", true))
        assertFalse(usesBluetoothForStop("B", null, false))
    }

    @Test fun `old completion only matches its own page and order`() {
        assertTrue(target.matches(order, 100L, owner))
        assertFalse(target.matches(order, 200L, owner))
        assertFalse(target.matches(order.copy(orderNo = "new-order"), 100L, owner))
        assertFalse(target.matches(order, 100L, "other-user"))
        assertFalse(target.sameOrderAs(target.copy(snCode = "B")))
        assertFalse(target.sameOrderAs(target.copy(orderNo = "new-order", startedAtMs = 200L)))
        assertTrue(target.copy(orderNo = "").sameOrderAs(target))
        assertFalse(target.copy(orderNo = "").sameOrderAs(target.copy(startedAtMs = 200L)))
    }
}
