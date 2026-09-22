package com.hualala.linyu.data

import com.hualala.linyu.model.ActiveOrder

enum class CloseDisposition {
    COMPLETE,
    RESTORE_FAILED,
    RESTORE_UNCONFIRMED
}

fun closeDisposition(outcome: CloseOutcome): CloseDisposition = when (outcome) {
    is CloseOutcome.Closed -> CloseDisposition.COMPLETE
    is CloseOutcome.Failed -> CloseDisposition.RESTORE_FAILED
    is CloseOutcome.Unconfirmed -> CloseDisposition.RESTORE_UNCONFIRMED
}

fun monitorSerials(orders: List<ActiveOrder>): List<String> =
    orders.map(ActiveOrder::snCode).filter(String::isNotBlank).distinct()

fun canLogoutVoluntarily(orders: List<ActiveOrder>): Boolean = orders.isEmpty()
