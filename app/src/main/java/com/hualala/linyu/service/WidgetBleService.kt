package com.hualala.linyu.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.hualala.linyu.api.NetworkModule
import com.hualala.linyu.api.getDeviceInfoSafe
import com.hualala.linyu.ble.BleConnectResult
import com.hualala.linyu.ble.BleController
import com.hualala.linyu.data.BleArbiter
import com.hualala.linyu.data.BleShowerController
import com.hualala.linyu.data.ShowerController
import com.hualala.linyu.data.ShowerEvents
import com.hualala.linyu.utils.AppLogger
import com.hualala.linyu.utils.MoneyFormat
import com.hualala.linyu.utils.Notifier
import com.hualala.linyu.utils.PrefsHelper
import com.hualala.linyu.widget.LinYuWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import com.hualala.linyu.data.DeviceInfoCache

/**
 * 小组件的**蓝牙表**开阀/关阀（前台服务）。
 *
 * ## 为什么必须有它
 *
 * 小组件是 RemoteViews，自己做不了 BLE——连接要 Context、要长连接、要权限，
 * 而且连接状态根本塞不进 RemoteViews。所以小组件上点「开始/停止」时，把活交给这个服务，
 * 它调用的是与 App 内**完全相同**的 [BleShowerController] 流程（开阀 / 关阀 + 结算）。
 *
 * ## 会话怎么传
 *
 * 蓝牙关阀要靠会话里的 `mac / protocolType / randomNumber`。App 内存里的 session
 * 服务读不到，所以开阀成功后写进 [PrefsHelper.bleSessionJson]，关阀时再读出来——
 * 这也是「App 已退出，小组件仍能关阀」的关键。
 *
 * ## 没有会话时
 *
 * 退回 [ShowerWatchService.finishShower]（沿用原有路径，它会把蓝牙表请求转发给 App）。
 */
class WidgetBleService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson = Gson()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 冷启动进来的话 PrefsHelper / NetworkModule / AppLogger 都还没初始化
        ensureInit()
        val action = intent?.action
        val sn = intent?.getStringExtra(EXTRA_SNCODE)?.takeIf { it.isNotEmpty() }
            ?: PrefsHelper.lastDeviceSnCode
        if (action == null || sn.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }

        // ⚠️ startForegroundService 起的服务，5 秒内必须挂前台通知；否则被系统直接杀。
        // 挂完再按用户的通知开关决定要不要摘掉（同 ShowerWatchService 的做法）。
        startForeground(Notifier.ID_IN_USE, Notifier.showStopping(this, deviceName(sn)))
        if (!PrefsHelper.notifyEnabled || !PrefsHelper.notifyInUse) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }

        scope.launch {
            try {
                when (action) {
                    ACTION_OPEN -> doOpen(sn, intent)
                    ACTION_CLOSE -> doClose(sn)
                }
            } catch (t: Throwable) {
                AppLogger.e("小组件蓝牙操作异常（$action）", t)
            } finally {
                LinYuWidget.refreshAll(this@WidgetBleService)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ── 开阀 ──
    private suspend fun doOpen(snCode: String, intent: Intent) {
        val name = deviceName(snCode)
        val mac = intent.getStringExtra(EXTRA_MAC)?.takeIf { it.isNotEmpty() }
            ?: PrefsHelper.lastDeviceMac
        if (mac.isEmpty()) {
            Notifier.showOpenFailed(this, name, "缺少设备信息，请先在 App 里连接一次该设备")
            return
        }
        val device = runCatching { DeviceInfoCache.load(mac).data }.getOrNull()
        if (device == null) {
            Notifier.showOpenFailed(this, name, "获取设备信息失败，请检查网络")
            return
        }

        // 蓝牙通道互斥：App 可能正在操作同一台设备（两边同时 connectGatt 会互抢连接）
        if (!BleArbiter.begin(snCode, "widget")) {
            Notifier.showOpenFailed(this, name, "该设备正在处理其他蓝牙操作，请稍候再试")
            return
        }
        val outcome = try {
            BleShowerController.openValve(
                ble = BleController(this),
                device = device,
                loginCode = PrefsHelper.loginCode,
                telephone = PrefsHelper.telephone
            )
        } finally {
            BleArbiter.end(snCode)
        }
        when (outcome) {
            is BleShowerController.OpenOutcome.Opened -> {
                PrefsHelper.bleSessionJson = gson.toJson(outcome.session)
                PrefsHelper.markBleDevice(snCode)
                PrefsHelper.lastDeviceSnCode = snCode
                PrefsHelper.lastDeviceMac = mac
                AppLogger.i("小组件蓝牙开阀成功 $snCode")
                Notifier.showInUse(this, snCode, device.displayName, System.currentTimeMillis())
                ShowerWatchService.start(this, snCode)
            }
            is BleShowerController.OpenOutcome.Resumed -> {
                PrefsHelper.bleSessionJson = gson.toJson(outcome.session)
                PrefsHelper.markBleDevice(snCode)
                AppLogger.i("小组件蓝牙恢复用水 $snCode")
                Notifier.showInUse(this, snCode, device.displayName, PrefsHelper.getStartedAt(snCode))
                ShowerWatchService.start(this, snCode)
            }
            // 已补结算上一笔遗留账单——提示用户重新点一次开阀
            is BleShowerController.OpenOutcome.Recovered ->
                Notifier.showOpenFailed(this, name, outcome.message)
            is BleShowerController.OpenOutcome.NotBleDevice -> {
                PrefsHelper.unmarkBleDevice(snCode)
                Notifier.showOpenFailed(this, name, "这台设备不支持蓝牙控制")
            }
            is BleShowerController.OpenOutcome.Failed ->
                Notifier.showOpenFailed(this, name, outcome.message)
            is BleShowerController.OpenOutcome.Unconfirmed ->
                Notifier.showOpenFailed(this, name, outcome.message)
            null -> Notifier.showOpenFailed(this, name, "蓝牙开阀失败，请重试")
        }
    }

    // ── 关阀（不依赖 App 进程）──
    private suspend fun doClose(snCode: String) {
        val name = deviceName(snCode)
        val session = runCatching {
            gson.fromJson(PrefsHelper.bleSessionJson, BleShowerController.Session::class.java)
        }.getOrNull()

        if (session == null || session.snCode != snCode) {
            // 没有会话（App 还没连过 / 会话已清理）→ 退回原有路径
            AppLogger.w("小组件蓝牙关阀缺少会话，退回 ShowerWatchService $snCode")
            ShowerWatchService.finishShower(this, snCode)
            return
        }

        // ⚠️ App 还活着（MainViewModel 存活）时，它握着内存里那条 BLE 连接；
        // 小组件再 connectGatt 会和它抢。转交 App 用它的连接关阀更稳。
        if (BleArbiter.appAlive) {
            AppLogger.i("小组件蓝牙关阀：App 存活，转交 App 处理 $snCode")
            ShowerEvents.notifyStopBleRequest(snCode)
            return
        }

        // 蓝牙通道互斥（App 不在，但可能仍有别的蓝牙操作在跑）
        if (!BleArbiter.begin(snCode, "widget")) {
            Notifier.showCloseFailed(this, name, "该设备正在处理其他蓝牙操作，请稍候再试")
            return
        }

        val elapsed = ShowerController.elapsedSeconds(snCode)
        val outcome = try {
            val ble = BleController(this)
            val conn = try {
                ble.connect(session.mac)
            } catch (t: Throwable) {
                AppLogger.e("小组件蓝牙连接异常", t)
                null
            }
            if (conn !is BleConnectResult.Ready) {
                AppLogger.w("小组件蓝牙连接失败：$conn")
                Notifier.showCloseFailed(this, name, "蓝牙连接失败，请靠近设备后重试")
                return
            }
            BleShowerController.closeAndSettle(
                ble = ble,
                session = session,
                loginCode = PrefsHelper.loginCode,
                telephone = PrefsHelper.telephone
            )
        } finally {
            BleArbiter.end(snCode)
        }
        when (outcome) {
            is BleShowerController.SettleOutcome.Completed -> {
                PrefsHelper.bleSessionJson = ""
                ShowerController.markFinished(snCode)
                // 服务端金额单位是**厘**，换算成元再交给通知（显示 3 位小数的准确值）
                val money = MoneyFormat.milliToYuan(outcome.rawUpMoney)
                // 立即记一笔「上次消费」，否则桌面卡片要等下次拉账单才更新
                if (money != null && money > 0) PrefsHelper.recordConsume(snCode, money)
                AppLogger.i("小组件蓝牙关阀+结算完成 $snCode 金额=$money")
                Notifier.showFinished(this, name, elapsed, money)
            }
            else -> {
                AppLogger.w("小组件蓝牙关阀未完成：$outcome")
                Notifier.showCloseFailed(this, name, "关闭设备未确认，请打开 App 重试")
            }
        }
    }

    private fun deviceName(snCode: String): String =
        (if (PrefsHelper.lastDeviceSnCode == snCode) PrefsHelper.lastDeviceName else "")
            .ifEmpty { PrefsHelper.lastDeviceName.ifEmpty { "热水器" } }

    private fun ensureInit() {
        if (!PrefsHelper.isInitialized) PrefsHelper.init(applicationContext)
        AppLogger.init(applicationContext)
        NetworkModule.restoreFromPrefs()
    }

    companion object {
        const val EXTRA_SNCODE = "snCode"
        const val EXTRA_MAC = "mac"
        private const val ACTION_OPEN = "com.hualala.linyu.service.WIDGET_BLE_OPEN"
        private const val ACTION_CLOSE = "com.hualala.linyu.service.WIDGET_BLE_CLOSE"

        /** 小组件请求开阀（蓝牙表） */
        fun open(context: Context, snCode: String, mac: String) {
            val i = Intent(context, WidgetBleService::class.java).apply {
                action = ACTION_OPEN
                putExtra(EXTRA_SNCODE, snCode)
                putExtra(EXTRA_MAC, mac)
            }
            ContextCompat.startForegroundService(context, i)
        }

        /** 小组件请求关阀（蓝牙表） */
        fun close(context: Context, snCode: String) {
            val i = Intent(context, WidgetBleService::class.java).apply {
                action = ACTION_CLOSE
                putExtra(EXTRA_SNCODE, snCode)
            }
            ContextCompat.startForegroundService(context, i)
        }
    }
}
