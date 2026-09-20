package com.hualala.linyu.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hualala.linyu.api.NetworkModule
import com.hualala.linyu.api.getAccountInfoSafe
import com.hualala.linyu.api.getBillListSafe
import com.hualala.linyu.api.getBindCardInfoSafe
import com.hualala.linyu.api.getCampusUserInfoSafe
import com.hualala.linyu.api.getDeviceInfoSafe
import com.hualala.linyu.api.forgetPasswordSafe
import com.hualala.linyu.api.generateUseCodeSafe
import com.hualala.linyu.api.getProjectInfoSafe
import com.hualala.linyu.api.getUseCodeSafe
import com.hualala.linyu.api.queryUnpaidBillsSafe
import com.hualala.linyu.api.requestDeductSafe
import com.hualala.linyu.api.setUseCodeSafe
import com.hualala.linyu.api.getWalletSafe
import com.hualala.linyu.api.queryUsingSafe
import com.hualala.linyu.data.BalanceEstimator
import com.hualala.linyu.data.CloseOutcome
import com.hualala.linyu.data.OpenOutcome
import com.hualala.linyu.data.ShowerController
import com.hualala.linyu.data.ShowerEvents
import com.hualala.linyu.service.ShowerWatchService
import com.hualala.linyu.widget.LinYuWidget
import com.hualala.linyu.model.AccountInfo
import com.hualala.linyu.model.ActiveOrder
import com.hualala.linyu.model.CachedBill
import com.hualala.linyu.model.CachedDevice
import com.hualala.linyu.model.UseCodeData
import com.hualala.linyu.model.BillItem
import com.hualala.linyu.model.DeviceInfo
import com.hualala.linyu.model.MqttOrderMsg
import com.hualala.linyu.model.NearbyDevice
import com.hualala.linyu.model.UnpaidBill
import com.hualala.linyu.model.WalletData
import com.hualala.linyu.utils.AppLogger
import com.hualala.linyu.utils.BluetoothScanner
import com.hualala.linyu.utils.MqttManager
import com.hualala.linyu.utils.Notifier
import com.hualala.linyu.utils.PrefsHelper
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 开阀失败的原因分类。
 *
 * ⚠️ 界面**只对 [SERVER_REJECTED] 引导去补扣**。把网络异常、设备被占用、结果未知
 * 也归到「有欠费」里，用户就会因为一次 WiFi 掉线被引导去真扣钱。
 */
enum class ShowerErrorKind {
    /** 服务端明确拒绝（欠费、账户异常…）——**只有这一类**和未扣账单有关 */
    SERVER_REJECTED,
    /** 设备正被别人用着 */
    IN_USE_BY_OTHERS,
    /** 开阀结果未知（预算耗尽没确认上） */
    UNKNOWN_RESULT,
    /** 网络异常 */
    NETWORK,
    /** 设备信息不完整 */
    BAD_DEVICE,
}

/** 开阀失败：[message] 给用户看，[kind] 决定界面要不要引导去补扣 */
data class ShowerError(val message: String, val kind: ShowerErrorKind)

class MainViewModel : ViewModel() {

    var walletInfo by mutableStateOf<WalletData?>(null)
    val nearbyDevices = mutableStateListOf<NearbyDevice>()
    var isScanning by mutableStateOf(false)
    var scanStartTime by mutableStateOf(0L)

    var isShowering by mutableStateOf(false)
    var isStartingShower by mutableStateOf(false)
    var isStopping by mutableStateOf(false)
    var showerRemaining by mutableStateOf("0.00")
    var showerConsumed by mutableStateOf(0.0)
    var showerPreDeduct by mutableStateOf(0.0)
    var showerElapsedSec by mutableStateOf(0)
    var autoDisConSec by mutableStateOf(0)   // 自动关停剩余秒数，0 = 未知
    var showerError by mutableStateOf<ShowerError?>(null)
    var toastMessage by mutableStateOf<String?>(null)

    // ── 自动关停确认弹窗 ──
    var showAutoCloseDialog by mutableStateOf(false)
    var autoCloseDeviceName by mutableStateOf("")
    var autoCloseElapsed by mutableStateOf(0)
    /** 自动关停那笔的金额。**null = 没拿到**，和 0.0（确实没花钱）不是一回事 */
    var autoCloseConsumed by mutableStateOf<Double?>(null)
    var autoCloseLoading by mutableStateOf(false)

    var kickedOut by mutableStateOf(false)

    var lastDeviceName by mutableStateOf("")
    var lastDeviceMac by mutableStateOf("")
    var lastDeviceSnCode by mutableStateOf("")
    var lastDeviceEmoji by mutableStateOf("🚿")

    var selectedDevice by mutableStateOf<DeviceInfo?>(null)
    var showDeviceDetail by mutableStateOf(false)
    var isOwner by mutableStateOf(true)

    // ── 多设备活跃订单 ──
    val activeOrders = mutableStateListOf<ActiveOrder>()

    // 用于当前洗澡的设备 snCode
    private var showerSnCode: String? = null

    private var currentOrderNo: String? = null
    private var activeDeviceSnCodes = mutableSetOf<String>()
    private val fetchingMacs = mutableSetOf<String>()
    private val gson = Gson()
    private var scanner: BluetoothScanner? = null
    private var mqttManager: MqttManager? = null
    private var timerJob: Job? = null
    private var orderPollJob: Job? = null
    private var kickWatchJob: Job? = null

    /**
     * 会话级协程作用域。
     *
     * 所有"发请求"的任务都挂在这上面，而不是直接挂 viewModelScope——
     * 这样被挤号 / 退出登录时可以把它们**整体取消**。
     *
     * 为什么必须整体取消：这些请求是拿旧 loginCode 发出去的，
     * 响应可能在用户已经重新登录之后才回来，里面写着"登录失效"。
     * 若不掐断，`checkKick` 会拿旧会话的失败去清掉**新会话**的凭证，
     * 表现就是"被挤下线后重新登录，刚进去又被弹出来"。
     */
    private var sessionJob = SupervisorJob(viewModelScope.coroutineContext[Job])
    private fun sessionScope() = CoroutineScope(viewModelScope.coroutineContext + sessionJob)

    init {
        // 用水结束由 ShowerWatchService 检测（它会清理本地状态并发通知），
        // 这里只负责界面收尾：同步内存状态 + 弹确认框。
        // App 不在前台时这个事件没人收，也没关系——服务那边已经处理完了。
        viewModelScope.launch {
            ShowerEvents.finished.collect { onShowerFinishedFromService(it) }
        }
        // 关阀未确认时服务会把活跃订单**放回去**（见 restoreActiveOrder）。那件事发生在
        // `finished` 之后，而上面那个处理已经调 `finishShower` 把设备从内存里删了——
        // 不在这里重新读一次 Prefs，内存和 Prefs 就会一直对不上。
        viewModelScope.launch {
            ShowerEvents.ordersRestored.collect {
                syncActiveOrdersFromPrefs()
                refreshWidgets()
            }
        }
    }

    // ── 账单 ──
    var billList by mutableStateOf<List<BillItem>>(emptyList())
    var isLoadingBills by mutableStateOf(false)

    /**
     * 账单是否**至少成功拉取过一次**。
     *
     * 余额是「用户填的初始余额 − 账单里的消费」算出来的。账单到位之前 billList 是空的，
     * 减数为 0，于是界面上会先亮出那个初始值、等账单到了再跳到真实值——
     * 看着就像余额自己变了一下（用户复现的「10.60 跳变」就是这个）。
     * 所以没加载完之前不显示估算结果，宁可先显示「—」。
     */
    var billsLoaded by mutableStateOf(false)
        private set
    var useCodeData by mutableStateOf<UseCodeData?>(null)
        private set

    /**
     * 使用码接口有没有**成功**拉过一次。
     *
     * 界面靠它区分两种「没有码」：还没拉到（显示「加载中」）和
     * 拉到了但服务端说这人没有码（`useCode` 为 null，显示「尚未领取」）。
     * 没有这个标志的话，没领过码的人会永远停在「加载中」。
     */
    var useCodeLoaded by mutableStateOf(false)
        private set

    /**
     * 账号信息（姓名 / 学号 / 绑定状态）。null = 还没拉到。
     *
     * 存在的意义在于**能触发重组**：直接让界面读 `PrefsHelper.userName` 的话，
     * 拉完数据界面不会自己刷新（Prefs 不是 Compose State）。
     */
    var accountInfo by mutableStateOf<AccountInfo?>(null)
        private set

    /**
     * 一卡通**真实余额**。null = 拿不到（没签约免密支付、或者还没拉过），
     * 这时界面回退到「初始余额 − 账单消费」的本地估算。
     *
     * 初值取 Prefs 里上次拉到的值：从桌面小组件点进账单页会**新建一个 ViewModel**，
     * 等接口回来之前这段时间界面会退回估算、还会冒出「手动填写」按钮，
     * 看着像数据丢了。先亮上次的值，拉到新的再覆盖。
     */
    var campusBalance by mutableStateOf(PrefsHelper.campusBalance.toDoubleOrNull())
        private set

    /**
     * 当前账号的手机号——**界面唯一该读的地方**。
     *
     * 以前是 MainActivity 里一个 `rememberSaveable`，登录成功时写一次就再也不动了，
     * 所以在「我的」页改完手机号，卡片上还显示旧的，得等下次重新登录才更新。
     * 现在换成 ViewModel 里的状态：登录、改号、退出三处都写它，界面自动跟着刷。
     */
    var phone by mutableStateOf(PrefsHelper.telephone)

    /**
     * 学校名（`/project/info/triple` 的 `projectName`）。
     *
     * 同 [accountInfo]：写进 Prefs 不会触发重组，界面得读这个 State 才刷得出来。
     * 初值取 Prefs，老用户不用等接口回来就有值显示。
     */
    var schoolName by mutableStateOf(PrefsHelper.schoolName)
        private set

    /** 存一份 AppContext 供刷新桌面小组件用（ViewModel 不应该长期持有 Activity） */
    private var appContext: Context? = null

    fun initManagers(context: Context) {
        appContext = context.applicationContext
        if (scanner == null) scanner = BluetoothScanner(context, { addDevice(it) }, { ok -> onScanTimeout(ok) })
        if (mqttManager == null) mqttManager = MqttManager(context, { handleMqttMessage(it) })

        lastDeviceName = PrefsHelper.lastDeviceName
        lastDeviceMac = PrefsHelper.lastDeviceMac
        lastDeviceSnCode = PrefsHelper.lastDeviceSnCode
        lastDeviceEmoji = PrefsHelper.lastDeviceEmoji
        boundRoom = PrefsHelper.boundRoom

        // 恢复所有活跃订单
        val saved = PrefsHelper.getActiveOrders()
        activeOrders.clear()
        activeOrders.addAll(saved)
        saved.forEach { activeDeviceSnCodes.add(it.snCode) }
    }

    override fun onCleared() {
        super.onCleared()
        stopTimer()
        sessionJob.cancel()
        mqttManager?.disconnect(); scanner?.stopScan()
    }

    fun startScan() {
        nearbyDevices.clear(); activeDeviceSnCodes.clear()
        isScanning = true; scanStartTime = System.currentTimeMillis(); scanner?.startScan()
    }
    /**
     * 扫描结束。
     *
     * @param ok 是否正常扫完。**只有正常扫完才更新小组件的快照**——
     *           扫描失败（蓝牙被关、适配器异常）时结果为空是"没扫成"，不是"附近没有设备"，
     *           拿它去覆盖会把上次扫到的好数据清空，小组件就白白变成「未发现热水器」了。
     */
    fun onScanTimeout(ok: Boolean) {
        val e = System.currentTimeMillis() - scanStartTime
        if (e < 600) sessionScope().launch { delay(600 - e); isScanning = false }
        else isScanning = false
        if (ok) cacheNearbyForWidget()
    }

    // ── 扫码绑定 ──
    fun scanBind(snCode: String) {
        sessionScope().launch {
            try {
                val resp = NetworkModule.apiService.getDeviceInfoSafe(snCode)
                if (resp.success && resp.data != null) {
                    val info = resp.data
                    // 保存为上次使用设备
                    PrefsHelper.lastDeviceSnCode = info.snCode
                    PrefsHelper.lastDeviceMac = info.macAddress
                    PrefsHelper.lastDeviceName = info.displayName
                    PrefsHelper.lastDeviceRawName = info.deviceName   // 寝室筛选用，见 PrefsHelper
                    PrefsHelper.lastDeviceEmoji = info.typeEmoji
                    // 弹出设备详情
                    selectedDevice = info; showDeviceDetail = true
                    refreshDeviceStatus(info.snCode)
                    // 停止扫描（避免设备详情弹出后列表还在跳）
                    scanner?.stopScan(); isScanning = false
                } else {
                    toastMessage = resp.displayMessage ?: "未找到该设备"
                    checkKick(resp.displayMessage)
                }
            } catch (e: Exception) {
                checkKickEx(e)
                val msg = e.message ?: ""
                toastMessage = if (msg.contains("Unable to resolve host", ignoreCase = true) ||
                    msg.contains("No address associated", ignoreCase = true) ||
                    msg.contains("Network is unreachable", ignoreCase = true) ||
                    msg.contains("Failed to connect", ignoreCase = true)) {
                    "网络连接失败，请检查网络设置"
                } else {
                    "查询设备失败"
                }
            }
        }
    }

    // ── 点击设备 ──
    fun fetchDeviceInfo(mac: String) {
        val cached = nearbyDevices.find { it.mac == mac }?.deviceInfo
        if (cached != null) {
            selectedDevice = cached; showDeviceDetail = true
            sessionScope().launch { refreshDeviceStatus(cached.snCode) }
            return
        }
        sessionScope().launch {
            try {
                val resp = NetworkModule.apiService.getDeviceInfoSafe(mac)
                if (resp.success && resp.data != null) {
                    selectedDevice = resp.data; showDeviceDetail = true
                    refreshDeviceStatus(resp.data.snCode)
                } else checkKick(resp.displayMessage)
            } catch (e: Exception) {
                checkKickEx(e)
                val msg = e.message ?: ""
                if (msg.contains("Unable to resolve host", ignoreCase = true) ||
                    msg.contains("No address associated", ignoreCase = true) ||
                    msg.contains("Network is unreachable", ignoreCase = true) ||
                    msg.contains("Failed to connect", ignoreCase = true)) {
                    toastMessage = "网络连接失败，请检查网络设置"
                }
            }
        }
    }

    private suspend fun refreshDeviceStatus(snCode: String) {
        try {
            val q = NetworkModule.apiService.queryUsingSafe(snCode = snCode, auth = NetworkModule.authFields())
            if (q.errorCode == 307 || (q.success && q.data?.orderNo != null)) {
                activeDeviceSnCodes.add(snCode)
                isOwner = q.data?.isOwner ?: true
                // 只有自己的订单才加入 activeOrders
                if (isOwner && activeOrders.none { it.snCode == snCode }) {
                    val orderNo = q.data?.orderNo ?: ""
                    val deviceInfo = nearbyDevices.find { it.deviceInfo?.snCode == snCode }?.deviceInfo
                    if (deviceInfo != null) {
                        activeOrders.add(ActiveOrder(snCode, orderNo, deviceInfo.displayName, deviceInfo.macAddress, deviceInfo.typeEmoji, deviceInfo.withholdMoney))
                        saveOrders()
                    }
                }
                // 设备上确实有订单，且**是自己的** → 之前那个「占用中」已经不成立了。
                // （不是自己的就是真被占着，标记该留着，不用管。）
                if (isOwner) clearOccupiedIfStale(snCode)
            } else {
                // 服务端说这台上**没有订单**：不管之前记没记过「占用中」，现在都空了。
                isOwner = true
                clearOccupiedIfStale(snCode)
            }
        } catch (_: Exception) {}
    }

    /**
     * 这台设备已经不归「占用中」管了，就把标记清掉并让桌面跟着刷。
     *
     * 补的是这么一条路径：小组件上点开阀被拒 → 桌面记下「占用中」→ 用户打开 App
     * 点进设备详情 → `refreshDeviceStatus` 已经查到设备空了 → **但小组件那边没人通知**，
     * 桌面会一直挂着「占用中」，直到用户再去点一次小组件的开水按钮。
     *
     * 现在 App 这边一旦确认设备空了就顺手把它抹掉。`PrefsHelper` 里还有一个
     * 3 分钟的兜底失效，防的是「用户此后既不打开 App、也不点小组件」那种情况。
     */
    private fun clearOccupiedIfStale(snCode: String) {
        if (PrefsHelper.occupiedSnCode != snCode) return
        PrefsHelper.clearOccupied()
        AppLogger.i("设备已空闲（$snCode），清掉小组件的「占用中」标记")
        refreshWidgets()
    }

    fun startLastDevice(phone: String) {
        val sn = lastDeviceSnCode.ifEmpty { return }
        // 找对应的活跃订单
        val order = activeOrders.find { it.snCode == sn }
        if (order != null) {
            sessionScope().launch {
                try {
                    val resp = NetworkModule.apiService.getDeviceInfoSafe(order.deviceMac)
                    if (resp.success && resp.data != null) {
                        selectedDevice = resp.data; startShower(phone)
                    } else {
                        toastMessage = "获取设备信息失败"
                    }
                } catch (e: Exception) {
                    toastMessage = "获取设备信息失败"
                }
            }
            return
        }
        val mac = lastDeviceMac.ifEmpty { return }
        sessionScope().launch {
            try {
                val resp = NetworkModule.apiService.getDeviceInfoSafe(mac)
                if (resp.success && resp.data != null) {
                    selectedDevice = resp.data; startShower(phone)
                } else {
                    toastMessage = "获取设备信息失败"
                }
            } catch (e: Exception) {
                toastMessage = "获取设备信息失败"
            }
        }
    }

    // ════════════════════════════════════════════
    fun startShower(phone: String) {
        val device = selectedDevice ?: return
        val snCode = device.snCode
        if (snCode.isNullOrBlank()) { showerError = ShowerError("设备信息不完整", ShowerErrorKind.BAD_DEVICE); return }

        isStartingShower = true
        sessionScope().launch {
            try {
                showerError = null
                showerSnCode = snCode

                // 「查询是否已有订单 → 开阀 → 轮询确认 → 落盘」这套流程在 ShowerController 里，
                // 桌面小组件也走同一份逻辑；这里只负责 MQTT、界面状态与结果提示
                mqttManager?.connect(phone)
                val outcome = ShowerController.openValve(snCode, device)

                when (outcome) {
                    is OpenOutcome.Resumed -> {
                        syncActiveOrdersFromPrefs()
                        // 恢复订单：从持久化恢复自动关停剩余时间
                        enterShowerState(outcome.orderNo, snCode, device, PrefsHelper.getAutoDisconRemain(snCode))
                    }

                    is OpenOutcome.Opened -> {
                        syncActiveOrdersFromPrefs()
                        enterShowerState(null, snCode, device, outcome.autoDiscon)
                        startOrderPoll(snCode)
                    }

                    /**
                     * 设备被别人用着。
                     *
                     * App 内正常情况下走不到这里——设备详情弹窗早就用 `isOwner`
                     * 把按钮置灰了。留着这个分支是为了：万一以后从别的入口绕进来
                     * （比如扫码头、小组件深链），也不会把别人的订单当成自己的。
                     */
                    is OpenOutcome.InUseByOthers -> {
                        showerError = ShowerError(outcome.message, ShowerErrorKind.IN_USE_BY_OTHERS)
                        mqttManager?.disconnect()
                    }

                    is OpenOutcome.Failed -> {
                        showerError = ShowerError(outcome.message, ShowerErrorKind.SERVER_REJECTED)
                        checkKick(outcome.kickHint)
                        mqttManager?.disconnect()
                    }

                    OpenOutcome.Unknown -> {
                        // App 内调用给了充足预算，理论上不会走到这；保守起见按未确认处理
                        showerError = ShowerError(
                            "开阀未确认成功，请确认热水器是否已开启",
                            ShowerErrorKind.UNKNOWN_RESULT
                        )
                        mqttManager?.disconnect()
                    }
                }
            } catch (e: Exception) {
                checkKickEx(e)
                if (!isShowering) {
                    showerError = ShowerError(e.message ?: "网络错误", ShowerErrorKind.NETWORK)
                }
            } finally { isStartingShower = false }
        }
    }

    /**
     * 桌面上有小组件时刷新一下。
     * 小组件自己不持有状态、每次都从 Prefs 现读，所以这里只要推它重绘即可。
     */
    private fun refreshWidgets() {
        appContext?.let { LinYuWidget.refreshAll(it) }
    }

    /**
     * 把账单快照存给小组件。
     * 小组件自己拉不了账单，2x4 的「账单」页读的就是这份数据，界面上会标同步时间。
     */
    private fun cacheBillsForWidget(recent: List<BillItem>) {
        val list = recent.map { b ->
            CachedBill(
                emoji = b.consumeBillDTO.deviceEmoji,
                name = b.consumeBillDTO.displayDesc,
                timeText = b.consumeBillDTO.consumeDate.take(16),
                moneyText = "-¥ " + b.consumeBillDTO.consumeMoney,
                // 原始值一并存下来：小组件的余额估算要拿它们做减法，
                // 靠上面那两个格式化字符串反解是解不出来的
                rawTimeMs = BalanceEstimator.billTimeMs(b.consumeBillDTO.consumeDate),
                rawMoney = b.consumeBillDTO.consumeMoney.toDoubleOrNull() ?: 0.0
            )
        }
        PrefsHelper.widgetBillJson = gson.toJson(list)
        PrefsHelper.widgetBillTime = System.currentTimeMillis()
    }

    /**
     * 把附近设备快照存给小组件。
     *
     * **空结果也要写**：小组件靠 `widgetNearbyTime` 区分「扫过但附近没有」和「压根没扫过」，
     * 前者显示「未发现热水器」（和 App 首页一致），后者才提示回 App 扫一次。
     * 之前空结果直接 return，两种情况在桌面上长得一模一样。
     *
     * ⚠️ **不在这里按寝室过滤，也不在这里 `take(2)`**，两个都是坑：
     *
     * - 过滤搬到这里 = 快照被绑定的瞬间冻住。用户改绑寝室后，桌面上要等到**下次扫描**
     *   才跟着变，中间这段时间显示的是上一个寝室的设备。
     *   过滤放在小组件渲染时做，改绑定立刻生效。
     * - `take(2)` 放在这里 = **在过滤之前截断**。小组件只能显示 2 行，要是扫到的前两台
     *   恰好都是别的寝室，而本寝室的在第 3 台之后，过滤完就是空的——桌面显示
     *   「未发现热水器」，可用户寝室里明明有设备。
     *
     * 所以这里存**全量**（BLE 扫描一般就几台，几百字节），筛选和截断都留给渲染时。
     */
    private fun cacheNearbyForWidget() {
        val list = nearbyDevices.map { d ->
            CachedDevice(
                emoji = d.typeEmoji,
                name = d.displayName,
                // 设备信息还没拉到时用名字兜底，保证小字那行不会是空的
                desc = d.deviceInfo?.typeLabel
                    ?: if (d.name.startsWith("洗手台")) "洗手台热水器" else "卫生间热水器",
                rssi = d.rssi,
                mac = d.mac,
                // 原始名，寝室筛选用。注意和 name 不是一回事
                rawName = d.deviceInfo?.deviceName ?: d.name
            )
        }
        PrefsHelper.widgetNearbyJson = gson.toJson(list)
        PrefsHelper.widgetNearbyTime = System.currentTimeMillis()
    }

    /**
     * 把活跃订单从 Prefs 重新读回内存。
     * Prefs 是唯一事实来源——ShowerController 可能在没有这个 ViewModel 的情况下改过它
     * （例如用户在桌面小组件里开了阀）。
     */
    private fun syncActiveOrdersFromPrefs() {
        val saved = PrefsHelper.getActiveOrders()
        activeOrders.clear(); activeOrders.addAll(saved)
        activeDeviceSnCodes.clear(); saved.forEach { activeDeviceSnCodes.add(it.snCode) }
    }

    /** 开阀后订单号还没生成，轮询把它补上 */
    private fun startOrderPoll(snCode: String) {
        orderPollJob?.cancel()
        orderPollJob = sessionScope().launch {
            for (i in 0..10) {
                delay(800)
                if (currentOrderNo != null) break
                try {
                    val p = NetworkModule.apiService.queryUsingSafe(snCode = snCode, auth = NetworkModule.authFields())
                    if (p.errorCode == 307 || (p.success && p.data?.orderNo != null)) {
                        currentOrderNo = p.data?.orderNo
                        updateOrderNo(snCode, currentOrderNo ?: "")
                    } else checkKick(p.displayMessage)
                } catch (e: Exception) { checkKickEx(e) }
            }
        }
    }

    private fun updateOrderNo(snCode: String, orderNo: String) {
        val i = activeOrders.indexOfFirst { it.snCode == snCode }
        if (i >= 0 && orderNo.isNotEmpty()) {
            activeOrders[i] = activeOrders[i].copy(orderNo = orderNo)
            saveOrders()
        }
    }

    private fun enterShowerState(orderNo: String?, snCode: String, device: DeviceInfo, autoDiscon: Int = 0) {
        currentOrderNo = orderNo; isShowering = true
        showerPreDeduct = device.withholdMoney; showerConsumed = 0.0
        showerRemaining = "%.2f".format(device.withholdMoney)
        activeDeviceSnCodes.add(snCode)
        // 开阀时间戳可能刚刚才写入（开阀成功时），这里读一次再刷新桌面，计时才对得上
        refreshWidgets()

        // 自动关停倒计时。界面上的秒数无条件跟随传入值；
        // 持久化的截止时间只在没有时才写——恢复订单时传入的本身就是「剩余秒数」，
        // 若用无条件覆盖的写法会把它当成新的完整时长，倒计时会越恢复越长
        if (autoDiscon > 0) {
            autoDisConSec = autoDiscon
            if (PrefsHelper.getAutoDisconRemain(snCode) <= 0) {
                PrefsHelper.setAutoDisconRemain(snCode, autoDiscon)
            }
        }

        val st = PrefsHelper.getStartedAt(snCode)
        showerElapsedSec = if (st > 0) ((System.currentTimeMillis() - st) / 1000).toInt() else {
            PrefsHelper.setStartedAt(snCode, System.currentTimeMillis()); 0
        }

        lastDeviceName = device.displayName; lastDeviceMac = device.macAddress; lastDeviceSnCode = snCode; lastDeviceEmoji = device.typeEmoji
        PrefsHelper.lastDeviceName = device.displayName; PrefsHelper.lastDeviceMac = device.macAddress
        PrefsHelper.lastDeviceSnCode = snCode; PrefsHelper.lastDeviceEmoji = device.typeEmoji
        PrefsHelper.lastDeviceRawName = device.deviceName   // 寝室筛选用，见 PrefsHelper

        // 交给前台服务持续监控：超时自动关停、被外部关闭，都要能立刻发现。
        // 这两件事以前挂在下头的 timerJob 上，退出洗澡页就被 cancel 了。
        appContext?.let { ShowerWatchService.start(it, snCode) }

        // ⚠️ 这个计时器现在只做一件事：每秒刷新界面上显示的「已用时长」。
        //
        // 它以前还兼职做「倒计时递减」和「每 15 秒轮询订单状态」，但那两件事挂在这里
        // 有两个致命问题：`minimizeShower()` 会 cancel 它（退出洗澡页就没人管了），
        // 从小组件开阀时更是压根不会启动。现在都搬进 ShowerWatchService 了。
        //
        // 倒计时也从「每秒减一」改成了直接读 Prefs——那里存的是截止**时间戳**，
        // 按当前时间算出来，不会因为协程被延迟而慢慢走偏。
        timerJob?.cancel()
        timerJob = sessionScope().launch {
            while (isShowering) {
                delay(1000)
                val st = PrefsHelper.getStartedAt(snCode)
                if (st > 0) {
                    showerElapsedSec = ((System.currentTimeMillis() - st) / 1000).toInt()
                    autoDisConSec = PrefsHelper.getAutoDisconRemain(snCode)
                }
            }
        }
    }

    private fun handleMqttMessage(message: String) {
        try {
            val msg = gson.fromJson(message, MqttOrderMsg::class.java)

            // ⚠️ 这里曾经是 `showerSnCode?.let { updateOrderNo(it, it) }`——
            // 内层的 `it` **遮蔽**了外层 `it`，两个参数都变成了设备序列号，
            // 于是持久化的 orderNo 被写成了 snCode。
            //
            // 后果不容易当场发现：关阀和结算拿这个假 orderNo 去查，服务端只会说"查不到"，
            // 然后静默退化成按时间窗猜账单——而那个兜底本来就不可靠。
            // 所以这里**必须用具名变量**，别再用嵌套 `let` 的隐式 `it`。
            msg.orderNo?.let { orderNo ->
                if (currentOrderNo == null) {
                    currentOrderNo = orderNo
                    showerSnCode?.let { sn -> updateOrderNo(sn, orderNo) }
                }
            }
            msg.consumeMoney?.let {
                showerConsumed = it
                showerRemaining = "%.2f".format(if (showerPreDeduct - it < 0) 0.0 else showerPreDeduct - it)
            }
        } catch (_: Exception) {}
    }

    /**
     * 回到前台时对齐一次洗澡状态。
     *
     * [ShowerEvents] 是**没有 replay 的进程内事件**——正常情况下服务发、界面收，
     * 但万一漏了（事件发出时收集器恰好没就绪、或者进程被重建），
     * 用户就会看到洗澡界面挂在那里、计时还不走了（`startedAt` 已被清成 0）。
     *
     * 这里按本地活跃订单再核一次，兜住那条链路。**纯读 Prefs，不发网络请求**——
     * 服务结束时会先清 `activeOrders`，所以本地状态就是准的。
     */
    fun reconcileShowerOnResume() {
        if (!isShowering) return
        val sn = showerSnCode ?: return
        if (ShowerController.isRunning(sn)) {
            // 还在用，只是把界面上的数字拉正（切后台期间计时器没跑）
            val st = PrefsHelper.getStartedAt(sn)
            if (st > 0) showerElapsedSec = ((System.currentTimeMillis() - st) / 1000).toInt()
            autoDisConSec = PrefsHelper.getAutoDisconRemain(sn)
            return
        }
        // 已经结束了，而界面还停在洗澡页——补一次收尾
        AppLogger.i("回前台对齐：$sn 已结束，退出使用页")
        finishShower(sn, null)
    }

    /**
     * 用水结束（由 [ShowerWatchService] 检测到：设备超时自己关的，或在别处被关的）。
     *
     * 服务那边已经把活跃订单清掉、小组件刷新过、通知也发完了，
     * 这里只剩界面收尾——所以金额是直接带过来的，**不再自己结算一遍**。
     */
    private fun onShowerFinishedFromService(e: ShowerEvents.Finished) {
        // 先把 Prefs 里的活跃订单读回内存：小组件那边开了阀又关了，
        // 这个 ViewModel 的内存副本一直是旧的
        syncActiveOrdersFromPrefs()

        if (!isShowering || showerSnCode != e.snCode) return

        timerJob?.cancel(); orderPollJob?.cancel()

        if (!e.autoClosed) {
            // 用户自己结束的（通知栏「结束用水」按钮 / 小组件停止）：
            // 直接退出使用页就行，不用再弹一个确认框
            finishShower(e.snCode, e.money?.takeIf { it > 0 })
            return
        }

        // 设备自己超时关的：弹确认框把结果告诉用户
        autoCloseDeviceName = e.deviceName
        autoCloseElapsed = e.elapsedSec
        autoCloseConsumed = e.money   // 可空：null 时弹窗会说「未能获取」而不是 ¥0.00
        autoCloseLoading = false
        showAutoCloseDialog = true
    }

    /** 用户点确认：退出洗澡界面并清理 */
    fun confirmAutoClose() {
        showAutoCloseDialog = false
        val snCode = showerSnCode ?: ""
        finishShower(snCode, null)
    }

    /**
     * 把使用页拉回来。点「正在使用」通知进来时调用。
     *
     * 界面可能是三种状态之一：
     * - **已经停在使用页** → 什么都不用做
     * - **之前点了「最小化」** → `isShowering` 是 false，但设备还在跑
     * - **App 被杀过** → 同上，而且内存里的活跃订单也没了
     *
     * 后两种都要先从 Prefs 把活跃订单读回来，再走「恢复订单」那条路——
     * `openValve` 查到已有订单且是自己的时候返回 `Resumed`，**不会重新开阀**，
     * 正好是首页那张「使用中的设备」卡片上「恢复」按钮的行为。
     */
    fun restoreShowerScreen(phone: String) {
        if (isShowering) return
        syncActiveOrdersFromPrefs()
        val order = activeOrders.firstOrNull() ?: return
        lastDeviceSnCode = order.snCode
        lastDeviceMac = order.deviceMac
        startLastDevice(phone)
    }

    /**
     * 最小化使用界面：退出界面但**不结束用水**。
     * 订单保留在 activeOrders，计时器（startedAt）继续累计，可随时通过"恢复"回到界面。
     */
    fun minimizeShower() {
        if (!isShowering) return
        timerJob?.cancel()
        orderPollJob?.cancel()
        isShowering = false
        isStopping = false
        showerSnCode = null
        currentOrderNo = null
        // 注意：保留 activeOrders 与 PrefsHelper.getStartedAt(snCode)，用水与计时都继续
        try { mqttManager?.disconnect() } catch (_: Exception) {}
        toastMessage = "已返回主页，设备仍在运行"
    }

    // ════════════════════════════════════════════
    fun stopShower(skipNetwork: Boolean = false) {
        if (!isShowering || isStopping) return
        val snCode = showerSnCode ?: ""
        val oNo = currentOrderNo ?: activeOrders.find { it.snCode == snCode }?.orderNo ?: ""

        // 挤号等场景：loginCode 已失效，跳过网络请求，直接本地清理，避免再次触发挤号
        if (skipNetwork) {
            finishShower(snCode, null)
            return
        }

        isStopping = true
        // 立刻把通知切成「正在结束」。关阀确认要轮询最多 5 秒，
        // 不切的话用户点完还得盯着「正在使用 · 秒数还在走」干等。
        appContext?.let { Notifier.showStopping(it, lastDeviceName.ifEmpty { "热水器" }) }
        sessionScope().launch {
            try {
                // ⚠️ orderNo 必须在**关阀之前**敲定：关阀成功后订单就没了，
                // `queryUsing` 再也问不出来。而开阀后 orderNo 是异步轮询补上的，
                // 「开完水马上停」时本地可能还是空串——那就白丢了结算用的那个直答接口。
                val settledNo = ShowerController.resolveOrderNo(snCode).ifEmpty { oNo }
                // 关阀 + 确认 + 清本地状态都在 ShowerController 里，小组件的「停止使用」走同一份逻辑
                val outcome = ShowerController.closeValve(snCode, settledNo)
                checkKick(outcome.kickHint)

                if (outcome is CloseOutcome.Failed) {
                    toastMessage = outcome.message
                    isStopping = false
                    return@launch
                }

                // ⚠️ 没确认到设备停了（多半是断网，关阀请求压根没发出去）——
                // **不能退出使用页**。这一页上的「停止」是用户唯一的停止入口，
                // 退出去就等于把他扔在一个「以为停了、其实还在扣钱」的状态里，
                // 而且他想再停一次都找不到按钮。留在这里，让他能重试。
                if (outcome is CloseOutcome.Unconfirmed) {
                    toastMessage = "没能确认设备已关闭，请检查网络后重试"
                    isStopping = false
                    AppLogger.w("停止用水未确认：${outcome.message}")
                    return@launch
                }

                val startTime = (outcome as CloseOutcome.Closed).startTimeMs
                finishShower(snCode, null)
                // 界面已退出（不阻塞），后台异步等账单结算后弹金额
                sessionScope().launch {
                    // 和小组件/通知栏那两个入口一样：先挂「结算中」，拿到金额原地更新
                    val elapsed0 = if (startTime > 0)
                        ((System.currentTimeMillis() - startTime) / 1000).toInt() else 0
                    appContext?.let {
                        Notifier.showSettling(
                            it, Notifier.ID_FINISHED,
                            "使用结束 · ${lastDeviceName.ifEmpty { "热水器" }}", elapsed0
                        )
                    }
                    val amount = ShowerController.settleAmount(settledNo, startTime, snCode)
                    toastMessage = when {
                        // null = 两条路都没拿到金额，是「还不知道」而不是「没花钱」
                        amount == null -> "已停止，消费金额稍后可在账单中查看"
                        amount > 0 -> "已停止，本次消费 ¥%.2f".format(amount)
                        else -> "热水器已关闭，本次无消费"
                    }
                    // 结算拿到了新的「上次消费」，让桌面小组件跟上
                    refreshWidgets()

                    // 发系统通知，然后把后台监控停掉。
                    // 不停的话它下一轮会查到订单已经没了，再发一条重复的结束通知。
                    appContext?.let { ctx ->
                        val elapsed = if (startTime > 0)
                            ((System.currentTimeMillis() - startTime) / 1000).toInt() else 0
                        Notifier.showFinished(
                            ctx,
                            lastDeviceName.ifEmpty { "热水器" },
                            elapsed,
                            amount      // 可空：null 会显示成「结算中」而不是「无消费」
                        )
                        ShowerWatchService.stop(ctx)
                    }
                }
            } catch (e: Exception) {
                checkKickEx(e)
                toastMessage = "停止洗澡失败，请检查网络后重试"
                isStopping = false
            }
        }
    }

    /** 完成停止流程：退出洗澡界面，清理状态，显示结算结果 */
    private fun finishShower(snCode: String, consumed: Double?) {
        isShowering = false
        isStopping = false

        // 从活跃列表移除
        activeOrders.removeAll { it.snCode == snCode }
        saveOrders()
        activeDeviceSnCodes.remove(snCode)

        if (consumed != null) {
            toastMessage = "已停止，本次消费 ¥%.2f".format(consumed)
        } else {
            toastMessage = "热水器已关闭"
        }

        currentOrderNo = null; showerConsumed = 0.0; showerPreDeduct = 0.0
        showerRemaining = "0.00"; showerElapsedSec = 0
        autoDisConSec = 0
        PrefsHelper.setStartedAt(snCode, 0L) // 重置该设备计时器
        PrefsHelper.clearAutoDiscon(snCode)  // 清除自动关停倒计时
        showerSnCode = null; timerJob?.cancel(); orderPollJob?.cancel()
        try { mqttManager?.disconnect() } catch (_: Exception) {}
        refreshWidgets()  // 桌面小组件跟着变回「空闲」
    }

    fun logout() {
        // 掐断所有网络任务（含挤号心跳），避免退出后还有响应回来改状态
        stopTimer()
        sessionJob.cancel()
        sessionJob = SupervisorJob(viewModelScope.coroutineContext[Job])

        // 断开 MQTT 连接
        try { mqttManager?.disconnect() } catch (_: Exception) {}

        // 停止蓝牙扫描
        try { scanner?.stopScan() } catch (_: Exception) {}
        isScanning = false

        // 重置所有状态
        isShowering = false
        showerSnCode = null
        currentOrderNo = null
        showerConsumed = 0.0
        showerPreDeduct = 0.0
        showerRemaining = "0.00"
        showerElapsedSec = 0
        showerError = null
        toastMessage = null
        kickedOut = false
        selectedDevice = null
        showDeviceDetail = false
        nearbyDevices.clear()
        activeOrders.clear()
        activeDeviceSnCodes.clear()
        billList = emptyList()
        // 一起复位：下一轮登录要重新等账单到位，不能沿用上一次的「已加载」
        billsLoaded = false
        useCodeData = null
        walletInfo = null
        // 账号信息是跟人走的，换账号必须清掉，否则会显示上一任的姓名/学号/余额
        accountInfo = null
        campusBalance = null
        phone = ""
        useCodeLoaded = false
        // 未支付账单和代扣锁是**跟账号走的**：不清的话，换账号后开阀失败弹窗会列出
        // 上一任的账单和金额，还能用新账号的凭证去扣它；锁更麻烦——
        // 万一扣款协程在启动前就被取消（logout/挤号都会 cancel sessionJob），
        // 清锁那行永远跑不到，之后所有代扣都会静默失效，只能杀进程
        unpaidBills = emptyList()
        deductingConsumeDate = null
        unpaidSeq++
    }

    private fun saveOrders() { PrefsHelper.saveActiveOrders(activeOrders.toList()) }

    fun isDeviceActive(snCode: String) = snCode in activeDeviceSnCodes

    // ── 寝室绑定 / 设备筛选 ──

    /**
     * 绑定的寝室键。
     *
     * ⚠️ 必须是 Compose 状态，**不能直接读 `PrefsHelper.boundRoom`**。
     * SharedPreferences 是普通属性，改它不会触发重组——首页设备列表的过滤结果
     * 是用 `remember(..., boundRoom)` 缓存的，读 pref 的话取消绑定后列表要等
     * 下一次扫描（或别的什么把界面顶一下）才会更新，表现就是「点了取消没反应」。
     *
     * 改绑定一律走 [applyBoundRoom]，别绕过它直接写 PrefsHelper。
     */
    var boundRoom by mutableStateOf(PrefsHelper.boundRoom)
        private set

    /** 绑定/取消绑定寝室。写 Prefs + 更新状态 + 收尾（清寝室外的上次设备、刷新桌面） */
    fun applyBoundRoom(value: String) {
        val v = value.trim()
        PrefsHelper.boundRoom = v
        boundRoom = v
        onBoundRoomChanged()
    }

    /** 当前是否有绑定寝室 */
    val hasBoundRoom: Boolean get() = boundRoom.isNotBlank()

    /**
     * 这台设备在不在绑定的寝室内。
     *
     * `boundRoom` 里存的是**完整的设备名**（选择附近时存的是被选中的那台的原名），
     * 所以两边都要先过一遍 [DeviceInfo.roomKey] 取关键词，再**等值**比较——
     * 规则和原因都在那里写着，别在这儿另写一套。
     */
    fun matchesBoundRoom(deviceName: String): Boolean =
        DeviceInfo.inSameRoom(PrefsHelper.boundRoom, deviceName)

    /**
     * 绑定/换绑寝室之后收尾：**清掉寝室外的「上次使用设备」**，并让桌面跟上来。
     *
     * 为什么是「清掉」而不是「只是不显示」：`lastDeviceSnCode` 是小组件主按钮的
     * **开阀依据**。留着它就等于留着一条「点一下就开别寝室的水」的路——
     * 虽然小组件那边也加了一道闸门，但两处都拦不如根本不留下这台设备。
     *
     * ⚠️ **正在用水的设备不清**：那条记录对应着一个活跃订单，清掉会让水还在流、
     * 卡片却没了停止入口。用水中的卡片本来就不参与筛选（见 WidgetRenderer.readState），
     * 所以留着它是安全的。等这单结束，用户再重新选设备或改绑定。
     */
    fun onBoundRoomChanged() {
        val name = PrefsHelper.lastDeviceName
        val sn = PrefsHelper.lastDeviceSnCode
        if (name.isEmpty() || sn.isEmpty()) { refreshWidgets(); return }
        // ⚠️ 判断用**原始设备名**（roomFilterName），不是 name。
        // name 是格式化过的显示名，楼层被 formatDeviceName 去掉了，拿它算出来的
        // 寝室键和绑定值对不上——会把一个**本来就在寝室里**的设备误清掉。
        if (DeviceInfo.inSameRoom(PrefsHelper.boundRoom, ShowerController.roomFilterName())) {
            refreshWidgets()
            return
        }
        if (ShowerController.isRunning(sn)) {
            AppLogger.w("绑定寝室后没清上次设备：$name 正在用水，保留停止入口")
            refreshWidgets()
            return
        }
        PrefsHelper.lastDeviceSnCode = ""
        PrefsHelper.lastDeviceName = ""
        PrefsHelper.lastDeviceRawName = ""
        PrefsHelper.lastDeviceMac = ""
        PrefsHelper.lastDeviceEmoji = "🚿"
        lastDeviceSnCode = ""
        lastDeviceName = ""
        lastDeviceMac = ""
        lastDeviceEmoji = "🚿"
        AppLogger.i("绑定寝室后清掉了寝室外的上次设备：$name")
        refreshWidgets()
    }

    // ── 挤号 ──

    /** 心跳间隔。要"马上发现被挤号"就得主动轮询，这是拿一点电和流量换来的 */
    private val kickWatchIntervalMs = 25_000L

    /**
     * 开始挤号心跳检测：登录后调用，按生命周期在前后台启停。
     *
     * 原来只有发请求时才顺带检查挤号（被动），用户挂在这个页面不动就永远发现不了。
     * 这里定时打一个最轻的接口兜底，被挤号最多 25 秒内弹提示。
     */
    fun startKickWatch() {
        if (kickWatchJob?.isActive == true) return
        kickWatchJob = sessionScope().launch {
            while (isActive) {
                delay(kickWatchIntervalMs)
                if (kickedOut) break
                try {
                    val resp = NetworkModule.apiService.getWalletSafe()
                    if (!resp.success) checkKick(resp.displayMessage)
                } catch (e: Exception) {
                    // 网络异常不算挤号，checkKickEx 只认 401/403
                    checkKickEx(e)
                }
            }
        }
    }

    fun stopKickWatch() {
        kickWatchJob?.cancel()
        kickWatchJob = null
    }

    /**
     * 被挤号：标记状态并清掉本地凭证。
     *
     * 用 `if (kickedOut) return` 兜住重复触发——多个请求可能几乎同时返回"登录失效"，
     * 否则会反复 clear() + 反复通知界面。
     */
    private fun kickOut() {
        if (kickedOut) return
        kickedOut = true
        stopTimer()
        PrefsHelper.clear()
        // 和 logout() 同理：账号已经失效，上一任的未支付账单和代扣锁都不能留
        unpaidBills = emptyList()
        deductingConsumeDate = null
        unpaidSeq++
        // 掐断旧会话的所有在途请求，避免它们的失败响应回来干扰用户接下来的重新登录。
        // 放在最后：调用者本身就跑在 sessionJob 上，取消会连自己一起取消，
        // 而取消是协作式的——只要后面不再有挂起点，这几行仍会执行完。
        sessionJob.cancel()
        sessionJob = SupervisorJob(viewModelScope.coroutineContext[Job])
    }

    private fun checkKick(msg: String?) {
        if (msg.isNullOrEmpty()) return
        if (msg.contains("登录") || msg.contains("token") || msg.contains("失效") || msg.contains("过期") || msg.contains("认证") || msg.contains("未登录") || msg.contains("请重新")) {
            kickOut()
        }
    }

    private fun checkKickEx(e: Exception) {
        val m = e.message ?: return
        if (m.contains("401") || m.contains("403") || m.contains("Unauthorized") || m.contains("Forbidden")) {
            kickOut()
        }
    }

    /** 取消计时与轮询（挤号 / 退出登录共用） */
    private fun stopTimer() {
        timerJob?.cancel(); timerJob = null
        orderPollJob?.cancel(); orderPollJob = null
        stopKickWatch()
    }

    /**
     * 开始一次新会话：登录成功后调用。
     *
     * 必须做三件事，缺一个都会导致"重新登录进去还是被弹出"：
     * 1. 换一个全新的 sessionJob，让旧会话的残留请求彻底失效
     * 2. 清掉 kickedOut 标志，否则主界面一挂载就又弹挤号框
     * 3. 断开上一轮的 MQTT（它还连着旧会话的 topic），由新会话按需重连
     */
    fun beginSession() {
        sessionJob.cancel()
        sessionJob = SupervisorJob(viewModelScope.coroutineContext[Job])
        stopTimer()
        try { mqttManager?.disconnect() } catch (_: Exception) {}
        kickedOut = false
        // 换账号了，手机号跟着换（新会话的认证参数已经写进 Prefs，这里同步到界面状态）
        phone = PrefsHelper.telephone
    }

    fun refreshWallet() {
        sessionScope().launch {
            try { 
                val resp = NetworkModule.apiService.getWalletSafe()
                if (resp.success) walletInfo = resp.data else checkKick(resp.displayMessage) 
            } catch (e: Exception) { 
                checkKickEx(e)
                val msg = e.message ?: ""
                if (msg.contains("Unable to resolve host", ignoreCase = true) ||
                    msg.contains("No address associated", ignoreCase = true) ||
                    msg.contains("Network is unreachable", ignoreCase = true) ||
                    msg.contains("Failed to connect", ignoreCase = true)) {
                    toastMessage = "网络连接失败，请检查网络设置"
                }
            }
        }
    }

    fun loadUseCode() {
        sessionScope().launch {
            try {
                val resp = NetworkModule.apiService.getUseCodeSafe()
                if (resp.success && resp.data != null) useCodeData = resp.data
                // 无论成功与否都算「拉过了」——网络失败时停在「加载中」比显示
                // 「尚未领取」更糟，用户会以为 App 卡住了，而重进页面就会重拉
                useCodeLoaded = true
            } catch (_: Exception) {}
        }
    }

    /**
     * 「换一个」：换出候选使用码，**不改变当前生效的码**。
     *
     * 服务端每次扣一次额度（每天 20 次），返回剩余次数。换出来要再
     * [claimUseCode] 才生效，3 分钟内不领取就作废。
     *
     * @param onResult 成功时回调 `(新码, 剩余次数)`；失败回调 null
     */
    fun generateUseCode(onResult: (Pair<String, Int>?) -> Unit) {
        sessionScope().launch {
            val r = try {
                val resp = NetworkModule.apiService.generateUseCodeSafe(NetworkModule.authFields())
                val code = resp.data?.useCode
                if (resp.success && !code.isNullOrEmpty()) {
                    code to (resp.data?.remainTimes ?: 0)
                } else null
            } catch (_: Exception) {
                null
            }
            onResult(r)
        }
    }

    /** 「确定领取」：把换出来的候选码设为当前生效的使用码。成功返回 true */
    fun claimUseCode(useCode: String, onResult: (Boolean) -> Unit) {
        sessionScope().launch {
            val ok = try {
                NetworkModule.apiService
                    .setUseCodeSafe(useCode, NetworkModule.authFields())
                    .success
            } catch (_: Exception) {
                false
            }
            if (ok) loadUseCode()
            onResult(ok)
        }
    }

    /**
     * 拉账号信息（姓名 / 学号 / 校园卡绑定状态）。
     *
     * 登录响应里其实也带这些字段，但我们的 Gson 模型只解析了用到的几个，
     * 而且**姓名在学校没同步时会是空**——单独走这个接口补齐，拿到就写回 Prefs。
     */
    fun loadAccountInfo() {
        if (!PrefsHelper.isLoggedIn) return
        sessionScope().launch {
            try {
                NetworkModule.apiService.getAccountInfoSafe().data?.let {
                    applyIdentity(it.name, it.idCardNumber, it)
                }
                // 姓名靠学校同步学籍数据，没同步的账号 /account/info 返回的 name 就是 null
                // （实测同学的两个号都是这样）。换个接口再要一次——绑定卡信息里也有姓名和学号，
                // 两个接口数据来源不同，一个没有另一个可能有。
                if (PrefsHelper.userName.isEmpty() || PrefsHelper.userStudentId.isEmpty()) {
                    NetworkModule.apiService.getBindCardInfoSafe().data?.let {
                        applyIdentity(it.name, it.idCardNumber, null)
                    }
                }
            } catch (_: Exception) {}
        }
    }

    /**
     * 把姓名 / 学号落到 Prefs **和** [accountInfo] 上。
     *
     * 现在有三个接口都能给这两个字段（`/account/info`、`/account/card/getBindCardInfo`、
     * `/settlement/campus/userInfo`），谁先拿到算谁的。所以统一走这里收口，
     * 免得每个调用点各写一遍「判空 → 写 Prefs」。
     *
     * ⚠️ 必须同时更新 [accountInfo]：界面读的是这个 State，只写 Prefs 不会触发重组，
     * 数据回来了界面也不刷新。
     *
     * @param extra 该来源额外的字段（卡状态之类），为 null 时保留已有的
     */
    private fun applyIdentity(name: String?, studentId: String?, extra: AccountInfo?) {
        val n = name?.takeIf { it.isNotBlank() }
        val s = studentId?.takeIf { it.isNotBlank() }
        if (n != null) PrefsHelper.userName = n
        if (s != null) PrefsHelper.userStudentId = s
        if (n == null && s == null && extra == null) return

        val cur = accountInfo
        val merged = AccountInfo(
            name = n ?: cur?.name,
            idCardNumber = s ?: cur?.idCardNumber,
            telephone = extra?.telephone ?: cur?.telephone,
            genderName = extra?.genderName ?: cur?.genderName,
            cardStatus = extra?.cardStatus ?: cur?.cardStatus,
            cardStatusName = extra?.cardStatusName ?: cur?.cardStatusName,
            gradeName = extra?.gradeName ?: cur?.gradeName,
            className = extra?.className ?: cur?.className
        )
        if (merged != cur) accountInfo = merged
    }

    /**
     * **未支付账单**——就是俗称的「残留账单」。
     *
     * 12 点后结束用水、或者一卡通余额不足时，服务端结算没扣成，账单会留在待扣状态。
     * 界面靠它决定哪些账单显示「请求代扣」按钮；开阀失败时也用它来提示
     * 「你可能是因为有未扣账单才开不了」。
     *
     * 存整个对象而不是只存日期，因为弹窗要显示**欠了多少钱**。
     */
    var unpaidBills by mutableStateOf<List<UnpaidBill>>(emptyList())
        private set

    /**
     * **真正能被补扣的账单**：`consumeDate` 非空的那些。
     *
     * 代扣接口就是靠 `consumeDate` 定位账单的，日期为空的一笔根本发不出去。
     * 界面显示的笔数 / 金额、以及 [deductAllUnpaid] 实际扣的，**都必须用这一份**——
     * 否则会出现「弹窗说共 3 笔、实际只扣 2 笔」，全为空时还会报一次假成功。
     */
    val deductibleBills: List<UnpaidBill>
        get() = unpaidBills.filter { !it.consumeDate.isNullOrBlank() }

    /** 未支付账单的 `consumeDate` 集合。按账单列表匹配按钮显隐时用它 */
    val unpaidConsumeDates: Set<String>
        get() = deductibleBills.mapNotNull { it.consumeDate }.toSet()

    /** 正在代扣的那条账单的 consumeDate；非空期间按钮禁用，防止连点重复扣款 */
    var deductingConsumeDate by mutableStateOf<String?>(null)
        private set

    /**
     * 未支付列表请求的单调序号。
     *
     * ⚠️ 不能省。刷新入口有四个（钱包页进入、下拉刷新、主页下拉、代扣成功后），
     * `AnimatedContent` 切页时新旧页面还会并存几百毫秒——**多个请求同时在途是常态**。
     * 没有序号的话，先发后到的旧响应会把刚扣掉的那笔「复活」，
     * 用户以为没扣成功再点一次，就是**扣两次**。
     */
    private var unpaidSeq = 0

    /** 拉一次未支付列表；失败返回 null（调用方保持原样，不清空） */
    private suspend fun fetchUnpaid(): List<UnpaidBill>? = try {
        val resp = NetworkModule.apiService.queryUnpaidBillsSafe(NetworkModule.authFields())
        if (resp.success) resp.data ?: emptyList() else null
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    /** 拉未支付账单列表。**失败保持原样，不清空**——清空会让按钮凭空消失 */
    fun loadUnpaidBills() {
        if (!PrefsHelper.isLoggedIn) return
        val seq = ++unpaidSeq
        sessionScope().launch {
            // 只接受最新一次请求的结果，见 [unpaidSeq]
            val list = fetchUnpaid() ?: return@launch
            if (seq == unpaidSeq) unpaidBills = list
        }
    }

    // ── 代扣 ──

    /**
     * 单笔代扣的结果。**「失败」和「结果未知」必须分开**。
     *
     * 分不清的代价很实在：请求已经发到服务端、只是没拿到答复的时候，
     * 如果报「失败」，界面就会把按钮放回去让用户重试——而服务端可能已经扣过了，
     * 那一下就是**扣两次**。见 [deductOnce]。
     */
    sealed interface DeductResult {
        data class Ok(val consumeDate: String) : DeductResult

        /** 服务端**明确**说没成（余额不足、账户异常…）。可以放心重试 */
        data class Failed(val consumeDate: String, val reason: String?) : DeductResult

        /**
         * 请求发出去了，但没拿到答复（超时、连接断开、会话被取消…）。
         *
         * **绝对不能当成失败**，也不该让用户重试，只能重新查一次服务端状态来判断。
         */
        data class Unknown(val consumeDate: String) : DeductResult
    }

    /** 批量补扣的汇总 */
    data class DeductSummary(
        val ok: Int,
        val failed: Int,
        /** 结果未知的笔数——这些既不能说成功也不能说失败 */
        val unknown: Int,
        /**
         * 第一笔失败时服务端给的原文（「余额不足」之类）。
         *
         * 批量路径以前只累加计数、把原因丢了，用户只能看到「请到钱包页重试」。
         */
        val firstFailReason: String? = null
    )

    /**
     * 扣一笔。**这是唯一碰代扣接口的地方**，单笔和批量都走它，
     * 免得两处流程各写一遍、改一处漏一处（而这是动钱的路径）。
     *
     * ⚠️ `CancellationException` 必须**先于** `Exception` 捕获并重抛：
     * 它是 `Exception` 的子类，混进下面的 catch 里就会被当成「扣款失败」，
     * 而请求其实已经发出去了。详见 [DeductResult.Unknown]。
     */
    private suspend fun deductOnce(consumeDate: String): DeductResult = try {
        val resp = NetworkModule.apiService
            .requestDeductSafe(consumeDate, NetworkModule.authFields())
        if (resp.success) DeductResult.Ok(consumeDate)
        else DeductResult.Failed(consumeDate, resp.displayMessage)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // 超时 / 断连：请求**可能已经到达服务端**，不能当作失败
        DeductResult.Unknown(consumeDate)
    }

    /**
     * 「结果未知」的收尾：重查一次服务端，以它为准。
     *
     * 账单还在列表里 → 说明没扣成（服务端会拒绝重复扣）；不在了 → 说明扣成了。
     * 这是唯一能确定答案的办法，**不能靠猜**。
     */
    private suspend fun reconcileUnpaid(): List<UnpaidBill>? {
        val list = fetchUnpaid() ?: return null
        // 让更早发出的刷新全部作废——这份是**刚刚**拿到的，比它们都新。
        // 不推序号的话，一个还在路上的旧响应回来后会把这里的结论覆盖掉
        unpaidSeq++
        unpaidBills = list
        return list
    }

    /**
     * 手动请求代扣（单笔）。
     *
     * ⚠️ **这是动钱的接口**，防重是第一要务：
     * - [deductingConsumeDate] 非空时**回调告知被挡住**，不静默 return
     *   （静默的话调用方已经把弹窗关了，用户点完什么都不发生）
     * - 成功后立刻把这条从列表里摘掉，不等网络往返
     * - 结果未知时不摘、也不报失败，重查服务端定夺
     */
    fun requestDeduct(consumeDate: String, onResult: (DeductResult) -> Unit) {
        if (consumeDate.isBlank()) return
        if (deductingConsumeDate != null) {
            // ⑧ 不能静默返回——调用方已经关了弹窗，用户会以为在扣
            onResult(DeductResult.Failed(consumeDate, BUSY_MESSAGE))
            return
        }

        deductingConsumeDate = consumeDate
        sessionScope().launch {
            try {
                val r = deductOnce(consumeDate)
                when (r) {
                    is DeductResult.Ok -> {
                        unpaidBills = unpaidBills.filterNot { it.consumeDate == consumeDate }
                        loadUnpaidBills()
                        loadBills()
                    }
                    is DeductResult.Unknown -> reconcileUnpaid() ?: loadUnpaidBills()
                    is DeductResult.Failed -> Unit
                }
                onResult(r)
            } catch (_: CancellationException) {
                // 会话级取消（挤号 / 退出登录 / 重新登录）。这里**不能报失败**——
                // 请求可能已经发出去了。清掉锁就够了，界面马上就会被替换掉
            } finally {
                deductingConsumeDate = null
            }
        }
    }

    /**
     * 补扣**全部**未支付账单，一笔一笔来。
     *
     * 开阀被未扣账单卡住时用这个：只补一笔往往还是开不了阀，
     * 而让用户回「钱包」页一笔一笔点太绕。
     *
     * @param onResult 传 null 表示**一笔都没发出去**（没有可补的账单，或者被另一个代扣挡住了）
     */
    fun deductAllUnpaid(onResult: (DeductSummary?) -> Unit) {
        if (deductingConsumeDate != null) {
            onResult(null)          // ⑧ 被挡住了也要回调，不能静默
            return
        }
        // 用和界面显示完全一致的来源，并且去重——review 发现过这里没去重，
        // 而 unpaidConsumeDates 去了（.toSet()），同一份数据两个口径
        val dates = unpaidConsumeDates.toList()
        if (dates.isEmpty()) {
            onResult(null)
            return
        }

        deductingConsumeDate = dates.first()   // 同步置锁，和 requestDeduct 一致
        sessionScope().launch {
            var ok = 0
            var failed = 0
            var unknown = 0
            var failReason: String? = null
            try {
                for (d in dates) {
                    deductingConsumeDate = d
                    when (val r = deductOnce(d)) {
                        is DeductResult.Ok -> {
                            ok++
                            unpaidBills = unpaidBills.filterNot { it.consumeDate == d }
                        }
                        is DeductResult.Failed -> {
                            failed++
                            if (failReason == null) failReason = r.reason
                        }
                        is DeductResult.Unknown -> unknown++
                    }
                }
                // 有未知的才需要重查；全部明确成功时直接刷新即可
                if (unknown > 0) reconcileUnpaid() ?: loadUnpaidBills() else loadUnpaidBills()
                loadBills()
                onResult(DeductSummary(ok, failed, unknown, failReason))
            } catch (_: CancellationException) {
                // 会话被取消，剩下的都没发出去。不回调——界面马上要被替换掉
            } finally {
                deductingConsumeDate = null
            }
        }
    }

    companion object {
        /** 被另一个代扣挡住时给用户的说法 */
        const val BUSY_MESSAGE = "正在处理另一笔代扣，请稍候"
    }

    /** 关掉开阀失败弹窗 */
    fun clearShowerError() { showerError = null }

    /**
     * 拉学校名填进「学校」那一行。
     *
     * 服务端的 `projectName` 就是学校名，本来就有——以前这里是写死的默认值，
     * 让用户自己填，纯属多余。**拉不到就保持原样**（默认值或用户手动填过的），
     * 不覆盖成空串。
     */
    fun loadSchoolName() {
        if (!PrefsHelper.isLoggedIn) return
        sessionScope().launch {
            try {
                val n = NetworkModule.apiService.getProjectInfoSafe().data?.projectName
                if (!n.isNullOrBlank() && n != PrefsHelper.schoolName) {
                    PrefsHelper.schoolName = n
                    schoolName = n
                }
            } catch (_: Exception) {}
        }
    }

    /**
     * 拉一卡通真实余额。
     *
     * **拿不到就保持 null**，界面回退到本地估算——服务端只在学生签约过
     * 校园卡免密支付时才给 `amount`。
     */
    fun loadCampusBalance() {
        if (!PrefsHelper.isLoggedIn) return
        sessionScope().launch {
            try {
                val d = NetworkModule.apiService.getCampusUserInfoSafe().data ?: return@launch
                val amount = d.amount?.toDoubleOrNull()
                if (amount != null) {
                    campusBalance = amount
                    PrefsHelper.campusBalance = d.amount.orEmpty()
                    PrefsHelper.campusBalanceTime = System.currentTimeMillis()
                    // 桌面小组件的余额是**渲染时**从 Prefs 读的，不会自己知道值变了。
                    // 不推一次刷新，它会一直顶着上次渲染的估算值——
                    // 用户看到的就是「App 里是真余额、桌面上还是估算」。
                    appContext?.let { LinYuWidget.refreshAll(it) }
                }
                // 这个接口顺带也给姓名和学号。它是从**校园卡**那边查的，
                // 和 /account/info 的数据来源不同——学校没同步学籍时前者为 null、
                // 这里却有值，正好互补。以前只取了学号，把姓名漏了。
                applyIdentity(d.studentName, d.studentNumber, null)
            } catch (_: Exception) {}
        }
    }

    /**
     * 刷新余额与账单。
     * 扫描不在这里触发——是否扫描由界面决定（要先确认拿到权限），
     * 见 MainScreen 的 scanWithPermission()。
     */
    fun pullRefresh() {
        refreshWallet()
        loadBills()
        // 一卡通余额是外部账户的钱，会随时变（食堂刷卡、充值都会动），
        // 下拉刷新时一起拉一次
        loadCampusBalance()
        // 残留账单同理——在别处（比如官方 App）补扣掉了，这边也得跟着消失
        loadUnpaidBills()
    }

    // ── 设备发现 ──
    fun addDevice(device: NearbyDevice) {
        val idx = nearbyDevices.indexOfFirst { it.mac == device.mac }
        if (idx >= 0) {
            val e = nearbyDevices[idx]
            if (abs(e.rssi - device.rssi) > 5 || e.deviceInfo == null) {
                nearbyDevices[idx] = e.copy(rssi = device.rssi)
                if (e.deviceInfo == null && fetchingMacs.add(device.mac)) fetchInfo(device.mac)
            }
        } else {
            nearbyDevices.add(device); if (fetchingMacs.add(device.mac)) fetchInfo(device.mac)
        }
    }

    private fun fetchInfo(mac: String) {
        sessionScope().launch {
            try {
                val resp = NetworkModule.apiService.getDeviceInfoSafe(mac)
                if (resp.success && resp.data != null) {
                    val info = resp.data; val i = nearbyDevices.indexOfFirst { it.mac == mac }
                    if (i >= 0) nearbyDevices[i] = nearbyDevices[i].copy(deviceInfo = info)
                    try {
                        val q = NetworkModule.apiService.queryUsingSafe(snCode = info.snCode, auth = NetworkModule.authFields())
                        if (q.errorCode == 307 || (q.success && q.data?.orderNo != null)) {
                            activeDeviceSnCodes.add(info.snCode)
                            val owner = q.data?.isOwner ?: true
                            if (owner && activeOrders.none { it.snCode == info.snCode }) {
                                activeOrders.add(ActiveOrder(info.snCode, q.data?.orderNo ?: "", info.displayName, info.macAddress, info.typeEmoji, info.withholdMoney))
                                saveOrders()
                            }
                        }
                    } catch (_: Exception) {}
                } else checkKick(resp.displayMessage)
            } catch (e: Exception) { checkKickEx(e) }
            finally { fetchingMacs.remove(mac) }
        }
    }

    // ── 账单 ──
    fun loadBills() {
        sessionScope().launch {
            isLoadingBills = true
            try {
                val fmt = java.text.SimpleDateFormat("yyyy-MM", java.util.Locale.getDefault())
                val cal = java.util.Calendar.getInstance()
                val all = mutableListOf<BillItem>()
                for (i in 0..2) {
                    val month = fmt.format(cal.time)
                    val resp = NetworkModule.apiService.getBillListSafe(month = month)
                    if (resp.success && !resp.data.isNullOrEmpty()) all.addAll(resp.data)
                    cal.add(java.util.Calendar.MONTH, -1)
                }
                billList = all.take(20)
                billsLoaded = true
                // 顺手把最近一笔消费记给桌面小组件。账单按月倒序拉取，第一条就是最新的。
                // ⚠️ 只有确认它属于「上次使用设备」时才记——账单接口不带 snCode，只能拿设备名比对。
                // 比不中就跳过：宁可这次不记录，也别把别的设备的消费记到当前设备头上。
                val newest = all.firstOrNull()?.consumeBillDTO
                val lastSn = PrefsHelper.lastDeviceSnCode
                if (newest != null && lastSn.isNotEmpty() &&
                    newest.displayDesc == PrefsHelper.lastDeviceName
                ) {
                    newest.consumeMoney.toDoubleOrNull()
                        ?.let { PrefsHelper.recordConsume(lastSn, it) }
                }
                // ⚠️ 必须和上面 billList 用的条数一致。
                // 小组件用这份缓存算「初始余额 − 消费」的估算值，
                // 只给 2 笔的话减数偏小，桌面上的余额会比 App 里高出一截。
                // 界面上只渲染 2 行，多存的不影响显示。
                cacheBillsForWidget(all.take(20))
                refreshWidgets()
            } catch (e: Exception) { 
                checkKickEx(e)
                val msg = e.message ?: ""
                if (msg.contains("Unable to resolve host", ignoreCase = true) ||
                    msg.contains("No address associated", ignoreCase = true) ||
                    msg.contains("Network is unreachable", ignoreCase = true) ||
                    msg.contains("Failed to connect", ignoreCase = true)) {
                    toastMessage = "网络连接失败，请检查网络设置"
                }
            }
            finally { isLoadingBills = false }
        }
    }
}
