package com.hualala.linyu.data

import com.hualala.linyu.model.BaseResponse
import com.hualala.linyu.model.CampusUserInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class CampusBalanceUpdateTest {
    @Test
    fun `signed balance accepts zero and a successful amount without a status`() {
        val zero = BaseResponse(true, CampusUserInfo(amount = "0.00", signStatus = 1))
        val legacy = BaseResponse(true, CampusUserInfo(amount = "16.76"))
        assertEquals(CampusBalanceUpdate.Set(0.0), BalanceEstimator.campusBalanceUpdate(zero))
        assertEquals(CampusBalanceUpdate.Set(16.76), BalanceEstimator.campusBalanceUpdate(legacy))
    }

    @Test
    fun `successful response without a usable signed balance clears the cache`() {
        val cases = listOf(
            null,
            CampusUserInfo(signStatus = 1),
            CampusUserInfo(amount = "16.76", signStatus = 0),
            CampusUserInfo(amount = "NaN", signStatus = 1)
        )
        cases.forEach {
            assertEquals(CampusBalanceUpdate.Clear, BalanceEstimator.campusBalanceUpdate(BaseResponse(true, it)))
        }
    }

    @Test
    fun `temporary failure never replaces or clears a cached balance`() {
        val response = BaseResponse(false, CampusUserInfo(amount = "16.76", signStatus = 1), errorCode = 1)
        assertEquals(CampusBalanceUpdate.Keep, BalanceEstimator.campusBalanceUpdate(response))
    }

    @Test
    fun `unsupported campus payment config clears the cache even with a data payload`() {
        val response = BaseResponse(
            false, CampusUserInfo(amount = "16.76"), errorCode = 12, errorMessage = "无效支付配置"
        )
        assertEquals(CampusBalanceUpdate.Clear, BalanceEstimator.campusBalanceUpdate(response))
    }
}
