package com.hualala.linyu.data

import com.hualala.linyu.api.ProjectAuthContext
import com.hualala.linyu.model.BaseResponse
import com.hualala.linyu.model.OrderStatus

/** 已准备写入费率、但尚未确认的蓝牙订单。独立于活跃订单。 */
internal data class PendingBleOpen(
    val session: BleShowerController.Session,
    val startedAt: Long
) {
    fun matchesDevice(owner: String, context: ProjectAuthContext, sn: String, address: String): Boolean =
        session.authOwner == owner && session.projectId == context.projectId &&
            session.accountId == context.accountId && session.snCode == sn &&
            session.mac.equals(address, ignoreCase = true)

    fun identifiesRunningOrder(state: Int, server: BaseResponse<OrderStatus>): Boolean =
        state == 1 && session.orderNo.isNotBlank() && server.ownedOrderNo(session.snCode) == session.orderNo
}
