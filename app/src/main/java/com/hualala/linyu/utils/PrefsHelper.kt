package com.hualala.linyu.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.hualala.linyu.model.ActiveOrder

object PrefsHelper {
    /** 加密存储的文件名 */
    private const val FILE_ENCRYPTED = "linyu_prefs"

    /**
     * 降级用的明文文件名。
     *
     * ⚠️ **必须和加密那份不同名**。以前两者都叫 `linyu_prefs`，于是：
     * - 加密初始化失败退回明文时，写的是**同名文件**，上层完全无感知
     * - 更要命的是反过来——某次加密又初始化成功了，它会去读**同一份明文文件**，
     *   发现格式不对（不是加密格式）后要么抛异常、要么当成空文件，
     *   用户看到的是「登录态莫名其妙没了」
     *
     * 分开之后两种情况各自独立：加密能用了就读加密那份（哪怕它是空的、
     * 需要重新登录），明文那份就静静躺在那儿不再被碰。
     *
     * ⚠️ **升级影响**：极少数设备上（老版本曾降级过）`linyu_prefs` 里其实是**明文**。
     * 这次改动之后，`EncryptedSharedPreferences` 会去解它、解不开，于是落到新的空文件上，
     * 那台设备会**要求重新登录一次**。
     *
     * 这是**有意不做迁移**的：那份数据本来就是明文躺在一个「看起来已加密」的文件里，
     * 把它原样抄进新的明文文件只会让这个状态延长。宁可让用户重登一次。
     */
    private const val FILE_PLAIN = "linyu_prefs_plain"

    private lateinit var prefs: SharedPreferences

    /** 本次运行是否退到了明文存储。给「我的 → 关于」之类的诊断位置留的观察口 */
    @Volatile var usingPlaintextFallback: Boolean = false
        private set

    private val gson = Gson()

    /** 是否已经 init 过。小组件可能在没有 Activity 的新进程里被唤起，需要一个幂等的判断 */
    val isInitialized: Boolean get() = ::prefs.isInitialized

    fun init(context: Context) {
        val encrypted = try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                FILE_ENCRYPTED,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // 加密初始化失败（设备不支持 / keystore 损坏）不能崩，否则 App 直接起不来。
            // 但也不能像以前那样**悄悄**退回一个同名明文文件——那是「看起来在加密、其实没有」，
            // 而且下次加密恢复后会读到格式不对的同名文件。
            null
        }

        if (encrypted != null) {
            prefs = encrypted
            usingPlaintextFallback = false
        } else {
            prefs = context.getSharedPreferences(FILE_PLAIN, Context.MODE_PRIVATE)
            usingPlaintextFallback = true
            // 日志脱敏只认手机号之类，这里只写一句「发生了降级」，不带任何凭证
            AppLogger.w("加密存储初始化失败，本次运行使用明文存储（$FILE_PLAIN）")
        }
    }

    // ── Auth ──
    var loginCode: String get() = prefs.getString("loginCode", "") ?: ""; set(v) = prefs.edit().putString("loginCode", v).apply()
    var userId: String get() = prefs.getString("userId", "") ?: ""; set(v) = prefs.edit().putString("userId", v).apply()
    var accountId: String get() = prefs.getString("accountId", "") ?: ""; set(v) = prefs.edit().putString("accountId", v).apply()
    var projectId: String get() = prefs.getString("projectId", "") ?: ""; set(v) = prefs.edit().putString("projectId", v).apply()
    var telephone: String get() = prefs.getString("telephone", "") ?: ""; set(v) = prefs.edit().putString("telephone", v).apply()
    var userName: String get() = prefs.getString("userName", "") ?: ""; set(v) = prefs.edit().putString("userName", v).apply()

    /**
     * 学号（`/account/info` 的 idCardNumber）。
     *
     * 登录响应里其实也有（`userAccount.idCardNumber`），但我们的模型以前没解析，
     * 所以老用户的 Prefs 里是空的——拉一次 /account/info 就会补上。
     */
    var userStudentId: String get() = prefs.getString("userStudentId", "") ?: ""
        set(v) = prefs.edit().putString("userStudentId", v).apply()

    /**
     * 一卡通**真实余额**（`/settlement/campus/userInfo` 的 amount）。
     *
     * 空串 = 拿不到，两种情况：学生没签约免密支付、或者压根还没拉过。
     * 这时界面回退到「初始余额 − 账单消费」的本地估算。
     */
    var campusBalance: String get() = prefs.getString("campusBalance", "") ?: ""
        set(v) = prefs.edit().putString("campusBalance", v).apply()

    var campusBalanceTime: Long get() = prefs.getLong("campusBalanceTime", 0L)
        set(v) = prefs.edit().putLong("campusBalanceTime", v).apply()
    var schoolName: String get() = prefs.getString("schoolName", "金华职业技术大学") ?: "金华职业技术大学"; set(v) = prefs.edit().putString("schoolName", v).apply()
    val isLoggedIn: Boolean get() = loginCode.isNotEmpty()
    fun saveAuth(lc: String, uid: String, aid: String, pid: String, phone: String, name: String?) {
        loginCode = lc; userId = uid; accountId = aid; projectId = pid; telephone = phone; userName = name ?: ""
    }
    fun clear() {
        // 保留余额和填写时间
        val bal = manualBalance; val btime = manualBalanceTime
        try {
            // 注意：EncryptedSharedPreferences 的 edit().clear() 有已知崩溃 bug
            // （内部遍历解密所有 key，遇无法解密的 key 抛 SecurityException）。
            // 改用逐个 remove() 静态 key，每个 remove 只处理单个 key，不会触发全部遍历。
            val editor = prefs.edit()
            editor.remove("loginCode").remove("userId").remove("accountId")
                .remove("projectId").remove("telephone").remove("userName")
                .remove("lastDeviceName").remove("lastDeviceMac").remove("lastDeviceSnCode")
                .remove("lastDeviceEmoji").remove("boundRoom").remove("activeOrders")
                .remove("lastConsumeMoney").remove("lastConsumeTime")
                .remove("lastDeviceTypeName").remove("lastDeviceWithholdMoney").remove("widgetNearbyJson").remove("widgetBillJson")
                .remove("widgetNearbyTime").remove("widgetBillTime")
                // 占用状态是跟设备走的，换账号后不适用
                .remove("occupiedSnCode")
                // 学号和余额是账号数据，换账号必须清掉，否则会显示上一任的
                .remove("userStudentId").remove("campusBalance").remove("campusBalanceTime")
            // startedAt_/autoDiscon_ 的 key 是「前缀 + snCode」，不是固定名，
            // 原来写成 remove("startedAt_") 是删不掉的——换个账号登录后，
            // 上一任的计时器还在，界面会显示莫名其妙的已用时长。这里按前缀扫掉。
            prefs.all.keys
                .filter {
                    it.startsWith("startedAt_") || it.startsWith("autoDiscon_") || it.startsWith("consume_")
                }
                .forEach { editor.remove(it) }
            editor.apply()
        } catch (_: Exception) {
            // 兜底：即使加密存储清理异常也不崩溃，登录态由内存态管理
        }
        manualBalance = bal; manualBalanceTime = btime
    }

    // ── Last device ──
    var lastDeviceName: String get() = prefs.getString("lastDeviceName", "") ?: ""; set(v) = prefs.edit().putString("lastDeviceName", v).apply()

    /**
     * 上次使用设备的**原始**设备名（`DeviceInfo.deviceName`），只用来做寝室筛选。
     *
     * ⚠️ 不能拿 [lastDeviceName] 去筛寝室。[lastDeviceName] 存的是
     * `DeviceInfo.displayName`，也就是 [com.hualala.linyu.model.DeviceInfo.formatDeviceName]
     * 处理过的**显示名**——它会把 `-3层-` 里的楼层去掉、把结尾的「洗手台」换成「房」。
     *
     * 而寝室键是从**原始名**取出来的（`龙川北苑-3号楼南-3层-320`）。两边算出来的键
     * 一个带楼层一个不带，永远不相等：绑定寝室后小组件会一直显示「请先选择设备」，
     * 哪怕这台设备就在绑定的寝室里。实测就是这么挂的。
     *
     * 老用户没有这个字段（空串），调用方回退到 [lastDeviceName]——用老的短绑定值
     * （`320房`）时靠 `endsWith` 兜底仍然能匹配上。
     */
    var lastDeviceRawName: String get() = prefs.getString("lastDeviceRawName", "") ?: ""; set(v) = prefs.edit().putString("lastDeviceRawName", v).apply()

    var lastDeviceMac: String get() = prefs.getString("lastDeviceMac", "") ?: ""; set(v) = prefs.edit().putString("lastDeviceMac", v).apply()
    var lastDeviceSnCode: String get() = prefs.getString("lastDeviceSnCode", "") ?: ""; set(v) = prefs.edit().putString("lastDeviceSnCode", v).apply()
    var lastDeviceEmoji: String get() = prefs.getString("lastDeviceEmoji", "🚿") ?: "🚿"; set(v) = prefs.edit().putString("lastDeviceEmoji", v).apply()

    // ── 绑定的寝室（设备筛选关键词） ──
    var boundRoom: String get() = prefs.getString("boundRoom", "") ?: ""; set(v) = prefs.edit().putString("boundRoom", v).apply()

    // ── 「我的」页面卡片顺序 / 已隐藏卡片（逗号分隔的枚举名） ──
    var userCardOrder: String get() = prefs.getString("userCardOrder", "") ?: ""; set(v) = prefs.edit().putString("userCardOrder", v).apply()
    var userHiddenCards: String get() = prefs.getString("userHiddenCards", "") ?: ""; set(v) = prefs.edit().putString("userHiddenCards", v).apply()

    // ── 自定义背景：两套独立配置（scope = home / shower），与深浅模式完全无关 ──
    fun bgGetBool(scope: String, name: String, def: Boolean) = prefs.getBoolean("bg_${scope}_$name", def)
    fun bgPutBool(scope: String, name: String, v: Boolean) = prefs.edit().putBoolean("bg_${scope}_$name", v).apply()
    fun bgGetFloat(scope: String, name: String, def: Float) = prefs.getFloat("bg_${scope}_$name", def)
    fun bgPutFloat(scope: String, name: String, v: Float) = prefs.edit().putFloat("bg_${scope}_$name", v).apply()
    fun bgGetInt(scope: String, name: String, def: Int) = prefs.getInt("bg_${scope}_$name", def)
    fun bgPutInt(scope: String, name: String, v: Int) = prefs.edit().putInt("bg_${scope}_$name", v).apply()

    // ── Active orders list ──
    fun getActiveOrders(): MutableList<ActiveOrder> {
        val json = prefs.getString("activeOrders", "[]") ?: "[]"
        return try {
            val array = JsonParser().parse(json).asJsonArray
            val result = mutableListOf<ActiveOrder>()
            for (item in array) {
                result.add(gson.fromJson(item, ActiveOrder::class.java))
            }
            result
        } catch (_: Exception) { mutableListOf() }
    }

    fun saveActiveOrders(orders: List<ActiveOrder>) {
        prefs.edit().putString("activeOrders", gson.toJson(orders)).apply()
    }

    fun clearActiveOrders() = prefs.edit().remove("activeOrders").apply()

    var themeMode: String
        get() = prefs.getString("themeMode", "LIGHT") ?: "LIGHT"
        set(value) = prefs.edit().putString("themeMode", value).apply()

    /**
     * 下载更新包时是否走国内镜像（Gitee）。
     *
     * 默认开。大陆实测 GitHub 的 Release 附件约 100 KB/s，Gitee 约 2 MB/s，差近 20 倍。
     * 但 Gitee 那边的仓库不如 GitHub 稳（审核、限流都可能让地址失效），
     * 所以留个开关，镜像下不动时用户能切回 GitHub。
     */
    var useMirrorDownload: Boolean get() = prefs.getBoolean("useMirrorDownload", true)
        set(v) = prefs.edit().putBoolean("useMirrorDownload", v).apply()

    // ── 通知开关（「我的」页面的通知卡片）──
    // 三个独立开关。关掉「使用中通知」只是不挂那条常驻通知，
    // 后台监控照常跑——自动关停和结束通知不受影响。

    /**
     * 通知总开关。
     *
     * 关掉后**一条通知都不发**，包括前台服务那条必须挂的——
     * 服务会先挂上再立刻摘掉（Android 不允许前台服务没有通知）。
     * 代价是服务降级成普通后台服务，更容易被系统回收。
     */
    var notifyEnabled: Boolean get() = prefs.getBoolean("notifyEnabled", true)
        set(v) = prefs.edit().putBoolean("notifyEnabled", v).apply()

    /** 用水期间在通知栏常驻一条，显示已用时间 */
    var notifyInUse: Boolean get() = prefs.getBoolean("notifyInUse", true)
        set(v) = prefs.edit().putBoolean("notifyInUse", v).apply()

    /** 用水结束时通知（手动停止） */
    var notifyFinished: Boolean get() = prefs.getBoolean("notifyFinished", true)
        set(v) = prefs.edit().putBoolean("notifyFinished", v).apply()

    /** 设备超时自动关停时通知 */
    var notifyAutoClose: Boolean get() = prefs.getBoolean("notifyAutoClose", true)
        set(v) = prefs.edit().putBoolean("notifyAutoClose", v).apply()

    /**
     * 「横幅提醒」——开阀失败、设备被占用、超时自动关停要从屏幕顶上弹出来。
     *
     * 靠**两条渠道二选一**实现（HIGH / DEFAULT），而不是改渠道优先级：
     * 系统禁止 App 修改已存在渠道的 importance，改不动。见 `Notifier.alertChannel`。
     */
    var notifyAlert: Boolean get() = prefs.getBoolean("notifyAlert", true)
        set(v) = prefs.edit().putBoolean("notifyAlert", v).apply()

    /**
     * 「占用中」标记的有效期。
     *
     * 3 分钟是用户定的。**这不是推导出来的数**——理论上界应该是设备的自动关停窗口
     * （对方不可能用超过那个时长），但那个值是从服务端读的 `autoDisConTime`，
     * 代码里没有兜底、也没有实测样本，所以只能按经验取。
     *
     * 取值理由：徽章**不禁用按钮**（占用中仍然点得动，服务端才是最终裁判），
     * 所以「设短」只是标签早消失一会儿，用户点一下就知道了；
     * 而「设长」会让人看到「占用中」干脆不去点——那个代价更大。**宁可短。**
     */
    private const val OCCUPIED_TTL_MS = 3 * 60 * 1000L

    /**
     * 已知「被他人占用」的设备 snCode；空串表示当前没有。
     *
     * 小组件上点开始、发现设备正被别人用着时写入，徽章随之变成「占用中」。
     *
     * ⚠️ 小组件渲染是纯本地同步的，发不了网络请求，所以它**没法自己知道
     * 设备什么时候空出来**。以前只靠「用户下次点」这个时机刷新，导致
     * 用户从此不再点小组件的话，桌面会**永远**挂着「占用中」。现在有两条出路：
     *
     * 1. App 侧查到设备空了会调 [clearOccupied]（见 `MainViewModel.refreshDeviceStatus`）
     * 2. 兜底：超过 [OCCUPIED_TTL_MS] 自动失效（见 [occupiedFor]）
     */
    var occupiedSnCode: String get() = prefs.getString("occupiedSnCode", "") ?: ""
        set(v) = prefs.edit()
            .putString("occupiedSnCode", v)
            .putLong("occupiedAtMs", System.currentTimeMillis())
            .apply()

    /**
     * 这台设备**现在**是不是还该显示「占用中」。
     *
     * 除了 snCode 对得上，还要求标记没过期——过期时**顺手把陈旧标记清掉并记一条日志**
     * （只记一次，因为清完再进来 snCode 就是空串了）。
     * 那条日志是给以后调这个 3 分钟用的：真实数据比再猜一轮靠谱。
     */
    fun occupiedFor(snCode: String): Boolean {
        if (snCode.isEmpty()) return false
        if (prefs.getString("occupiedSnCode", "") != snCode) return false
        val at = prefs.getLong("occupiedAtMs", 0L)
        if (at > 0L && System.currentTimeMillis() - at <= OCCUPIED_TTL_MS) return true
        clearOccupied()
        AppLogger.i("「占用中」标记超过 ${OCCUPIED_TTL_MS / 60000} 分钟，自动失效")
        return false
    }

    /** 清掉「占用中」标记。App 查到设备已空闲、或小组件开阀成功时调用 */
    fun clearOccupied() {
        if (prefs.getString("occupiedSnCode", "")!!.isEmpty()) return
        prefs.edit().remove("occupiedSnCode").remove("occupiedAtMs").apply()
    }

    var manualBalance: String get() = prefs.getString("manualBalance", "") ?: ""; set(v) = prefs.edit().putString("manualBalance", v).apply()
    var manualBalanceTime: Long get() = prefs.getLong("manualBalanceTime", 0L); set(v) = prefs.edit().putLong("manualBalanceTime", v).apply()

    /**
     * 上次使用设备的预扣金额。
     * 小组件开阀时拿不到 DeviceInfo（它只有 snCode），而预扣金额只存在于设备信息里，
     * 所以单独存一份——否则小组件开阀后卡片上的预扣永远是 ¥0.00。
     */
    var lastDeviceWithholdMoney: Float get() = prefs.getFloat("lastDeviceWithholdMoney", 0f)
        set(v) = prefs.edit().putFloat("lastDeviceWithholdMoney", v).apply()

    /** 上次使用设备的类型名（热水器 / 洗手台 / 饮水机），小组件副标题用 */
    var lastDeviceTypeName: String get() = prefs.getString("lastDeviceTypeName", "") ?: ""
        set(v) = prefs.edit().putString("lastDeviceTypeName", v).apply()

    // ── 小组件离线快照 ──
    // 小组件自己扫不了蓝牙、拉不了账单，所以由 App 在干活时把结果存下来，
    // 小组件的「附近设备 / 账单」两页读这里，并显示同步时间避免被当成实时数据。

    var widgetNearbyJson: String get() = prefs.getString("widgetNearbyJson", "") ?: ""
        set(v) = prefs.edit().putString("widgetNearbyJson", v).apply()

    var widgetNearbyTime: Long get() = prefs.getLong("widgetNearbyTime", 0L)
        set(v) = prefs.edit().putLong("widgetNearbyTime", v).apply()

    var widgetBillJson: String get() = prefs.getString("widgetBillJson", "") ?: ""
        set(v) = prefs.edit().putString("widgetBillJson", v).apply()

    var widgetBillTime: Long get() = prefs.getLong("widgetBillTime", 0L)
        set(v) = prefs.edit().putLong("widgetBillTime", v).apply()

    // 2x4 小组件的当前 tab（每个 widget id 各一份，用 "tab_<id>" 作 key）
    fun widgetTab(id: Int): Int = prefs.getInt("widgetTab_$id", 0)
    fun setWidgetTab(id: Int, tab: Int) { prefs.edit().putInt("widgetTab_$id", tab).apply() }

    /** 该 widget 是否还在桌面上；被删掉时清掉它的 tab 记录 */
    fun clearWidgetTab(id: Int) { prefs.edit().remove("widgetTab_$id").apply() }

    // ── 上次消费 ──
    // 持久化下来给桌面小组件显示用：小组件不能为了一个数字去轮询账单接口，
    // 所以由 App（结算成功、或拉到账单列表时）写入，小组件只读。

    /** 上次消费时间戳（毫秒），0 表示还没有记录 */
    var lastConsumeTime: Long get() = prefs.getLong("lastConsumeTime", 0L); set(v) = prefs.edit().putLong("lastConsumeTime", v).apply()

    /**
     * 某台设备的「上次消费」金额，0 表示还没有记录。
     *
     * 原先只有一个全局值，结果在小组件上用「选用」换到另一台设备之后，
     * 卡片会顶着新设备的名字显示上一台的消费金额。改成按 snCode 分开存，
     * key 是「前缀 + snCode」，和 startedAt_ / autoDiscon_ 同一个套路。
     */
    fun lastConsumeFor(snCode: String): Float =
        if (snCode.isEmpty()) 0f else prefs.getFloat("consume_$snCode", 0f)

    /** 记一笔消费。金额为 0 时不动已有记录，避免「本次无消费」把历史值抹掉 */
    fun recordConsume(snCode: String, amount: Double, atMs: Long = System.currentTimeMillis()) {
        if (amount <= 0 || snCode.isEmpty()) return
        prefs.edit()
            .putFloat("consume_$snCode", amount.toFloat())
            .putLong("lastConsumeTime", atMs)
            .apply()
    }

    // ── Per-device timer ──
    fun getStartedAt(snCode: String): Long = prefs.getLong("startedAt_$snCode", 0L)
    fun setStartedAt(snCode: String, v: Long) = prefs.edit().putLong("startedAt_$snCode", v).apply()

    // ── 自动关停倒计时（以毫秒时间戳持久化，App 重启后可恢复） ──
    private fun autoDisconKey(snCode: String) = "autoDiscon_$snCode"

    /** 剩余秒数，依据持久化的截止时间戳计算；无记录返回 0 */
    fun getAutoDisconRemain(snCode: String): Int {
        val deadline = prefs.getLong(autoDisconKey(snCode), 0L)
        if (deadline <= 0L) return 0
        val remain = ((deadline - System.currentTimeMillis()) / 1000).toInt()
        return if (remain > 0) remain else 0
    }

    /** 设置剩余秒数，转换为截止时间戳保存 */
    fun setAutoDisconRemain(snCode: String, seconds: Int) {
        if (seconds <= 0) {
            prefs.edit().remove(autoDisconKey(snCode)).apply()
        } else {
            prefs.edit().putLong(autoDisconKey(snCode), System.currentTimeMillis() + seconds * 1000L).apply()
        }
    }

    fun clearAutoDiscon(snCode: String) = prefs.edit().remove(autoDisconKey(snCode)).apply()
}
