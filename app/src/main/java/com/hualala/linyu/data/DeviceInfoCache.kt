package com.hualala.linyu.data

import com.hualala.linyu.api.NetworkModule
import com.hualala.linyu.api.getDeviceInfoSafe
import com.hualala.linyu.model.BaseResponse
import com.hualala.linyu.model.DeviceInfo

/**
 * `/device/info/mac` 的短 TTL 缓存。
 *
 * ## 为什么需要
 *
 * 公共澡堂场景里扫描结果可能几十上百台设备，每台都会被查一次设备信息；`getDeviceInfoSafe`
 * 又是同步打网络的，5 秒内能发出上百次请求，而且**绝大多数返回 null**（不是本校区的表）。
 * 设备信息（deviceId / snCode / MAC / 类型）基本是静态的，加一层缓存即可把重复请求清零。
 *
 * TTL 取 60 秒：足以覆盖一次扫描 + 一次开阀，又不至于让改名之类的变更长期不生效。
 * [load] 的 `force = true` 用于确实需要最新值的场合。
 */
object DeviceInfoCache {

    private const val TTL_MS = 60_000L
    private const val MAX_ENTRIES = 64

    private data class Entry(val at: Long, val info: DeviceInfo)

    private val map = LinkedHashMap<String, Entry>()

    @Synchronized
    private fun cached(mac: String): DeviceInfo? {
        val e = map[mac] ?: return null
        if (System.currentTimeMillis() - e.at > TTL_MS) {
            map.remove(mac)
            return null
        }
        return e.info
    }

    @Synchronized
    private fun store(mac: String, info: DeviceInfo) {
        // 简单 LRU：超限就丢最早插入的那个
        if (map.size >= MAX_ENTRIES) {
            map.keys.firstOrNull()?.let { map.remove(it) }
        }
        map[mac] = Entry(System.currentTimeMillis(), info)
    }

    /**
     * 查设备信息（命中缓存则不打网络）。
     *
     * 返回类型与原 [getDeviceInfoSafe] 一致，调用方只需把 `NetworkModule.apiService.getDeviceInfoSafe(x)`
     * 换成 `DeviceInfoCache.load(x)`，错误处理（`checkKick(resp.displayMessage)` 等）完全不用动。
     *
     * ## 为什么要按候选逐个试
     *
     * 我们手上只有安卓扫描到的**空口广播地址**，而台账里存的是设备**真实的** MAC，
     * 两者对部分设备不是一个字符串（只差首字节最高两位，见 [MacFormat]）。
     * 按原样查会稳定拿到 `success=true + data=null`——
     * 注意这不是"报错"：`errorMessage` 是「成功」，所以 [com.hualala.linyu.ui.MainViewModel]
     * 里的 `checkKick` 不会有任何反应，用户看到的是**点了设备卡片什么都没发生**：
     * 拿不到 snCode → 设备详情弹窗不弹 → 走不到开关页面。
     *
     * 所以按 [MacFormat.lookupVariants] 依次尝试，第一个拿到 data 的即返回。
     * 第一个候选永远是原样，对本来就查得到的学校**零额外开销**。
     *
     * @param force 强制刷新（忽略缓存）
     */
    suspend fun load(mac: String, force: Boolean = false): BaseResponse<DeviceInfo> {
        if (mac.isEmpty()) return BaseResponse(false, null)
        if (!force) cached(mac)?.let { return BaseResponse(true, it) }

        var first: BaseResponse<DeviceInfo>? = null
        for ((index, candidate) in MacFormat.lookupVariants(mac).withIndex()) {
            val resp = NetworkModule.apiService.getDeviceInfoSafe(candidate)
            if (index == 0) first = resp
            resp.data?.let {
                store(mac, it)
                return resp
            }
            // 服务端**明确报错**（如被挤下线）换写法也是同样的错，原样返回交给上层 checkKick；
            // 只有「查无此设备」（success=true + data=null）才值得换一种写法再试
            if (!resp.success) return resp
        }
        return first ?: BaseResponse(true, null)
    }

    /** 清空（退出登录 / 换账号时调用，避免读到上一任账号的设备） */
    @Synchronized
    fun clear() = map.clear()
}
