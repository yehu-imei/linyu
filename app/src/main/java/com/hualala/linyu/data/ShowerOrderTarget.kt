package com.hualala.linyu.data

import android.content.Intent
import com.hualala.linyu.api.NetworkModule
import com.hualala.linyu.model.ActiveOrder
import com.hualala.linyu.model.BaseResponse
import com.hualala.linyu.model.OrderStatus
import com.hualala.linyu.utils.PrefsHelper

/** Identity captured for an existing order, including orders whose number arrives late. */
data class ShowerOrderTarget(
    val snCode: String,
    val orderNo: String,
    val startedAtMs: Long,
    val owner: String
) {
    internal fun matches(order: ActiveOrder?, localStartedAt: Long, currentOwner: String): Boolean {
        if (order == null || snCode != order.snCode || owner != currentOwner ||
            (order.authOwner != null && order.authOwner != owner)) return false
        if (startedAtMs > 0 && startedAtMs != localStartedAt) return false
        return if (orderNo.isNotBlank()) orderNo == order.orderNo else startedAtMs > 0
    }

    internal fun sameOrderAs(other: ShowerOrderTarget): Boolean {
        if (snCode != other.snCode || owner != other.owner) return false
        if (startedAtMs > 0 && other.startedAtMs > 0 && startedAtMs != other.startedAtMs) return false
        if (orderNo.isNotBlank() && other.orderNo.isNotBlank()) return orderNo == other.orderNo
        return startedAtMs > 0 && startedAtMs == other.startedAtMs
    }

    internal fun currentOrder(): ActiveOrder? {
        if (!PrefsHelper.isLoggedIn) return null
        val order = ShowerController.activeOrderFor(snCode)
        return order?.takeIf { matches(it, PrefsHelper.getStartedAt(snCode), NetworkModule.session().owner) }
    }

    internal fun putInto(intent: Intent) {
        intent.putExtra(SN, snCode)
        intent.putExtra(ORDER, orderNo)
        intent.putExtra(STARTED, startedAtMs)
        intent.putExtra(OWNER, owner)
    }

    internal companion object {
        private const val SN = "linyu_shower_sn"
        private const val ORDER = "linyu_shower_order"
        private const val STARTED = "linyu_shower_started"
        private const val OWNER = "linyu_shower_owner"

        fun fromIntent(intent: Intent): ShowerOrderTarget? {
            val sn = intent.getStringExtra(SN)?.takeIf { it.isNotBlank() } ?: return null
            val owner = intent.getStringExtra(OWNER)?.takeIf { it.isNotBlank() } ?: return null
            val order = intent.getStringExtra(ORDER).orEmpty()
            val started = intent.getLongExtra(STARTED, 0L)
            return ShowerOrderTarget(sn, order, started, owner).takeIf { order.isNotBlank() || started > 0 }
        }

        fun capture(order: ActiveOrder): ShowerOrderTarget? {
            if (!PrefsHelper.isLoggedIn) return null
            val owner = NetworkModule.session().owner
            val startedAt = PrefsHelper.getStartedAt(order.snCode)
            val target = ShowerOrderTarget(order.snCode, order.orderNo, startedAt, owner)
            return target.takeIf { it.matches(order, startedAt, owner) }
        }

        fun capture(snCode: String): ShowerOrderTarget? =
            ShowerController.activeOrderFor(snCode)?.let { capture(it) }
    }
}

internal sealed interface CloudOpenStep {
    data class Resume(val orderNo: String) : CloudOpenStep
    data object Occupied : CloudOpenStep
    data object RequestNew : CloudOpenStep
    data object InvalidRestore : CloudOpenStep
    data object FailedStatus : CloudOpenStep
}

internal fun BaseResponse<OrderStatus>.cloudOpenStep(snCode: String, restoreOrderNo: String?): CloudOpenStep {
    if (deviceInUse == true) {
        val orderNo = ownedOrderNo(snCode) ?: return CloudOpenStep.Occupied
        if (restoreOrderNo != null && restoreOrderNo != orderNo) return CloudOpenStep.InvalidRestore
        return CloudOpenStep.Resume(orderNo)
    }
    if (!success) return CloudOpenStep.FailedStatus
    return if (restoreOrderNo == null) CloudOpenStep.RequestNew else CloudOpenStep.InvalidRestore
}

internal fun usesBluetoothForStop(snCode: String, sessionSnCode: String?, deviceUsesBluetooth: Boolean): Boolean =
    snCode == sessionSnCode || deviceUsesBluetooth
