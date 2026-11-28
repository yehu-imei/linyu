package com.hualala.linyu.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hualala.linyu.api.NetworkModule
import com.hualala.linyu.api.ProjectAuthContext
import com.hualala.linyu.api.getAccountInfoSafe
import com.hualala.linyu.api.getProjectBillListSafe
import com.hualala.linyu.api.getBindCardInfoSafe
import com.hualala.linyu.api.getCampusUserInfoSafe
import com.hualala.linyu.api.getDeviceInfoSafe
import com.hualala.linyu.api.forgetPasswordSafe
import com.hualala.linyu.api.generateUseCodeSafe
import com.hualala.linyu.api.updateUseCodeStatusSafe
import com.hualala.linyu.data.UseCodePolicy
import com.hualala.linyu.data.ConsumptionCachePolicy
import com.hualala.linyu.api.getProjectInfoSafe
import com.hualala.linyu.api.getUseCodeSafe
import com.hualala.linyu.api.queryUnpaidBillsSafe
import com.hualala.linyu.api.requestDeductSafe
import com.hualala.linyu.api.setUseCodeSafe
import com.hualala.linyu.api.getWalletSafe
import com.hualala.linyu.api.queryUsingSafe
import com.hualala.linyu.data.BalanceEstimator
import com.hualala.linyu.data.CampusBalanceUpdate
import com.hualala.linyu.data.BillHistoryCache
import com.hualala.linyu.data.BleArbiter
import com.hualala.linyu.data.CloseOutcome
import com.hualala.linyu.data.OpenOutcome
import com.hualala.linyu.data.ProjectAuthRepository
import com.hualala.linyu.data.deviceInUse
import com.hualala.linyu.data.ownedOrderNo
import com.hualala.linyu.ble.BleController
import com.hualala.linyu.ble.BleConnectResult
import com.hualala.linyu.data.BleShowerController
import com.hualala.linyu.data.PluginManifest
import com.hualala.linyu.data.InstalledPlugins
import com.hualala.linyu.data.RoomBindingPlugin
import com.hualala.linyu.data.ShowerController
import com.hualala.linyu.data.ShowerEvents
import com.hualala.linyu.data.ShowerOrderTarget
import com.hualala.linyu.data.ActiveOrderRepository
import com.hualala.linyu.data.usesBluetoothForStop
import com.hualala.linyu.data.SettlementReconciler
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
import com.hualala.linyu.utils.BackgroundManager
import com.hualala.linyu.utils.BluetoothScanner
import com.hualala.linyu.utils.MqttManager
import com.hualala.linyu.utils.Notifier
import com.hualala.linyu.utils.PrefsHelper
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import com.hualala.linyu.utils.MoneyFormat
import com.hualala.linyu.data.DeviceInfoCache
import com.hualala.linyu.data.FavoriteDevice
import com.hualala.linyu.data.HomeDeviceLayout
import com.hualala.linyu.data.HomeDevicePreferences
import com.hualala.linyu.data.HomeDeviceSection
import com.hualala.linyu.widget.WidgetBridge
import com.hualala.linyu.data.MacFormat
import com.hualala.linyu.data.SettlementEngine
import java.time.YearMonth

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
    internal var homeDeviceLayout by mutableStateOf(HomeDeviceLayout())
        private set
    private var homeLayoutOwner = ""
    private var deviceDetailJob: Job? = null
    var isScanning by mutableStateOf(false)
    var scanStartTime by mutableStateOf(0L)

    var isShowering by mutableStateOf(false)
    var isStartingShower by mutableStateOf(false)
    var isStopping by mutableStateOf(false)

    // ── 蓝牙水表（smallTypeId==1 / isBle）──
    //
    // 与 4G 表最大的不同：**连接必须跨「开阀 → 用水 → 关阀」整段存活**。
    // 蓝牙表的阀是手机直连开的，中途断开连接就没法再关阀了，
    // 所以这两个对象挂在 ViewModel 上，不能像普通请求那样用完即弃。

    /** 当前蓝牙连接。没有进行中的蓝牙用水时为 null */
    private var bleController: BleController? = null

    /** 当前蓝牙用水会话。开阀成功后写入，结算完清空 */
    private var bleSession: BleShowerController.Session? = null

    /**
     * 蓝牙流程的进度文案（null 表示没在进行）。
     *
     * 蓝牙开阀比 4G 表慢得多——要连设备、等状态帧、下费率、写费率包，
     * 全程好几秒，不给反馈用户会以为卡死。
     */
    var bleProgress by mutableStateOf<String?>(null)
        private set

    /**
     * 云端开阀返回 306 时暂存这里，等用户在确认框里决定要不要改用蓝牙。
     *
     * 非 null 即表示「弹框正在显示」。用户确认或取消后清空。
     */
    var bleRetryDevice by mutableStateOf<DeviceInfo?>(null)
        private set

    /**
     * 用户在确认框里选了「用蓝牙重试」。
     *
     * 先记下这台设备（`markBleDevice`），再走蓝牙流程——
     * **先记再看结果**：万一蓝牙连接超时（用户走远了），标记还是要保住的，
     * 否则下次又要问一遍。真猜错了（连上但没有水控服务）会自动撤销。
     */
    fun confirmBleRetry() {
        val device = bleRetryDevice ?: return
        bleRetryDevice = null
        PrefsHelper.markBleDevice(device.snCode)
        isStartingShower = true
        sessionScope().launch {
            try {
                showerError = null
                showerSnCode = device.snCode
                startBleShower(device, phone, device.snCode)
            } catch (t: Throwable) {
                AppLogger.e("蓝牙重试异常", t)
                showerError = ShowerError("蓝牙开启失败，请重试", ShowerErrorKind.NETWORK)
            } finally {
                isStartingShower = false
            }
        }
    }

    /** 用户放弃蓝牙方式 */
    fun dismissBleRetry() {
        bleRetryDevice = null
    }

    /**
     * 服务端「设备不在线」的业务错误码。
     *
     * ⚠️ 用它判断，**不要匹配文案**（之前就是匹配「不在线」三个字，太脆：
     * 措辞会随学校和服务端版本变，而且我们只能覆盖到自己见过的说法）。
     */
    private val ERROR_DEVICE_OFFLINE = 306
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
    private var showerPageTarget: ShowerOrderTarget? = null
    private var autoCloseTarget: ShowerOrderTarget? = null
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

    /**
     * 已查询到占用的设备（按 snCode 索引）。值表示是否已确认是自己的订单。
     *
     * ⚠️ 原先是一个全局 `isOwner` 布尔，而 [refreshDeviceStatus] 是**异步**的——
     * 快速切换设备时，A 的响应回来会盖到 B 上（「A 是别人的」串成 B 的状态，
     * 详情弹窗的按钮就会错灰或错亮）。改成按 snCode 存：谁的结果写谁，互不干扰。
     */
    private val ownerBySn = mutableStateMapOf<String, Boolean>()

    /** 未查询到归属时，只允许恢复有本地记录的用水。 */
    fun isOwnerFor(snCode: String): Boolean = ownerBySn[snCode] ?: activeOrders.any {
        it.snCode == snCode && (it.orderNo.isNotBlank() || PrefsHelper.getStartedAt(snCode) > 0L)
    }

    /** 当前选中设备是否归自己（设备详情弹窗用） */
    val isOwner: Boolean get() = selectedDevice?.snCode?.let { isOwnerFor(it) } == true

    // ── 多设备活跃订单 ──
    val activeOrders = mutableStateListOf<ActiveOrder>()

    // 用于当前洗澡的设备 snCode
    private var showerSnCode: String? = null
    /** 自动关停后页面可能仍等待确认，以当前设备的活跃订单判断是否还在使用。 */
    val isShowerDeviceActive: Boolean
        get() = activeOrders.any { it.snCode == showerSnCode }

    private var currentOrderNo: String? = null
    /** 本轮扫描里已经发起过设备信息查询的 MAC（见 [addDevice]）；[startScan] 时清空 */
    private val queriedThisScan = mutableSetOf<String>()
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
        // 让小组件服务知道「App 还活着、可能握着 BLE 连接」（见 BleArbiter）
        BleArbiter.appAlive = true
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
        // 通知栏「结束使用」按钮 / 小组件的「关闭」对蓝牙表：服务发来请求，
        // App 若还持有这台设备的 BLE 连接就用自己的连接关阀（更稳），否则**重连设备**再关。
        viewModelScope.launch {
            ShowerEvents.stopBleRequest.collect { target ->
                AppLogger.i("收到服务请求停止蓝牙用水 ${target.snCode}")
                stopBleShower(target)
            }
        }
    }

    // ── 账单 ──
    var billList by mutableStateOf<List<BillItem>>(emptyList())
    var billHistory by mutableStateOf<List<BillItem>>(emptyList())
    var balanceBills by mutableStateOf<List<BillItem>>(emptyList())
        private set
    var isLoadingBills by mutableStateOf(false)
    var billRefreshFailed by mutableStateOf(false)
        private set
    private var billLoadJob: Job? = null
    private var billLoadSequence = 0

    fun isDefaultProjectBill(bill: BillItem): Boolean =
        bill.belongsTo(PrefsHelper.projectId, PrefsHelper.accountId)

    /**
     * 账单是否**至少成功拉取过一次**。
     *
     * 余额是「用户填的初始余额 − 账单里的消费」算出来的。账单到位之前 balanceBills 是空的，
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
    private var useCodeQuota by mutableStateOf(PrefsHelper.useCodeQuota())
    val useCodeRemaining: Int? get() = UseCodePolicy.remainingToday(useCodeQuota)
    val useCodeBlockReason: String? get() = UseCodePolicy.blockReason(useCodeData?.resetAvailability, useCodeRemaining)
    var changingUseCodeStatus by mutableStateOf(false)
        private set
    private var generatingUseCode = false
    private var claimingUseCode = false
    private var useCodeLoadSeq = 0
    var useCodeError by mutableStateOf<String?>(null)
        private set

    /**
     * 账号信息（姓名 / 学号 / 绑定状态）。null = 还没拉到。
     *
     * 存在的意义在于**能触发重组**：直接让界面读 `PrefsHelper.userName` 的话，
     * 拉完数据界面不会自己刷新（Prefs 不是 Compose State）。
     */
    var accountInfo by mutableStateOf<AccountInfo?>(null)
        private set
    var accountInfoLoaded by mutableStateOf(PrefsHelper.userName.isNotBlank() || PrefsHelper.userStudentId.isNotBlank())
        private set
    var accountInfoError by mutableStateOf<String?>(null)
        private set
    private var loadingAccountInfo = false
    private var profileLoadSeq = 0

    /**
     * 一卡通**真实余额**。null = 拿不到（没签约免密支付、或者还没拉过），
     * 这时界面回退到「初始余额 − 账单消费」的本地估算。
     *
     * 初值取 Prefs 里上次拉到的值：从桌面小组件点进账单页会**新建一个 ViewModel**，
     * 等接口回来之前这段时间界面会退回估算、还会冒出「手动填写」按钮，
     * 看着像数据丢了。先亮上次的值，拉到新的再覆盖。
     */
    var campusBalance by mutableStateOf(PrefsHelper.campusBalance.toDoubleOrNull())
    private var campusBalanceLoadJob: Job? = null

    /**
     * 趣智校园钱包余额（`/account/wallet`）。
     *
     * 一卡通拿不到时的第二来源，见 [com.hualala.linyu.data.BalanceEstimator]。
     * 界面判断「有没有真实余额」时必须把它算进去，否则没接一卡通的学校
     * 会被误判成「只能估算」。
     */
    var walletBalance by mutableStateOf(PrefsHelper.walletBalance.toDoubleOrNull())
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
        boundRoom = RoomBindingPlugin.effectiveRoom
        homeLayoutOwner = NetworkModule.session().owner
        homeDeviceLayout = HomeDevicePreferences.load(homeLayoutOwner)

        // 恢复所有活跃订单
        val saved = PrefsHelper.getActiveOrders()
        activeOrders.clear()
        activeOrders.addAll(saved)
    }

    override fun onCleared() {
        super.onCleared()
        BleArbiter.appAlive = false
        stopTimer()
        sessionJob.cancel()
        mqttManager?.disconnect(); scanner?.stopScan()
    }

    fun startScan() {
        nearbyDevices.clear(); ownerBySn.clear()
        // 新一轮扫描 = 允许对每台设备再查一次（见 addDevice）
        queriedThisScan.clear()
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
        if (!ok) return

        // ⚠️ 扫描**结束后**才按信号强度排一次序。
        //
        // 原先列表是纯插入顺序（谁先被扫到谁在前面）。寝室场景只有一两台设备时无所谓，
        // 但**公共澡堂**完全不是这回事：一间屋十二个位置、几个屋子连在一起，
        // 一次能扫到几十上百台，按扫描顺序排列等于让用户在一堆设备名里大海捞针，
        // 根本找不到自己站的那个位置。
        //
        // 按 RSSI 降序后排在最前面的，就是离手机最近的那一台——澡堂里也就是
        // 用户面前这个位置，通常正是他要开的那个。
        //
        // ⚠️ **必须是扫描结束后排，不能实时排**。扫描过程中 RSSI 一直在小幅波动，
        // 实时重排会让列表不停跳动，用户正要点的时候被挪走，直接点错设备——
        // 点错在澡堂里意味着替别人付了水费。结束后排一次既有正确的顺序，又不会跳。
        val sorted = nearbyDevices.sortedByDescending { it.rssi }
        nearbyDevices.clear()
        nearbyDevices.addAll(sorted)

        cacheNearbyForWidget()
    }

    // ── 扫码绑定 ──
    fun scanBind(snCode: String) {
        deviceDetailJob?.cancel()
        deviceDetailJob = sessionScope().launch {
            try {
                val resp = DeviceInfoCache.load(snCode)
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                checkKickEx(e)
                val msg = e.message ?: ""
                toastMessage = if (msg.contains("Unable to resolve host", ignoreCase = true) ||
                    msg.contains("No address associated", ignoreCase = true) ||
                    msg.contains("Network is unreachable", ignoreCase = true) ||
                    msg.contains("Failed to connect", ignoreCase = true)) {
                    "网络连接失败，请检查网络"
                } else {
                    "查询设备失败"
                }
            }
        }
    }

    // ── 点击设备 ──
    //
    // ⚠️ 这条路径**不能静默失败**。
    //
    // `/device/info/mac` 查不到设备时返回的是
    // `success=true, errorCode=0, errorMessage="成功", data=null`——它既不是网络错误，
    // 也不是"报错"，只是服务端说"没有这台设备"。而老代码的失败分支只调了 [checkKick]，
    // 它只认「登录 / token / 失效 / 过期 / 认证」这类词，"成功"一个都不匹配，
    // 于是**既不弹窗、也不提示、也没有日志**：用户点一次设备卡片，屏幕上什么都不发生。
    // 用户报的「点击热水器卡片跳不到开关页面」就是这个观感。
    //
    // 现在三种结果分开说：查到了 → 弹详情；服务端说没有这台设备 → 明说；
    // 服务端明确报错 → 把原话转给用户。
    fun fetchDeviceInfo(mac: String) {
        deviceDetailJob?.cancel()
        val cached = nearbyDevices.find { it.mac == mac }?.deviceInfo
        if (cached != null) {
            selectedDevice = cached; showDeviceDetail = true
            deviceDetailJob = sessionScope().launch { refreshDeviceStatus(cached.snCode) }
            return
        }
        deviceDetailJob = sessionScope().launch {
            try {
                val resp = DeviceInfoCache.load(mac)
                if (resp.success && resp.data != null) {
                    selectedDevice = resp.data; showDeviceDetail = true
                    refreshDeviceStatus(resp.data.snCode)
                } else {
                    checkKick(resp.displayMessage)
                    toastMessage = when {
                        // 服务端明确报错（被挤下线由上面的 checkKick 负责），照原话说
                        !resp.success -> resp.displayMessage ?: "获取设备信息失败，请稍后重试"
                        // success=true 但 data=null：服务端确认没有登记这台设备
                        else -> "未查询到该设备，请检查设备码"
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                checkKickEx(e)
                val msg = e.message ?: ""
                toastMessage = if (msg.contains("Unable to resolve host", ignoreCase = true) ||
                    msg.contains("No address associated", ignoreCase = true) ||
                    msg.contains("Network is unreachable", ignoreCase = true) ||
                    msg.contains("Failed to connect", ignoreCase = true)) {
                    "网络连接失败，请检查网络"
                } else {
                    // 以前这里什么都不说，异常被整个吞掉，同样是"点了没反应"
                    "获取设备信息失败，请稍后重试"
                }
            }
        }
    }

    private suspend fun refreshDeviceStatus(snCode: String, discovered: DeviceInfo? = null) {
        try {
            val session = NetworkModule.session()
            val context = ProjectAuthRepository.forDevice(snCode, discovered)
            val q = NetworkModule.apiService.queryUsingSafe(snCode = snCode,
                auth = context.fields())
            ProjectAuthRepository.requireCurrent(session)
            when (q.deviceInUse) {
                true -> {
                    val orderNo = q.ownedOrderNo(snCode)
                    ownerBySn[snCode] = orderNo != null
                    if (orderNo != null) {
                        val info = discovered ?: selectedDevice?.takeIf { it.snCode == snCode }
                            ?: nearbyDevices.firstOrNull { it.deviceInfo?.snCode == snCode }?.deviceInfo
                        if (info != null) {
                            val saved = activeOrders.firstOrNull { it.snCode == snCode }
                            if (saved == null || saved.orderNo.isBlank()) {
                                ShowerController.ensureActiveOrder(snCode, orderNo, info)
                                syncActiveOrdersFromPrefs()
                            }
                        }
                        clearOccupiedIfStale(snCode)
                    }
                }
                false -> {
                    ownerBySn.remove(snCode)
                    clearOccupiedIfStale(snCode)
                }
                null -> checkKick(q.displayMessage)
            }
        } catch (e: CancellationException) {
            throw e
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
        val active = ShowerController.activeOrderFor(sn)
        if (active != null) {
            restoreShowerScreen(phone, ShowerOrderTarget.capture(active))
            return
        }
        if (!hasEligibleLastDevice) {
            toastMessage = "请重新选择设备"
            return
        }
        val mac = lastDeviceMac.ifEmpty { return }
        loadDeviceAndStart(phone, mac, sn)
    }

    private fun loadDeviceAndStart(phone: String, mac: String, snCode: String, target: ShowerOrderTarget? = null) {
        if (isShowering || isStartingShower || isStopping) return
        isStartingShower = true
        sessionScope().launch {
            val device = try {
                val resp = DeviceInfoCache.load(mac)
                resp.data?.takeIf { resp.success && it.snCode == snCode }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } finally {
                isStartingShower = false
            }
            if (device == null) {
                toastMessage = "获取设备信息失败"
                return@launch
            }
            if (target != null && target.currentOrder() == null) {
                toastMessage = "原订单已结束"
                return@launch
            }
            if (target == null && (PrefsHelper.lastDeviceSnCode != snCode ||
                    !RoomBindingPlugin.allows(device.deviceName.orEmpty()))) {
                toastMessage = "请重新选择设备"
                return@launch
            }
            selectedDevice = device
            startShower(phone, target)
        }
    }

    internal fun toggleHomeSection(section: HomeDeviceSection) {
        updateHomeLayout { it.toggleSection(section) }
    }

    internal fun moveHomeSection(section: HomeDeviceSection, delta: Int) {
        updateHomeLayout { it.moveSection(section, delta) }
    }

    internal fun moveFavorite(key: String, delta: Int) {
        updateHomeLayout { it.moveFavorite(key, delta) }
    }

    internal fun removeFavorite(key: String) {
        updateHomeLayout { it.removeFavorite(key) }
    }

    internal fun favoriteFor(info: DeviceInfo?): FavoriteDevice? {
        val favorite = info?.let { FavoriteDevice.from(it, NetworkModule.session().projectId) } ?: return null
        return homeDeviceLayout.findFavorite(favorite)
    }

    internal fun lastDeviceFavorite(): FavoriteDevice? {
        val mac = MacFormat.normalize(lastDeviceMac) ?: return null
        return homeDeviceLayout.favorites.singleOrNull {
            it.device.snCode == lastDeviceSnCode && MacFormat.normalize(it.device.macAddress) == mac
        }
    }

    internal fun addFavorite(mac: String, info: DeviceInfo? = null, expectedSn: String? = null) {
        if (mac.isBlank() || !PrefsHelper.isLoggedIn) return
        val session = NetworkModule.session()
        sessionScope().launch {
            try {
                val resolved = info ?: DeviceInfoCache.load(mac).let { if (it.success) it.data else null }
                ProjectAuthRepository.requireCurrent(session)
                val favorite = resolved?.let { FavoriteDevice.from(it, session.projectId) }
                if (favorite == null || (!expectedSn.isNullOrBlank() && favorite.device.snCode != expectedSn)) {
                    toastMessage = "设备信息未确认，暂无法收藏"
                    return@launch
                }
                val nearbyIndex = nearbyDevices.indexOfFirst { it.mac == mac && it.deviceInfo == null }
                if (nearbyIndex >= 0) {
                    nearbyDevices[nearbyIndex] = nearbyDevices[nearbyIndex].copy(deviceInfo = favorite.device)
                }
                updateHomeLayout { layout ->
                    if (layout.findFavorite(favorite) != null) layout else layout.toggleFavorite(favorite)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                checkKickEx(e)
                toastMessage = "获取设备信息失败，请重试"
            }
        }
    }

    internal fun openFavorite(favorite: FavoriteDevice) {
        if (showDeviceDetail || isStartingShower || isShowering || !PrefsHelper.isLoggedIn) return
        val session = NetworkModule.session()
        deviceDetailJob?.cancel()
        deviceDetailJob = sessionScope().launch {
            try {
                val resp = DeviceInfoCache.load(favorite.device.macAddress, force = favorite.projectId == null)
                ProjectAuthRepository.requireCurrent(session)
                val info = resp.data?.takeIf { resp.success }
                if (!resp.success || info == null) {
                    checkKick(resp.displayMessage)
                    toastMessage = "获取设备信息失败，请重试"
                    return@launch
                }
                val resolved = FavoriteDevice.from(info, session.projectId)
                if (resolved == null || !favorite.matches(resolved)) {
                    toastMessage = "设备信息已变化，请重新扫码或刷新附近设备"
                    return@launch
                }
                if (favorite.projectId == null) {
                    if (info.projectId?.takeIf { it > 0 } == null) {
                        toastMessage = "设备项目待确认，请稍后重试"
                        return@launch
                    }
                    updateHomeLayout { it.confirmFavorite(favorite, resolved) }
                } else if (resolved.projectId != favorite.projectId) {
                    toastMessage = "设备项目待确认，请稍后重试"
                    return@launch
                }
                if (!showDeviceDetail && !isStartingShower && !isShowering) {
                    selectedDevice = info
                    showDeviceDetail = true
                    refreshDeviceStatus(info.snCode)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                checkKickEx(e)
                toastMessage = "获取设备信息失败，请重试"
            }
        }
    }

    private fun updateHomeLayout(change: (HomeDeviceLayout) -> HomeDeviceLayout) {
        val owner = NetworkModule.session().owner
        if (!PrefsHelper.isLoggedIn || homeLayoutOwner != owner) return
        val updated = change(homeDeviceLayout)
        if (updated == homeDeviceLayout) return
        HomeDevicePreferences.save(owner, updated)
        homeDeviceLayout = updated
    }

    // ════════════════════════════════════════════
    fun startShower(phone: String, restoreTarget: ShowerOrderTarget? = null) {
        if (isShowering || isStartingShower || isStopping) return
        val device = selectedDevice ?: return
        val snCode = device.snCode
        if (snCode.isNullOrBlank()) { showerError = ShowerError("设备信息不完整", ShowerErrorKind.BAD_DEVICE); return }
        if (restoreTarget != null && (restoreTarget.snCode != snCode || restoreTarget.currentOrder() == null)) {
            toastMessage = "原订单已结束"
            return
        }

        isStartingShower = true
        sessionScope().launch {
            try {
                showerError = null
                showerSnCode = snCode

                // ① 用户之前确认过这台需要蓝牙（或服务端明确标了 isBle）→ 直接走蓝牙，
                //    不再白试一次云端、再吃一句 306
                // ② 否则按普通设备走云端；万一回 306，会在下面弹框问用户要不要改用蓝牙
                if (device.needsBluetoothControl || PrefsHelper.isBleDevice(snCode)) {
                    startBleShower(device, phone, snCode, restoreTarget = restoreTarget)
                    return@launch
                }

                // 「查询是否已有订单 → 开阀 → 轮询确认 → 落盘」这套流程在 ShowerController 里，
                // 桌面小组件也走同一份逻辑；这里只负责 MQTT、界面状态与结果提示
                mqttManager?.connect(phone)
                val outcome = ShowerController.openValve(snCode, device, restoreTarget = restoreTarget)

                when (outcome) {
                    is OpenOutcome.Resumed -> {
                        syncActiveOrdersFromPrefs()
                        // 恢复订单：从持久化恢复自动关停剩余时间
                        enterShowerState(outcome.orderNo, snCode, device, PrefsHelper.getAutoDisconRemain(snCode))
                    }

                    is OpenOutcome.Opened -> {
                        syncActiveOrdersFromPrefs()
                        val orderNo = ShowerController.activeOrderFor(snCode)?.orderNo?.takeIf { it.isNotBlank() }
                        enterShowerState(orderNo, snCode, device, outcome.autoDiscon)
                        if (orderNo == null) startOrderPoll(snCode)
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
                        // 306「设备不在线」：**这台很可能就是蓝牙表**（云端到不了它）。
                        // 弹框问用户要不要改用手机蓝牙——这是唯一可靠的判别方式：
                        // 服务端不给字段、参考项目也不判断，只有用户站在设备旁才知道。
                        if (restoreTarget == null && outcome.errorCode == ERROR_DEVICE_OFFLINE &&
                            device.macAddress.isNotBlank()
                        ) {
                            bleRetryDevice = device
                        }
                        if (restoreTarget == null) showerError = ShowerError(outcome.message, ShowerErrorKind.SERVER_REJECTED)
                        else toastMessage = outcome.message
                        checkKick(outcome.kickHint)
                        mqttManager?.disconnect()
                    }

                    OpenOutcome.Unknown -> {
                        // 开阀请求已受理，轮询结束后仍无确认，保持未知状态。
                        showerError = ShowerError(
                            "开启结果未确认，请检查是否出水，勿重复开启",
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
     * 蓝牙表开阀。
     *
     * 与 [startShower] 主体（4G 表）的区别：
     *
     * | | 4G 表 | 蓝牙表 |
     * |---|---|---|
     * | 连接 | 无需连接 | **必须先连上设备**，还要保持在范围内 |
     * | 耗时 | 1~2 秒 | 数秒（连接 + 等状态帧 + 下费率 + 写费率包） |
     * | MQTT | 用来收实时消费 | **用不上**（蓝牙表不走 MQTT） |
     * | 自动关停 | 服务端下发时间 | 设备自己管 |
     *
     * ⚠️ 这里**不 disconnect**——连接要留给后面的关阀用。
     * 蓝牙表的阀是手机开的，断开连接就再也关不上了。
     */
    private suspend fun startBleShower(device: DeviceInfo, phone: String, snCode: String, restoreTarget: ShowerOrderTarget? = null) {
        val ctx = appContext
        if (ctx == null) {
            showerError = ShowerError("应用上下文丢失，请重试", ShowerErrorKind.UNKNOWN_RESULT)
            return
        }

        // 蓝牙通道互斥：小组件服务可能正在操作同一台设备，两边同时 connectGatt 会互抢连接
        if (!BleArbiter.begin(snCode, "app")) {
            showerError = ShowerError("该设备正在处理其他蓝牙操作，请稍候再试", ShowerErrorKind.UNKNOWN_RESULT)
            return
        }

        val ble = bleController ?: BleController(ctx).also { bleController = it }

        bleProgress = "正在连接设备…"
        val result = try {
            BleShowerController.openValve(
                ble = ble,
                device = device,
                loginCode = PrefsHelper.loginCode,
                telephone = phone,
                restoreTarget = restoreTarget
            )
        } catch (t: Throwable) {
            AppLogger.e("蓝牙开阀异常", t)
            null
        } finally {
            // 临界区（连设备 + 开阀）结束即释放，后面的状态处理不需要独占
            BleArbiter.end(snCode)
        }
        bleProgress = null

        when (result) {
            is BleShowerController.OpenOutcome.Opened -> {
                bleSession = result.session
                // 会话落盘：小组件服务是另一个入口，关阀时要读它（App 退出也能关阀）
                PrefsHelper.bleSessionJson = gson.toJson(result.session)
                // ✅ 开阀成功 ⇒ 这台**确实**是蓝牙表，记下来，以后直接走蓝牙
                PrefsHelper.markBleDevice(snCode)
                AppLogger.i("蓝牙开阀成功，进入使用页")
                // 蓝牙表没有服务端下发的自动关停时间，第四项传 0
                enterShowerState(null, snCode, device, 0)
            }

            is BleShowerController.OpenOutcome.Resumed -> {
                // 恢复用水：设备本来就在出水，不再写费率包、不再重新开阀。
                // 沿用当前 BLE 连接（session）进入使用页，让用户能继续计时、也能关阀。
                bleSession = result.session
                PrefsHelper.bleSessionJson = gson.toJson(result.session)
                AppLogger.i("蓝牙恢复用水，进入使用页")
                enterShowerState(null, snCode, device, 0)
            }

            is BleShowerController.OpenOutcome.Recovered -> {
                // 补结算完成：上一笔遗留账单已结清，但设备要等下次 rateOrder 才生成新
                // randomNumber，立刻开阀会 226。用 toast 温和提示，让用户重新点开阀。
                AppLogger.i("蓝牙补结算完成，提示重新开阀")
                releaseBle()
                toastMessage = com.hualala.linyu.utils.UserFeedback.message(result.message)
            }

            is BleShowerController.OpenOutcome.NotBleDevice -> {
                // 连上了但没有水控服务 ⇒ 用户当初猜错了。
                // **自动撤销标记**，以后不再对这台设备尝试蓝牙——
                // 否则用户每次开阀都要白等一次连接超时。
                PrefsHelper.unmarkBleDevice(snCode)
                showerError = ShowerError(
                    "这台设备不支持蓝牙控制，已切回普通方式，请重试",
                    ShowerErrorKind.BAD_DEVICE
                )
                releaseBle()
            }

            is BleShowerController.OpenOutcome.Failed -> {
                // ⚠️ 这里**不撤销标记**：可能是走远了、设备忙、蓝牙没开，
                // 但设备本身大概率确实是蓝牙表。撤销了反而要用户重新确认一遍。
                showerError = ShowerError(result.message, ShowerErrorKind.SERVER_REJECTED)
                releaseBle()
            }

            is BleShowerController.OpenOutcome.Unconfirmed -> {
                showerError = ShowerError(result.message, ShowerErrorKind.UNKNOWN_RESULT)
                // ⚠️ **故意不断开**：费率包可能已经写进设备、水正在流，
                // 断开等于把唯一的关阀通道也扔掉了。留在使用页让用户能停。
            }

            null -> {
                showerError = ShowerError("蓝牙开阀失败，请重试", ShowerErrorKind.NETWORK)
                releaseBle()
            }
        }
    }

    /**
     * 释放蓝牙连接。
     *
     * ⚠️ 只在**确定没有进行中的用水**时调用。蓝牙表断开后无法再关阀，
     * 所以「开阀未确认」那种情况必须保留连接。
     */
    private fun releaseBle() {
        bleController?.disconnect()
        bleController = null
        bleSession = null
        // 会话已结束/失效，落盘那份也要清——否则小组件会拿一条过期会话去关阀
        PrefsHelper.bleSessionJson = ""
    }

    /**
     * 桌面上有小组件时刷新一下。
     * 小组件自己不持有状态、每次都从 Prefs 现读，所以这里只要推它重绘即可。
     */
    private fun refreshWidgets() {
        appContext?.let { LinYuWidget.refreshAll(it) }
    }

    internal var manualBalanceRevision by mutableStateOf(0)
        private set

    internal fun saveManualBalance(account: ProjectAuthContext, value: String): Boolean {
        if (!PrefsHelper.saveManualBalance(account, value)) return false
        manualBalanceRevision++
        appContext?.let { LinYuWidget.refreshDeviceFilter(it) }
        return true
    }

    /**
     * 把账单快照存给小组件。
     * 小组件自己拉不了账单，2x4 的「账单」页读的就是这份数据，界面上会标同步时间。
     */
    private fun cacheBillsForWidget() {
        val balanceRows = balanceBills.toSet()
        val list = (billList.take(2) + balanceBills).distinct().map { b ->
            val useForBalance = b in balanceRows
            CachedBill(
                emoji = b.consumeBillDTO.deviceEmoji,
                name = b.consumeBillDTO.displayDesc,
                timeText = b.consumeBillDTO.consumeDate.take(16),
                moneyText = "-¥ " + b.consumeBillDTO.consumeMoney,
                // 原始值一并存下来：小组件的余额估算要拿它们做减法，
                // 靠上面那两个格式化字符串反解是解不出来的
                rawTimeMs = BalanceEstimator.billTimeMs(b.consumeBillDTO.consumeDate).takeIf { useForBalance },
                rawMoney = b.consumeBillDTO.consumeMoney.toDoubleOrNull().takeIf { useForBalance }
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
    }

    /** 开阀后订单号还没生成，轮询把它补上 */
    private fun startOrderPoll(snCode: String) {
        orderPollJob?.cancel()
        orderPollJob = sessionScope().launch {
            for (i in 0..10) {
                delay(800)
                if (showerSnCode != snCode || currentOrderNo != null) break
                try {
                    val p = NetworkModule.apiService.queryUsingSafe(snCode = snCode,
                        auth = com.hualala.linyu.data.ProjectAuthRepository.forDevice(snCode).fields())
                    if (showerSnCode != snCode || currentOrderNo != null) break
                    val orderNo = p.ownedOrderNo(snCode)
                    if (orderNo != null) {
                        currentOrderNo = orderNo
                        updateOrderNo(snCode, orderNo)
                    } else if (p.deviceInUse != true) checkKick(p.displayMessage)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { checkKickEx(e) }
            }
        }
    }

    private fun updateOrderNo(snCode: String, orderNo: String) {
        if (orderNo.isBlank()) return
        ActiveOrderRepository.update { orders ->
            val i = orders.indexOfFirst { it.snCode == snCode && it.orderNo.isBlank() }
            if (i >= 0) orders[i] = orders[i].copy(orderNo = orderNo)
        }
        syncActiveOrdersFromPrefs()
    }

    private fun enterShowerState(orderNo: String?, snCode: String, device: DeviceInfo, autoDiscon: Int = 0) {
        currentOrderNo = orderNo; isShowering = true
        showerPreDeduct = device.withholdMoney; showerConsumed = 0.0
        showerRemaining = MoneyFormat.format(device.withholdMoney)
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
        showerPageTarget = ShowerOrderTarget.capture(snCode)

        lastDeviceName = device.displayName; lastDeviceMac = device.macAddress; lastDeviceSnCode = snCode; lastDeviceEmoji = device.typeEmoji
        PrefsHelper.lastDeviceName = device.displayName; PrefsHelper.lastDeviceMac = device.macAddress
        PrefsHelper.lastDeviceSnCode = snCode; PrefsHelper.lastDeviceEmoji = device.typeEmoji
        PrefsHelper.lastDeviceRawName = device.deviceName   // 寝室筛选用，见 PrefsHelper
        refreshWidgets()

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
            // Topic 按账号订阅，只有已由设备接口确认的当前订单才能接收金额。
            val orderNo = currentOrderNo?.takeIf { it.isNotBlank() } ?: return
            if (msg.orderNo != orderNo || !isShowering) return
            msg.consumeMoney?.let {
                showerConsumed = it
                showerRemaining = MoneyFormat.format(if (showerPreDeduct - it < 0) 0.0 else showerPreDeduct - it)
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
        finishShower(sn, null, showerPageTarget)
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
        val target = e.target ?: return
        if (target.owner != NetworkModule.session().owner || ShowerController.isRunning(e.snCode)) return
        if (e.replacingOrderOwner == null) ownerBySn.remove(e.snCode)
        else ownerBySn[e.snCode] = e.replacingOrderOwner
        if (!isShowering || showerSnCode != e.snCode || showerPageTarget?.sameOrderAs(target) != true) return

        timerJob?.cancel(); orderPollJob?.cancel()

        if (!e.autoClosed) {
            // 用户自己结束的（通知栏「结束用水」按钮 / 小组件停止）：
            // 直接退出使用页就行，不用再弹一个确认框
            finishShower(e.snCode, e.money?.takeIf { it > 0 }, target, e.replacingOrderOwner)
            return
        }

        // 设备自己超时关的：弹确认框把结果告诉用户
        autoCloseDeviceName = e.deviceName
        autoCloseElapsed = e.elapsedSec
        autoCloseConsumed = e.money   // 可空：null 时弹窗会说「未能获取」而不是 ¥0.00
        autoCloseLoading = false
        autoCloseTarget = target
        showAutoCloseDialog = true
    }

    /** 用户点确认：退出洗澡界面并清理 */
    fun confirmAutoClose() {
        showAutoCloseDialog = false
        val target = autoCloseTarget ?: return
        autoCloseTarget = null
        finishShower(target.snCode, null, target)
    }

    /** 通知与恢复按钮只确认它们所指的原订单。 */
    fun restoreShowerScreen(phone: String, target: ShowerOrderTarget?) {
        if (isShowering || isStartingShower || isStopping) return
        val order = target?.currentOrder()
        if (order == null) {
            toastMessage = "原订单已结束"
            return
        }
        loadDeviceAndStart(phone, order.deviceMac, order.snCode, target)
    }

    /**
     * 最小化使用界面：退出界面但**不结束用水**。
     * 订单保留在 activeOrders，计时器（startedAt）继续累计，可随时通过"恢复"回到界面。
     */
    fun minimizeShower() {
        if (!isShowering || isStopping) return
        timerJob?.cancel()
        orderPollJob?.cancel()
        isShowering = false
        isStopping = false
        showerSnCode = null
        showerPageTarget = null
        currentOrderNo = null
        // 注意：保留 activeOrders 与 PrefsHelper.getStartedAt(snCode)，用水与计时都继续
        try { mqttManager?.disconnect() } catch (_: Exception) {}
        toastMessage = "已返回首页，用水仍在继续"
    }

    // ════════════════════════════════════════════
    fun stopShower(skipNetwork: Boolean = false) {
        if (!isShowering || isStopping) return
        val snCode = showerSnCode ?: return
        val target = showerPageTarget?.takeIf { it.snCode == snCode && it.currentOrder() != null } ?: run {
            toastMessage = "原订单已变化，请刷新设备"
            return
        }

        if (usesBluetoothForStop(snCode, bleSession?.snCode, PrefsHelper.isBleDevice(snCode) ||
                selectedDevice?.let { it.snCode == snCode && it.needsBluetoothControl } == true)) {
            stopBleShower(target)
            return
        }

        // 挤号等场景：loginCode 已失效，跳过网络请求，直接本地清理，避免再次触发挤号
        if (skipNetwork) {
            finishShower(snCode, null, target)
            return
        }

        val deviceName = target.currentOrder()?.deviceName.orEmpty().ifEmpty { "热水器" }
        isStopping = true
        // 关阀等待状态确认期间，通知也显示「正在结束」。
        appContext?.let { Notifier.showStopping(it, deviceName) }
        sessionScope().launch {
            try {
                // ⚠️ orderNo 必须在**关阀之前**敲定：关阀成功后订单就没了，
                // `queryUsing` 再也问不出来。而开阀后 orderNo 是异步轮询补上的，
                // 「开完水马上停」时本地可能还是空串——那就白丢了结算用的那个直答接口。
                val settlementContext = com.hualala.linyu.data.ProjectAuthRepository.forDevice(snCode)
                val settledNo = ShowerController.resolveOrderNo(snCode, target)
                // 关阀 + 确认 + 清本地状态都在 ShowerController 里，小组件的「停止使用」走同一份逻辑
                val outcome = ShowerController.closeValve(snCode, settledNo, target)
                checkKick(outcome.kickHint)

                if (outcome is CloseOutcome.Failed) {
                    toastMessage = com.hualala.linyu.utils.UserFeedback.message(outcome.message, "关闭失败，请重试")
                    return@launch
                }

                // ⚠️ 没确认到设备停了（多半是断网，关阀请求压根没发出去）——
                // **不能退出使用页**。这一页上的「停止」是用户唯一的停止入口，
                // 退出去就等于把他扔在一个「以为停了、其实还在扣钱」的状态里，
                // 而且他想再停一次都找不到按钮。留在这里，让他能重试。
                if (outcome is CloseOutcome.Unconfirmed) {
                    toastMessage = "关闭结果未确认，设备可能仍在出水"
                    AppLogger.w("停止用水未确认：${outcome.message}")
                    return@launch
                }

                val closed = outcome as CloseOutcome.Closed
                val startTime = closed.startTimeMs
                val elapsed = if (startTime > 0) ((System.currentTimeMillis() - startTime) / 1000).toInt() else 0
                finishShower(snCode, null, target, closed.replacingOrderOwner)
                // 界面已退出（不阻塞），后台异步等账单结算后弹金额
                sessionScope().launch {
                    // 和小组件/通知栏那两个入口一样：先挂「结算中」，拿到金额原地更新
                    appContext?.let {
                        Notifier.showSettling(
                            it, Notifier.ID_FINISHED,
                            "使用结束 · $deviceName", elapsed
                        )
                    }
                    val amount = SettlementEngine.settleAmount(
                        settledNo,
                        startTime,
                        snCode,
                        deviceName,
                        settlementContext
                    )
                    if (!isShowering || showerSnCode == snCode) toastMessage = when {
                        // null = 两条路都没拿到金额，是「还不知道」而不是「没花钱」
                        amount == null && closed.replacingOrderOwner != null -> "本次用水已结束，金额请稍后查看账单"
                        amount == null -> "已关闭，金额请稍后查看账单"
                        amount > 0 -> "已停止，本次消费 ${MoneyFormat.withSymbol(amount)}"
                        closed.replacingOrderOwner != null -> "本次用水已结束，本次无消费"
                        else -> "热水器已关闭，本次无消费"
                    }
                    // 结算拿到了新的「上次消费」，让桌面小组件跟上
                    refreshWidgets()

                    // 监控自行移除已关闭设备；同一服务里的其他设备继续监控。
                    appContext?.let { ctx ->
                        Notifier.showFinished(
                            ctx,
                            deviceName,
                            elapsed,
                            amount      // 可空：null 会显示成「结算中」而不是「无消费」
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                checkKickEx(e)
                toastMessage = "停止洗澡失败，请检查网络后重试"
            } finally {
                if (showerPageTarget?.sameOrderAs(target) == true) isStopping = false
                if (isActive && target.currentOrder() != null) {
                    appContext?.let { ShowerWatchService.refreshNotification(it, target) }
                }
            }
        }
    }

    /** 结束该订单。重连使用独立连接，避免断开另一台设备的会话。 */
    private fun stopBleShower(target: ShowerOrderTarget) {
        val snCode = target.snCode
        val order = target.currentOrder()
        val context = appContext
        if (order == null || context == null) {
            toastMessage = if (order == null) "原订单已结束" else "应用上下文丢失，请重试"
            endStopAttempt()
            return
        }
        val affectsPage = isShowering && showerSnCode == snCode
        if (affectsPage && isStopping) return
        if (!BleArbiter.begin(snCode, "app")) {
            toastMessage = "该设备正在处理其他蓝牙操作，请稍候再试"
            endStopAttempt()
            return
        }

        val activeSession = bleSession?.takeIf { it.matchesBleOrder(target, order.orderNo) }
        val liveConnection = bleController?.takeIf { activeSession != null && it.isReady }
        val loginCode = PrefsHelper.loginCode
        val telephone = phone
        if (affectsPage) isStopping = true
        Notifier.showStopping(context, order.deviceName.ifEmpty { "热水器" })
        sessionScope().launch {
            val ble = liveConnection ?: BleController(context)
            try {
                val session = if (liveConnection != null) requireNotNull(activeSession) else {
                    val stored = runCatching {
                        gson.fromJson(PrefsHelper.bleSessionJson, BleShowerController.Session::class.java)
                    }.getOrNull()?.takeIf { it.matchesBleOrder(target, order.orderNo) }
                    val address = activeSession?.mac ?: stored?.mac
                        ?: MacFormat.connectAddressFor(order.deviceMac)
                    if (affectsPage) bleProgress = "正在重新连接设备…"
                    val state = if (ble.connect(address) is BleConnectResult.Ready) ble.query() else null
                    if (state == null) {
                        toastMessage = "蓝牙连接失败，请靠近设备后重试"
                        return@launch
                    }
                    if (target.currentOrder() == null) return@launch
                    if (state.state == 0) {
                        finishShower(snCode, null, target)
                        return@launch
                    }
                    BleShowerController.Session(
                        snCode = snCode, mac = address, protocolType = state.protocolType,
                        randomNumber = state.randomNumber, consumeDate = stored?.consumeDate.orEmpty(),
                        orderNo = order.orderNo, projectId = order.projectId,
                        accountId = order.accountId, authOwner = target.owner
                    )
                }
                if (target.currentOrder() == null) return@launch
                if (affectsPage) bleProgress = "正在关闭设备…"
                when (val outcome = BleShowerController.closeAndSettle(ble, session, loginCode, telephone)) {
                    is BleShowerController.SettleOutcome.Uploaded -> {
                        val yuan = MoneyFormat.milliToYuan(outcome.rawUpMoney)
                        if (yuan != null && yuan > 0) PrefsHelper.recordConsume(snCode, yuan)
                        finishShower(snCode, yuan, target)
                        if (bleController === ble && bleSession === session) releaseBle()
                    }
                    is BleShowerController.SettleOutcome.PartialSettlement -> {
                        finishShower(snCode, null, target)
                        if (affectsPage) toastMessage = "已关闭，金额请稍后查看账单"
                        if (bleController === ble && bleSession === session) releaseBle()
                    }
                    is BleShowerController.SettleOutcome.CloseFailed -> {
                        toastMessage = "关闭结果未确认，设备可能仍在出水"
                        AppLogger.w("蓝牙关阀失败，保留使用页以便重试")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("蓝牙停止异常", e)
                toastMessage = "停止失败，请重试"
            } finally {
                if (liveConnection == null) ble.disconnect()
                BleArbiter.end(snCode)
                if (affectsPage) {
                    bleProgress = null
                    if (showerPageTarget?.sameOrderAs(target) == true) isStopping = false
                }
                endStopAttempt()
                if (isActive && target.currentOrder() != null) {
                    ShowerWatchService.refreshNotification(context, target)
                }
            }
        }
    }

    private fun BleShowerController.Session.matchesBleOrder(target: ShowerOrderTarget, actualOrderNo: String): Boolean =
        snCode == target.snCode && (authOwner == null || authOwner == target.owner) &&
            (orderNo.isBlank() || actualOrderNo.isBlank() || orderNo == actualOrderNo)

    /** 完成停止流程：退出洗澡界面，清理状态，显示结算结果 */
    /**
     * 一次停止尝试结束（**成败都算**）：收掉小组件托管过来的「正在关闭…」并重绘桌面。
     *
     * 小组件的关阀有两种走法，busy 的归属跟着操作走：
     *
     * | 走法 | busy 谁清 |
     * |---|---|
     * | 服务自己关（App 进程不在） | `WidgetBleService` 的 finally |
     * | 转交 App 关（App 握着连接） | **本方法**——转交时 busy 被保留，一直显示「正在关闭…」直到真正关完 |
     *
     * 少了这里的收尾，转交路径的卡片会在关阀中途弹回「使用中」继续计时，
     * 过一两秒才变空闲——用户看到的就是「闪一下正在关闭、又接着计时」。
     */
    private fun endStopAttempt() {
        WidgetBridge.clearBusy()
        refreshWidgets()
    }

    private fun finishShower(snCode: String, consumed: Double?, target: ShowerOrderTarget?, replacingOrderOwner: Boolean? = null) {
        if (target != null) {
            if (target.owner != NetworkModule.session().owner) return
            if (ShowerController.isRunning(snCode) && !ShowerController.clearClosedTarget(snCode, target)) return
        }
        syncActiveOrdersFromPrefs()
        if (replacingOrderOwner == null) ownerBySn.remove(snCode)
        else ownerBySn[snCode] = replacingOrderOwner
        refreshWidgets()
        if (showerSnCode != snCode || (target != null && showerPageTarget?.sameOrderAs(target) != true)) return
        isShowering = false
        isStopping = false

        if (consumed != null && consumed > 0) {
            toastMessage = "已停止，本次消费 ${MoneyFormat.withSymbol(consumed)}"
        } else {
            // 0.0 或 null（结算没拿到金额）都只提示关闭，不弹「消费 ¥0.00」
            toastMessage = if (replacingOrderOwner == null) "热水器已关闭" else "本次用水已结束"
        }

        currentOrderNo = null; showerConsumed = 0.0; showerPreDeduct = 0.0
        showerRemaining = "0.00"; showerElapsedSec = 0
        autoDisConSec = 0
        showerSnCode = null; showerPageTarget = null; timerJob?.cancel(); orderPollJob?.cancel()
        try { mqttManager?.disconnect() } catch (_: Exception) {}
        // ⚠️ 小组件的「正在关闭…」由本进程收尾：转交过来的关阀（见 WidgetBleService），
        // busy 一直保留到这里，清掉后卡片才落到「空闲」。
        WidgetBridge.clearBusy()
        refreshWidgets()
    }

    fun logout(preserveActiveRecovery: Boolean = false) {
        // 掐断所有网络任务（含挤号心跳），避免退出后还有响应回来改状态
        stopTimer()
        sessionJob.cancel()
        sessionJob = SupervisorJob(viewModelScope.coroutineContext[Job])

        // 释放蓝牙连接。能走到这里说明**没有进行中的用水**——
        // 有活跃订单时 `canLogoutVoluntarily` 会先拦住，所以断连是安全的。
        // ⚠️ 蓝牙表断开后就再也关不了阀，这句话是这里唯一的安全保证，别绕过。
        releaseBle()

        // 断开 MQTT 连接
        try { mqttManager?.disconnect() } catch (_: Exception) {}

        // 停止蓝牙扫描
        try { scanner?.stopScan() } catch (_: Exception) {}
        isScanning = false

        // 重置所有状态
        isShowering = false
        showerSnCode = null
        showerPageTarget = null
        autoCloseTarget = null
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
        homeLayoutOwner = ""
        homeDeviceLayout = HomeDeviceLayout()
        deviceDetailJob = null
        nearbyDevices.clear()
        activeOrders.clear()
        ownerBySn.clear()
        resetBillHistory()
        useCodeData = null
        walletInfo = null
        // 账号信息是跟人走的，换账号必须清掉，否则会显示上一任的姓名/学号/余额
        accountInfo = null
        campusBalance = null
        walletBalance = null
        phone = ""
        useCodeLoaded = false
        useCodeLoadSeq++
        changingUseCodeStatus = false
        generatingUseCode = false
        claimingUseCode = false
        useCodeQuota = null
        useCodeError = null
        profileLoadSeq++
        loadingAccountInfo = false
        accountInfoLoaded = false
        accountInfoError = null
        // 未支付账单和代扣锁是**跟账号走的**：不清的话，换账号后开阀失败弹窗会列出
        // 上一任的账单和金额，还能用新账号的凭证去扣它；锁更麻烦——
        // 万一扣款协程在启动前就被取消（logout/挤号都会 cancel sessionJob），
        // 清锁那行永远跑不到，之后所有代扣都会静默失效，只能杀进程
        unpaidBills = emptyList()
        deductingConsumeDate = null
        unpaidSeq++
        // 内存态的「上次设备」也要跟着清。
        //
        // Prefs 那份由 `PrefsHelper.clear()` 负责——它的动态 filter 走
        // `shouldPreserveForRecovery`，而那个函数覆盖了 `lastDevice*` 前缀，
        // 所以正常登出时落盘的七个字段都会被 remove。**内存副本是另一份**，
        // 不清的话，重新登录之后、`initManagers` 重新读 Prefs 覆盖回来之前
        // 那段窗口里，界面读到的还是上一任的设备。
        //
        // ⚠️ 挤号（`preserveActiveRecovery = true`）时**绝不能清**：那时正是要靠
        // 这些字段保住正在用水的设备上下文，清了用户就找不到停止入口了。
        if (!preserveActiveRecovery) {
            lastDeviceName = ""
            lastDeviceMac = ""
            lastDeviceSnCode = ""
            lastDeviceEmoji = "🚿"
        }
        PrefsHelper.clear(preserveActiveRecovery)
        boundRoom = RoomBindingPlugin.effectiveRoom
        if (preserveActiveRecovery) appContext?.let(ShowerWatchService::stop)
    }

    fun isDeviceActive(snCode: String) = ownerBySn.containsKey(snCode) || activeOrders.any { it.snCode == snCode }

    // ── 寝室绑定 / 设备筛选 ──

    var boundRoom by mutableStateOf(RoomBindingPlugin.effectiveRoom)
        private set

    private var pluginOrder by mutableStateOf(PrefsHelper.installedPluginOrder.split(','))

    internal fun orderedPlugins(plugins: List<PluginManifest>): List<PluginManifest> =
        restorePluginOrder(plugins, pluginOrder)

    internal fun moveInstalledPlugin(id: String, targetIndex: Int) {
        val current = orderedPlugins(InstalledPlugins.all).map { it.id }
        val updated = movePlugin(current, id, targetIndex)
        if (updated == current) return
        pluginOrder = updated
        PrefsHelper.installedPluginOrder = updated.joinToString(",")
    }

    fun applyBoundRoom(value: String) {
        RoomBindingPlugin.setRoom(value)
        boundRoom = RoomBindingPlugin.effectiveRoom
        appContext?.let(LinYuWidget::refreshDeviceFilter)
    }

    internal fun installPlugin(context: Context, manifest: PluginManifest): Boolean {
        if (manifest.id == InstalledPlugins.BACKGROUND && !InstalledPlugins.isInstalled(manifest.id) &&
            !BackgroundManager.clearPluginData(context)) return false
        if (!InstalledPlugins.install(manifest)) return false
        refreshPluginState(context)
        return true
    }

    internal fun uninstallPlugin(context: Context, id: String): Boolean {
        if (id == InstalledPlugins.BACKGROUND && InstalledPlugins.isInstalled(id) &&
            !BackgroundManager.clearPluginData(context)) return false
        if (!InstalledPlugins.uninstall(id)) return false
        refreshPluginState(context)
        return true
    }

    private fun refreshPluginState(context: Context) {
        boundRoom = RoomBindingPlugin.effectiveRoom
        LinYuWidget.refreshDeviceFilter(context.applicationContext)
    }

    val hasBoundRoom: Boolean get() = boundRoom.isNotBlank()

    fun matchesBoundRoom(deviceName: String): Boolean = DeviceInfo.inSameRoom(boundRoom, deviceName)

    val hasEligibleLastDevice: Boolean
        get() = lastDeviceMac.isNotBlank() && lastDeviceSnCode.isNotBlank() &&
            matchesBoundRoom(ShowerController.roomFilterName())

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
                    if (resp.success) {
                        // 顺手把钱包余额刷新了。这条心跳本来就在跑，
                        // 不额外增加请求量，却让余额每 25 秒自己跟上——
                        // 洗完澡余额会变，不用等用户手动下拉刷新。
                        resp.data?.money?.toDoubleOrNull()?.let {
                            walletBalance = it
                            PrefsHelper.walletBalance = it.toString()
                        }
                    } else checkKick(resp.displayMessage)
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
        resetBillHistory()
        PrefsHelper.clear(preserveActiveRecovery = true)
        appContext?.let(ShowerWatchService::stop)
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
        ownerBySn.clear()
        resetBillHistory()
        stopTimer()
        try { mqttManager?.disconnect() } catch (_: Exception) {}
        kickedOut = false
        // 换账号了，手机号跟着换（新会话的认证参数已经写进 Prefs，这里同步到界面状态）
        phone = PrefsHelper.telephone
        useCodeQuota = PrefsHelper.useCodeQuota()
        useCodeLoadSeq++
        changingUseCodeStatus = false
        generatingUseCode = false
        claimingUseCode = false
        useCodeError = null
        profileLoadSeq++
        loadingAccountInfo = false
        accountInfoLoaded = PrefsHelper.userName.isNotBlank() || PrefsHelper.userStudentId.isNotBlank()
        accountInfoError = null
    }

    fun refreshWallet() {
        sessionScope().launch {
            try {
                val resp = NetworkModule.apiService.getWalletSafe()
                if (resp.success) {
                    walletInfo = resp.data
                    // 钱包余额落盘：一卡通拿不到时它就是真实余额
                    // （见 BalanceEstimator 的三档优先级）。
                    // ⚠️ 用 `money` 字符串字段而不是 `accountRealMoney` 数字：
                    //    `money` / `accountRealMoneyStr` 已经是**元**（如 "55.460"），
                    //    而 `accountRealMoney` 是厘（55460），单位得自己除 1000——
                    //    服务端万一换单位就会静默错 1000 倍，字符串字段没这个风险。
                    resp.data?.money?.toDoubleOrNull()?.let {
                        walletBalance = it
                        PrefsHelper.walletBalance = it.toString()
                    }
                } else checkKick(resp.displayMessage)
            } catch (e: Exception) {
                checkKickEx(e)
                val msg = e.message ?: ""
                if (msg.contains("Unable to resolve host", ignoreCase = true) ||
                    msg.contains("No address associated", ignoreCase = true) ||
                    msg.contains("Network is unreachable", ignoreCase = true) ||
                    msg.contains("Failed to connect", ignoreCase = true)) {
                    toastMessage = "网络连接失败，请检查网络"
                }
            }
        }
    }

    fun loadUseCode() {
        if (changingUseCodeStatus || claimingUseCode) return
        val seq = ++useCodeLoadSeq
        sessionScope().launch {
            try {
                val resp = NetworkModule.apiService.getUseCodeSafe()
                if (seq != useCodeLoadSeq) return@launch
                if (resp.success && resp.data != null) useCodeData = resp.data
                // 无论成功与否都算「拉过了」——网络失败时停在「加载中」比显示
                // 「尚未领取」更糟，用户会以为 App 卡住了，而重进页面就会重拉
                useCodeLoaded = true
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (seq == useCodeLoadSeq) useCodeLoaded = true }
        }
    }

    /** 同一时刻只发送一次开关请求；确认成功后更新卡片，失败保留原状态。 */
    fun setUseCodeEnabled(enabled: Boolean) {
        if (changingUseCodeStatus || claimingUseCode || !useCodeLoaded) return
        changingUseCodeStatus = true
        val seq = ++useCodeLoadSeq
        sessionScope().launch {
            try {
                val status = if (enabled) 1 else 0
                val response = NetworkModule.apiService.updateUseCodeStatusSafe(status, NetworkModule.authFields())
                if (seq != useCodeLoadSeq) return@launch
                if (response.success) useCodeData = (useCodeData ?: UseCodeData()).copy(useCodeStatus = status)
                else toastMessage = com.hualala.linyu.utils.UserFeedback.message(response.displayMessage, "操作失败，请重试")
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (seq == useCodeLoadSeq) toastMessage = "操作失败，请重试" }
            finally { if (seq == useCodeLoadSeq) changingUseCodeStatus = false }
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
        val reason = useCodeBlockReason
        if (reason != null || generatingUseCode || claimingUseCode) {
            useCodeError = reason ?: "请稍候再试"
            onResult(null)
            return
        }
        generatingUseCode = true
        useCodeError = null
        val owner = PrefsHelper.useCodeOwner()
        val requestSession = sessionJob
        sessionScope().launch {
            val r = try {
                val resp = NetworkModule.apiService.generateUseCodeSafe(NetworkModule.authFields())
                if (requestSession !== sessionJob || owner != PrefsHelper.useCodeOwner()) return@launch
                UseCodePolicy.quotaFromResponse(resp.data?.remainTimes, resp.errorCode)?.let {
                    useCodeQuota = it
                    PrefsHelper.saveUseCodeQuota(owner, it)
                }
                val code = resp.data?.useCode
                if (resp.success && !code.isNullOrEmpty()) {
                    code to (resp.data?.remainTimes ?: -1)
                } else {
                    useCodeError = if (resp.errorCode == 79) UseCodePolicy.EXHAUSTED_MESSAGE
                        else com.hualala.linyu.utils.UserFeedback.message(resp.displayMessage, "换码失败，请重试")
                    null
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                useCodeError = "换码失败，请重试"
                null
            } finally { if (requestSession === sessionJob) generatingUseCode = false }
            onResult(r)
        }
    }

    /** 「确定领取」：把换出来的候选码设为当前生效的使用码。成功返回 true */
    fun claimUseCode(useCode: String, onResult: (Boolean) -> Unit) {
        if (claimingUseCode || generatingUseCode || changingUseCodeStatus || useCode.isBlank()) {
            onResult(false)
            return
        }
        claimingUseCode = true
        val seq = ++useCodeLoadSeq
        sessionScope().launch {
            val ok = try {
                NetworkModule.apiService
                    .setUseCodeSafe(useCode, NetworkModule.authFields())
                    .success
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                false
            } finally { if (seq == useCodeLoadSeq) claimingUseCode = false }
            if (seq != useCodeLoadSeq) return@launch
            if (ok) {
                useCodeData = (useCodeData ?: UseCodeData()).copy(useCode = useCode, resetAvailability = 0)
                loadUseCode()
            }
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
        if (!PrefsHelper.isLoggedIn || loadingAccountInfo) return
        loadingAccountInfo = true
        accountInfoError = null
        val seq = ++profileLoadSeq
        sessionScope().launch {
            try {
                val response = NetworkModule.apiService.getAccountInfoSafe()
                if (seq != profileLoadSeq) return@launch
                if (!response.success) {
                    accountInfoError = "信息加载失败，点击重试"
                    checkKick(response.displayMessage)
                    return@launch
                }
                response.data?.let {
                    applyIdentity(it.name, it.idCardNumber, it)
                }
                // 姓名靠学校同步学籍数据，没同步的账号 /account/info 返回的 name 就是 null
                // （实测同学的两个号都是这样）。换个接口再要一次——绑定卡信息里也有姓名和学号，
                // 两个接口数据来源不同，一个没有另一个可能有。
                if (PrefsHelper.userName.isEmpty() || PrefsHelper.userStudentId.isEmpty()) {
                    NetworkModule.apiService.getBindCardInfoSafe().data?.let {
                        if (seq != profileLoadSeq) return@launch
                        applyIdentity(it.name, it.idCardNumber, null)
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (seq == profileLoadSeq) accountInfoError = "信息加载失败，点击重试" }
            finally {
                if (seq == profileLoadSeq) {
                    accountInfoLoaded = true
                    loadingAccountInfo = false
                }
            }
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

        /**
         * 事后对账补发通知的时间窗。
         *
         * 结算当时可能因为服务端还没算完而误判成「无消费」，账单下来后对账能拿到
         * 真金额。但那可能是几小时之后——那时用户早就不在等这条通知了，
         * 凭空弹一条「消费 ¥x.xx」只会吓人一跳。所以只补最近 30 分钟内的。
         */
        private const val SETTLEMENT_NOTIFY_GRACE_MS = 30 * 60 * 1000L
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
     * 服务端明确不能提供余额时清除上次结果，临时请求失败时保留。
     */
    fun loadCampusBalance() {
        if (!PrefsHelper.isLoggedIn) return
        val session = NetworkModule.session()
        campusBalanceLoadJob?.cancel()
        campusBalanceLoadJob = sessionScope().launch {
            try {
                val response = NetworkModule.apiService.getCampusUserInfoSafe()
                ProjectAuthRepository.requireCurrent(session)
                val amount = when (val update = BalanceEstimator.campusBalanceUpdate(response)) {
                    CampusBalanceUpdate.Keep -> return@launch
                    CampusBalanceUpdate.Clear -> null
                    is CampusBalanceUpdate.Set -> update.amount
                }
                PrefsHelper.campusBalance = amount?.toString().orEmpty()
                PrefsHelper.campusBalanceTime = if (amount == null) 0L else System.currentTimeMillis()
                campusBalance = amount
                refreshWidgets()
                // 这个接口顺带也给姓名和学号。它是从**校园卡**那边查的，
                // 和 /account/info 的数据来源不同——学校没同步学籍时前者为 null、
                // 这里却有值，正好互补。以前只取了学号，把姓名漏了。
                if (response.success) response.data?.let {
                    applyIdentity(it.studentName, it.studentNumber, null)
                }
            } catch (e: CancellationException) {
                throw e
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
            if (abs(e.rssi - device.rssi) > 5) nearbyDevices[idx] = e.copy(rssi = device.rssi)
        } else {
            nearbyDevices.add(device)
        }

        // ⚠️ 每台设备**每轮扫描只查一次**。
        //
        // 扫描回调对同一台设备每 ~200ms 就来一条，而"只要这台还没有 deviceInfo 就再查一次"
        // 会让**查不到**的设备被无限重查（实测日志里 13 秒对同一个 MAC 发了 35 次）。
        // 设备信息在一轮扫描内不会变，想重试交给下拉刷新（会重新 startScan 并清空本集合）。
        if (queriedThisScan.add(device.mac)) fetchInfo(device.mac)
    }

    private fun fetchInfo(mac: String) {
        sessionScope().launch {
            try {
                val resp = DeviceInfoCache.load(mac)
                if (resp.success && resp.data != null) {
                    val info = resp.data; val i = nearbyDevices.indexOfFirst { it.mac == mac }
                    if (i >= 0) nearbyDevices[i] = nearbyDevices[i].copy(deviceInfo = info)
                    refreshDeviceStatus(info.snCode, info)
                } else checkKick(resp.displayMessage)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { checkKickEx(e) }
        }
    }

    // ── 账单 ──
    private fun resetBillHistory() {
        billLoadSequence++
        billLoadJob?.cancel()
        billLoadJob = null
        billList = emptyList()
        billHistory = emptyList()
        balanceBills = emptyList()
        billRefreshFailed = false
        isLoadingBills = false
        billsLoaded = false
    }

    private fun publishBillHistory(bills: List<BillItem>, defaultBills: List<BillItem>, defaultLoaded: Boolean) {
        billHistory = BillHistoryCache.newestFirst(bills)
        billList = billHistory.take(20)
        balanceBills = BillHistoryCache.newestFirst(defaultBills)
        billsLoaded = defaultLoaded
    }

    private data class BillMonthResult(
        val source: ProjectAuthContext,
        val bills: List<BillItem>,
        val refreshed: Boolean
    )

    private suspend fun loadBillMonth(
        session: ProjectAuthContext,
        source: ProjectAuthContext,
        month: YearMonth
    ): BillMonthResult {
        val cacheKey = BillHistoryCache.monthAccountKey(session.owner, source, month)
        val fresh = try {
            val response = NetworkModule.apiService.getProjectBillListSafe(month.toString(), session, source)
            if (response.success) {
                BillHistoryCache.fromProject(response.data.orEmpty(), source)
            } else null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 其他项目的权限或接口异常不能使默认账号退出登录。
            if (source == session) checkKickEx(e)
            null
        }
        ProjectAuthRepository.requireCurrent(session)
        if (fresh != null) PrefsHelper.saveCachedBills(cacheKey, fresh)
        val bills = fresh ?: PrefsHelper.getCachedBills(cacheKey)
        return BillMonthResult(source, bills, fresh != null)
    }

    fun loadBills() {
        val session = NetworkModule.session()
        if (session.loginCode.isBlank()) return
        val sequence = ++billLoadSequence
        billLoadJob?.cancel()
        billLoadJob = sessionScope().launch {
            isLoadingBills = true
            try {
                val currentMonth = YearMonth.now()
                val months = (0L..2L).map(currentMonth::minusMonths)
                val cached = ProjectAuthRepository.knownAccounts(session).flatMap { source ->
                    months.flatMap { month ->
                        PrefsHelper.getCachedBills(BillHistoryCache.monthAccountKey(session.owner, source, month))
                    }
                }
                if (cached.isNotEmpty()) {
                    val defaultCached = cached.filter(::isDefaultProjectBill)
                    publishBillHistory(cached, defaultCached, billsLoaded || defaultCached.isNotEmpty())
                }

                // 升级后的旧会话也会发现可查账单的项目，无需重新登录。
                ProjectAuthRepository.refreshAccounts(session)
                val accounts = ProjectAuthRepository.knownAccounts(session)
                val results = accounts.flatMap { source -> months.map { source to it } }.chunked(3).flatMap { batch ->
                    coroutineScope {
                        batch.map { (source, month) -> async { loadBillMonth(session, source, month) } }.awaitAll()
                    }
                }
                ProjectAuthRepository.requireCurrent(session)
                val defaultMonths = results.filter { it.source == session }
                val defaultBills = defaultMonths.flatMap { it.bills }
                publishBillHistory(
                    results.flatMap { it.bills },
                    defaultBills,
                    defaultMonths.any { it.refreshed || it.bills.isNotEmpty() }
                )
                billRefreshFailed = results.any { !it.refreshed }
                cacheBillsForWidget()
                refreshWidgets()

                val freshDefaultBills = defaultMonths.filter { it.refreshed }.flatMap { it.bills }
                val pendingBefore = PrefsHelper.getPendingSettlements()
                val reconciliation = SettlementEngine.reconcilePending(pendingBefore, freshDefaultBills)
                val pendingBySn = pendingBefore.associateBy { it.snCode }
                reconciliation.updates.forEach { update ->
                    PrefsHelper.recordConsume(update.snCode, update.amount, pendingBySn[update.snCode]?.createdAt ?: 0L)

                    // 结算那会儿要是误判成了「无消费」，这条通知现在还挂着错误金额——
                    // 拿到了真金额就得把它改成对的，否则用户看到的一直是错的。
                    //
                    // ⚠️ 只补最近 [SETTLEMENT_NOTIFY_GRACE_MS] 内的。超过这个时间
                    // 用户早就不在等这条通知了，凭空弹一条「消费 ¥x.xx」只会吓人一跳。
                    val p = pendingBySn[update.snCode] ?: return@forEach
                    val ageMs = System.currentTimeMillis() - p.createdAt
                    if (ageMs <= SETTLEMENT_NOTIFY_GRACE_MS) {
                        appContext?.let { ctx ->
                            Notifier.showFinished(
                                ctx,
                                p.deviceName.ifEmpty { "热水器" },
                                p.elapsedSec,
                                update.amount
                            )
                        }
                        AppLogger.i(
                            "结算对账补发通知：${p.snCode} ¥${update.amount}（延迟 ${ageMs / 1000}s）"
                        )
                    }
                }
                val pendingNow = PrefsHelper.getPendingSettlements()
                PrefsHelper.savePendingSettlements(pendingNow.filter { it !in pendingBefore || it in reconciliation.remaining })
                // 遍历已拉取的账单找该设备的最新有效消费，不要求它恰好是整个账户的第一笔。
                val lastSn = PrefsHelper.lastDeviceSnCode
                if (lastSn.isNotEmpty() && com.hualala.linyu.data.ProjectAuthRepository.knownContext(lastSn)?.projectId == PrefsHelper.projectId) {
                    ConsumptionCachePolicy.latestForDevice(freshDefaultBills, PrefsHelper.lastDeviceRawName,
                        PrefsHelper.lastDeviceName, PrefsHelper.lastDeviceTypeName)?.let {
                        PrefsHelper.recordConsume(lastSn, it.amount, it.atMs)
                    }
                }
                refreshWidgets()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (NetworkModule.session() == session) billRefreshFailed = true
            } finally {
                if (sequence == billLoadSequence) isLoadingBills = false
            }
        }
    }
}
