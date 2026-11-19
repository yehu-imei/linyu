package com.hualala.linyu.data

import com.hualala.linyu.api.ProjectAuthContext
import com.hualala.linyu.api.ProjectAuthException
import com.hualala.linyu.model.ActiveOrder
import com.hualala.linyu.model.DeviceInfo

/** 缓存可跨进程恢复，但只属于同一个登录用户及默认账户；不含登录凭证。 */
internal data class ProjectRoutingSnapshot(
    val owner: String,
    val accounts: Map<String, String> = emptyMap(),
    val devices: Map<String, DeviceInfo> = emptyMap()
) {
    fun belongsTo(session: ProjectAuthContext): Boolean = owner == session.owner
}

internal fun deviceProject(session: ProjectAuthContext, info: DeviceInfo?, active: ActiveOrder?): String {
    if (active?.authOwner != null && active.authOwner != session.owner) {
        throw ProjectAuthException("此订单属于其他账户，请切回原账户")
    }
    if (active?.authOwner == session.owner && active.projectId != null &&
        info?.projectId != null && info.projectId > 0 && active.projectId != info.projectId.toString()) {
        throw ProjectAuthException("设备所属项目已变化，请在官方 App 核对订单")
    }
    return info?.projectId?.takeIf { it > 0 }?.toString()
        ?: active?.projectId?.takeIf { active.authOwner == session.owner }
        // 兼容不返回 projectId 的旧淋浴项目；饮水机和旧的无档案设备不猜。
        ?: if (info != null && !info.isDrinkingWater) session.projectId else null
        ?: throw ProjectAuthException("设备项目信息不完整，请刷新设备后重试")
}

internal fun PendingSettlement.matchesContext(defaultSession: ProjectAuthContext, target: ProjectAuthContext): Boolean =
    (authOwner == null || authOwner == defaultSession.owner) &&
        (projectId == null || projectId == target.projectId) &&
        (accountId == null || accountId == target.accountId)
