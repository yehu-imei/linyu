package com.hualala.linyu.data

import com.hualala.linyu.utils.AppLogger

/**
 * 蓝牙操作的**仲裁器**（进程内单例）。
 *
 * ## 解决什么问题
 *
 * 同一个 App 进程里有两条入口都会操作蓝牙水表：
 * - App 界面（`MainViewModel.startBleShower` / `stopBleShower`）
 * - 桌面小组件的服务（`WidgetBleService`）
 *
 * 两者都会 `connectGatt` 到同一台设备。同一时刻两边一起连，会互相抢连接——
 * 表现为「一边操作成功、另一边超时失败」，甚至阀开了却连不上导致关不掉。
 *
 * ## 怎么用
 *
 * 任何要走 BLE 的流程，进入前 [begin]、退出时 [end]（放 `finally`）：
 *
 * ```kotlin
 * if (!BleArbiter.begin(snCode, "app")) { 提示"正在处理中"; return }
 * try { ... } finally { BleArbiter.end(snCode) }
 * ```
 *
 * ## App 是否活着
 *
 * [appAlive] 由 `MainViewModel` 维护。小组件服务据此判断「App 是不是正握着一条连接」：
 * 若 App 活着且会话还在，关阀就该转交 App 去做（用它的连接），而不是另起一条。
 *
 * ⚠️ 刻意用内存变量而不是 Prefs——服务和 Activity 本就在同一进程（见 `ShowerWatchService`
 * 的说明），没有跨进程需求；跨进程反而要处理文件锁，得不偿失。
 * 时间戳兜底是为了防止某条流程异常退出后永久占着不放。
 */
object BleArbiter {

    /** 占用超时（毫秒）。超过这个时间视为上一次操作已失效，允许后来者接管 */
    private const val TIMEOUT_MS = 120_000L

    /** App 的 MainViewModel 是否存活（存活 ⇒ 它可能持有 BLE 连接） */
    @Volatile
    var appAlive: Boolean = false

    private var holder: String? = null
    private var holderTag: String = ""
    private var since: Long = 0L

    /**
     * 申请占用某台设备的蓝牙通道。
     *
     * @param snCode 设备标识
     * @param owner  占用方（`"app"` / `"widget"`），只用于日志
     * @return true = 拿到，可以继续；false = 已被别人占着，应放弃本次操作
     */
    @Synchronized
    fun begin(snCode: String, owner: String): Boolean {
        val now = System.currentTimeMillis()
        val h = holder
        if (h != null && now - since < TIMEOUT_MS) {
            AppLogger.w("蓝牙操作被拒：$h 正被 $holderTag 占用，本次 $owner 请求 $snCode")
            return false
        }
        holder = snCode
        holderTag = owner
        since = now
        return true
    }

    /** 释放占用（只在自己仍持有的时候才清，避免误放掉别人的） */
    @Synchronized
    fun end(snCode: String) {
        if (holder == snCode) {
            holder = null
            holderTag = ""
            since = 0L
        }
    }

    /** 当前是否有人占着（未超时）；返回被占的设备 snCode */
    @Synchronized
    fun busySnCode(): String? =
        holder?.takeIf { System.currentTimeMillis() - since < TIMEOUT_MS }
}
