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
}
