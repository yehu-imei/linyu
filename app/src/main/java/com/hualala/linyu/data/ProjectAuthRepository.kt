package com.hualala.linyu.data

import com.google.gson.Gson
import com.hualala.linyu.api.*
import com.hualala.linyu.model.DeviceInfo
import com.hualala.linyu.utils.AppLogger
import com.hualala.linyu.utils.PrefsHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 按登录用户隔离的项目账户与设备元数据，不保存 token；关阀清理活跃订单后仍可结算。 */
object ProjectAuthRepository {
    private val gson = Gson()
    private val refreshMutex = Mutex()
    private var cache: ProjectRoutingSnapshot? = null
    private var lastAttemptToken = ""
    private var lastAttemptAt = 0L

    @Synchronized private fun current(session: ProjectAuthContext): ProjectRoutingSnapshot {
        cache?.takeIf { it.belongsTo(session) }?.let { return it }
        val restored = runCatching {
            gson.fromJson(PrefsHelper.projectRoutingJson, ProjectRoutingSnapshot::class.java)?.takeIf {
                it.belongsTo(session) && it.accounts.all { (p, a) -> p.toLong() > 0 && a.toLong() > 0 } &&
                    it.devices.all { (sn, d) -> sn.isNotBlank() && sn == d.snCode }
            }
        }.getOrNull()
        return (restored ?: ProjectRoutingSnapshot(session.owner)).also { cache = it }
    }

    @Synchronized private fun save(value: ProjectRoutingSnapshot, session: ProjectAuthContext) {
        requireCurrent(session)
        cache = value
        if (!PrefsHelper.usingPlaintextFallback) PrefsHelper.projectRoutingJson = gson.toJson(value)
    }

    @Synchronized fun clear() {
        cache = null
        lastAttemptToken = ""
        lastAttemptAt = 0
    }

    fun requireCurrent(session: ProjectAuthContext) {
        if (session.loginCode.isEmpty() || NetworkModule.session().loginCode != session.loginCode ||
            PrefsHelper.loginCode != session.loginCode) throw ProjectAuthException("登录状态已变化，请重新登录")
    }

    fun rememberDevice(device: DeviceInfo, session: ProjectAuthContext = NetworkModule.session()) {
        if (device.snCode.isBlank()) return
        synchronized(this) {
            val old = current(session)
            save(old.copy(devices = old.devices + (device.snCode to device)), session)
        }
    }

    suspend fun refreshAccounts(session: ProjectAuthContext = NetworkModule.session()) = refreshMutex.withLock {
        requireCurrent(session)
        // 一轮附近扫描的多台设备共用一次请求；失败也短暂退避，避免接口风暴。
        val now = System.currentTimeMillis()
        if (lastAttemptToken == session.loginCode && now - lastAttemptAt < 15_000) return@withLock
        lastAttemptToken = session.loginCode
        lastAttemptAt = now
        try {
            val response = NetworkModule.apiService.getUserProjectsSafe(session.fields())
            requireCurrent(session)
            if (response.success && response.data != null) synchronized(this) {
                save(current(session).copy(accounts = projectAccountMap(response.data, session)), session)
                AppLogger.i("项目账户已同步（${response.data.size} 项）")
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { AppLogger.w("项目账户同步失败，保留已有映射") }
    }

    /** 默认账户和同一登录用户已同步的其他项目账户。 */
    fun knownAccounts(session: ProjectAuthContext): List<ProjectAuthContext> {
        requireCurrent(session)
        val accounts = synchronized(this) { current(session).accounts }
        return listOf(session) + accounts.keys.filter { it != session.projectId }.map {
            session.forProject(it, accounts)
        }
    }

    suspend fun forDevice(snCode: String, device: DeviceInfo? = null): ProjectAuthContext {
        val session = NetworkModule.session()
        requireCurrent(session)
        if (device != null) {
            if (device.snCode != snCode) throw ProjectAuthException("设备信息不一致，请刷新后重试")
            rememberDevice(device, session)
        }
        var info = synchronized(this) { current(session).devices[snCode] }
        val active = PrefsHelper.getActiveOrders().firstOrNull { it.snCode == snCode }
        if (active?.authOwner != null && active.authOwner != session.owner) {
            throw ProjectAuthException("此订单属于其他账户，请切回原账户")
        }
        // 旧版订单/上次设备没有项目字段：先按 MAC 查档案，不能拿默认项目猜测空闲。
        if (info == null && active?.projectId == null) {
            val mac = active?.deviceMac?.takeIf { it.isNotBlank() }
                ?: PrefsHelper.lastDeviceMac.takeIf { PrefsHelper.lastDeviceSnCode == snCode }
                ?: snCode.takeIf { it.replace(":", "").matches(Regex("[0-9A-Fa-f]{12}")) }
            if (!mac.isNullOrBlank()) {
                info = DeviceInfoCache.load(mac).data?.takeIf { it.snCode == snCode }
                requireCurrent(session)
                info?.let { rememberDevice(it, session) }
            }
        }
        val project = deviceProject(session, info, active)
        var accounts = synchronized(this) { current(session).accounts }
        if (project != session.projectId && accounts[project] == null) {
            refreshAccounts(session)
            accounts = synchronized(this) { current(session).accounts }
        }
        requireCurrent(session)
        val context = session.forProject(project, accounts)
        if (active?.authOwner == session.owner && active.accountId != null && active.accountId != context.accountId) {
            throw ProjectAuthException("订单账户已变化，请在官方 App 核对")
        }
        return context
    }

    fun knownContext(snCode: String): ProjectAuthContext? = runCatching {
        val session = NetworkModule.session()
        requireCurrent(session)
        val saved = synchronized(this) { current(session) }
        val info = saved.devices[snCode] ?: return null
        val project = info.projectId?.takeIf { it > 0 }?.toString()
            ?: if (!info.isDrinkingWater) session.projectId else return null
        session.forProject(project, saved.accounts)
    }.getOrNull()

    suspend fun forPending(item: PendingSettlement): ProjectAuthContext {
        if (item.projectId == null || item.accountId == null || item.authOwner == null) return forDevice(item.snCode)
        val session = NetworkModule.session()
        requireCurrent(session)
        if (item.authOwner != session.owner) throw ProjectAuthException("此账单属于其他账户")
        var accounts = synchronized(this) { current(session).accounts }
        if (item.projectId != session.projectId && accounts[item.projectId] == null) {
            refreshAccounts(session)
            accounts = synchronized(this) { current(session).accounts }
        }
        requireCurrent(session)
        return session.forProject(item.projectId, accounts).also {
            if (it.accountId != item.accountId) throw ProjectAuthException("账单账户已变化")
        }
    }
}
