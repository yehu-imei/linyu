package com.hualala.linyu.data

import com.google.gson.Gson
import com.hualala.linyu.model.BaseResponse
import com.hualala.linyu.model.OrderStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderUsageStatusTest {
    @Test
    fun `absent null and false owner flags do not confirm ownership`() {
        val gson = Gson()
        val rows = listOf(
            """{"orderNo":"123"}""",
            """{"orderNo":"123","isOwner":null}""",
            """{"orderNo":"123","isOwner":false}"""
        )
        for (row in rows) {
            val status = gson.fromJson(row, OrderStatus::class.java)
            val response = BaseResponse(success = true, data = status)
            assertTrue(response.deviceInUse == true)
            assertNull(response.ownedOrderNo("SN-1"))
        }
    }

    @Test
    fun `307 with missing payload or blank number is only occupancy`() {
        val missing = BaseResponse<OrderStatus>(success = false, data = null, errorCode = 307)
        val blank = missing.copy(data = OrderStatus(orderNo = " ", isOwner = true))
        for (response in listOf(missing, blank)) {
            assertTrue(response.deviceInUse == true)
            assertNull(response.ownedOrderNo("SN-1"))
        }
    }

    @Test
    fun `only explicit ownership with a matching device can recover an order`() {
        val owned = BaseResponse(
            success = false, data = OrderStatus("123", snCode = "SN-1", isOwner = true), errorCode = 307
        )
        assertEquals("123", owned.ownedOrderNo("SN-1"))
        assertNull(owned.ownedOrderNo("SN-2"))
        assertNull(owned.copy(errorCode = 500).ownedOrderNo("SN-1"))
        assertEquals(false, BaseResponse<OrderStatus>(success = true, data = null).deviceInUse)
        assertNull(BaseResponse<OrderStatus>(success = false, data = null).deviceInUse)
    }

    @Test
    fun `foreign occupancy ends our original order but not the device use`() {
        val foreign = BaseResponse(success = false, errorCode = 307,
            data = OrderStatus(orderNo = "", isOwner = false))
        assertEquals(true, foreign.deviceInUse)
        assertEquals(OrderPresence.Ended(replacingOrderOwner = false), foreign.orderPresence("SN-1", "original"))
    }

    @Test
    fun `same account replacement does not authorize the old close action`() {
        val owned = BaseResponse(success = false, errorCode = 307,
            data = OrderStatus(orderNo = "new", snCode = "SN-1", isOwner = true))
        assertEquals(OrderPresence.Ended(replacingOrderOwner = true), owned.orderPresence("SN-1", "original"))
        assertEquals(OrderPresence.Original("new"), owned.orderPresence("SN-1", "new"))
        assertEquals(OrderPresence.Unknown, owned.orderPresence("SN-2", "original"))
    }

    @Test
    fun `unknown ownership or malformed foreign response does not end the original order`() {
        val partial = BaseResponse(success = false, errorCode = 307,
            data = OrderStatus(orderNo = "", snCode = "other-device", isOwner = false))
        assertEquals(OrderPresence.Unknown, partial.orderPresence("SN-1", "original"))
        val unknown = BaseResponse<OrderStatus>(success = false, errorCode = 307, data = null)
        assertEquals(OrderPresence.Unknown, unknown.orderPresence("SN-1", "original"))
        val idle = BaseResponse<OrderStatus>(success = true, data = null)
        assertEquals(OrderPresence.Ended(), idle.orderPresence("SN-1", "original"))
    }
}
