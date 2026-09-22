package com.hualala.linyu.data

import com.hualala.linyu.model.ActiveOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShowerSafetyPolicyTest {
    @Test
    fun `已关闭结果才进入完成流程`() {
        val outcome = CloseOutcome.Closed(startTimeMs = 1_000L)

        assertEquals(CloseDisposition.COMPLETE, closeDisposition(outcome))
    }

    @Test
    fun `明确失败结果要求恢复失败状态`() {
        val outcome = CloseOutcome.Failed(message = "关闭失败", kickHint = null)

        assertEquals(CloseDisposition.RESTORE_FAILED, closeDisposition(outcome))
    }

    @Test
    fun `未确认结果要求恢复未确认状态`() {
        val outcome = CloseOutcome.Unconfirmed(
            startTimeMs = 1_000L,
            message = "无法确认设备状态"
        )

        assertEquals(CloseDisposition.RESTORE_UNCONFIRMED, closeDisposition(outcome))
    }

    @Test
    fun `监控序列号过滤空值并按首次出现顺序去重`() {
        val orders = listOf(
            activeOrder("device-b"),
            activeOrder(""),
            activeOrder("device-a"),
            activeOrder("device-b"),
            activeOrder("   ")
        )

        assertEquals(listOf("device-b", "device-a"), monitorSerials(orders))
    }

    @Test
    fun `两个活跃设备都进入监控恢复集合`() {
        val orders = listOf(activeOrder("device-a"), activeOrder("device-b"))

        assertEquals(listOf("device-a", "device-b"), monitorSerials(orders))
    }

    @Test
    fun `没有活跃订单时允许主动退出`() {
        assertTrue(canLogoutVoluntarily(emptyList()))
    }

    @Test
    fun `存在任一活跃订单时阻止主动退出`() {
        assertFalse(canLogoutVoluntarily(listOf(activeOrder("device-a"))))
    }

    @Test
    fun `只有完成处置可以宣布使用结束`() {
        assertTrue(CloseDisposition.COMPLETE.mayAnnounceFinished)
        assertFalse(CloseDisposition.RESTORE_FAILED.mayAnnounceFinished)
        assertFalse(CloseDisposition.RESTORE_UNCONFIRMED.mayAnnounceFinished)
    }

    @Test
    fun `强制下线只保留活跃用水恢复键`() {
        val recoveryKeys = listOf(
            "activeOrders",
            "lastDeviceName",
            "lastDeviceRawName",
            "lastDeviceMac",
            "lastDeviceSnCode",
            "lastDeviceEmoji",
            "lastDeviceTypeName",
            "lastDeviceWithholdMoney",
            "startedAt_device-a",
            "autoDiscon_device-a"
        )
        val accountKeys = listOf(
            "loginCode",
            "userId",
            "accountId",
            "telephone",
            "widgetBillJson",
            "consume_device-a"
        )

        assertTrue(recoveryKeys.all(::shouldPreserveForRecovery))
        assertTrue(accountKeys.none(::shouldPreserveForRecovery))
    }

    private fun activeOrder(snCode: String) = ActiveOrder(
        snCode = snCode,
        orderNo = "order-$snCode",
        deviceName = "测试设备",
        deviceMac = "00:00:00:00:00:00",
        deviceEmoji = "",
        preDeduct = 0.0
    )
}
