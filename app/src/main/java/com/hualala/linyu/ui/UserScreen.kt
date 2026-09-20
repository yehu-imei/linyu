package com.hualala.linyu.ui

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hualala.linyu.BuildConfig
import com.hualala.linyu.R
import com.hualala.linyu.utils.MD5Utils
import com.hualala.linyu.utils.Notifier
import com.hualala.linyu.api.GithubApi
import com.hualala.linyu.api.GithubAsset
import com.hualala.linyu.api.GithubRelease
import com.hualala.linyu.api.GithubRepoInfo
import com.hualala.linyu.api.NetworkModule
import com.hualala.linyu.data.AuthRepository
import com.hualala.linyu.api.forgetPasswordSafe
import com.hualala.linyu.api.updatePasswordSafe
import com.hualala.linyu.api.updatePhoneSafe
import com.hualala.linyu.api.updateUseCodeStatusSafe
import com.hualala.linyu.model.DeviceInfo
import com.hualala.linyu.model.UseCodeData
import com.hualala.linyu.ui.theme.AppColors
import com.hualala.linyu.ui.theme.LocalThemeMode
import com.hualala.linyu.ui.theme.LocalThemeReveal
import com.hualala.linyu.ui.theme.ThemeMode
import com.hualala.linyu.utils.ApkDownloadState
import com.hualala.linyu.utils.ApkInstallResult
import com.hualala.linyu.utils.ApkUpdater
import com.hualala.linyu.utils.PrefsHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 「我的」页面中可编辑的卡片类型（声明顺序即默认顺序） */
enum class UserCardType(val title: String) {
    ACCOUNT("账号信息"),
    USE_CODE("使用码"),
    NOTIFY("通知"),
    BACKGROUND("背景装扮"),
    BOUND_ROOM("绑定寝室"),
    UPDATE("软件更新"),
    ABOUT("关于项目"),
    LOG("运行日志")
}

// ── 卡片顺序 / 隐藏状态的读写 ──

/**
 * 历代的默认顺序。
 *
 * 存下来的顺序**恰好等于**其中任意一条，就说明用户从没手动排过（只是某次进页面时
 * 顺手存了默认值），这时应该用当前的新默认顺序，而不是把他锁在旧排序里。
 *
 * 代价是：用户真的手动排成了和某条默认完全一样的顺序时，会跟着新默认走。
 * 这种巧合概率极低，换来的是改默认顺序时老用户能跟着更新——值得。
 */
private val SUPERSEDED_DEFAULT_ORDERS = listOf(
    // v2.2.x 早期：「运行日志」在「关于项目」之前
    listOf("ACCOUNT", "BOUND_ROOM", "USE_CODE", "BACKGROUND", "UPDATE", "LOG", "ABOUT"),
    // 之后：补上了「通知」，但顺序仍是旧的
    listOf("ACCOUNT", "BOUND_ROOM", "USE_CODE", "BACKGROUND", "UPDATE", "NOTIFY", "ABOUT", "LOG")
)

private fun loadCardOrder(): List<UserCardType> {
    val all = UserCardType.values().toList()
    val savedNames = PrefsHelper.userCardOrder.split(",")
        .map { it.trim() }.filter { it.isNotEmpty() }
    val effective = if (savedNames in SUPERSEDED_DEFAULT_ORDERS) emptyList() else savedNames
    val saved = effective.mapNotNull { name -> all.find { it.name == name } }
    // 已保存的顺序 + 新增卡片（追加到末尾），并去重
    return (saved + all).distinct()
}

private fun saveCardOrder(order: List<UserCardType>) {
    PrefsHelper.userCardOrder = order.joinToString(",") { it.name }
    UserPageCache.cardOrder = order
}

private fun loadHiddenCards(): Set<String> =
    PrefsHelper.userHiddenCards.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()

private fun saveHiddenCards(hidden: Set<String>) {
    PrefsHelper.userHiddenCards = hidden.joinToString(",")
    UserPageCache.hiddenCards = hidden
}

private fun <T> moveItem(list: List<T>, from: Int, to: Int): List<T> {
    if (from == to || from !in list.indices || to !in list.indices) return list
    val mutable = list.toMutableList()
    val item = mutable.removeAt(from)
    mutable.add(to, item)
    return mutable
}

/**
 * 「我的」页面的进程级缓存。
 *
 * 页面在切换 tab 时会被重建（AnimatedContent），若不缓存则会反复做两件慢事：
 * 1. 读 EncryptedSharedPreferences（需要解密）
 * 2. 发 GitHub 网络请求
 * 缓存后只做一次，切换 tab 就不再卡顿。
 */
private object UserPageCache {
    var cardOrder: List<UserCardType>? = null
    var hiddenCards: Set<String>? = null
    var releasesFetched: Boolean = false
    var releases: List<GithubRelease> = emptyList()
    var repoInfoFetched: Boolean = false
    var repoInfo: GithubRepoInfo? = null
}

@Composable
fun UserScreen(phone: String, onLogout: () -> Unit, viewModel: MainViewModel? = null) {
    LaunchedEffect(Unit) {
        viewModel?.loadUseCode()
        // 姓名 / 学号走 /account/info，一卡通余额走 /settlement/campus/userInfo。
        // 这两个都写进 Prefs，但界面读的是 ViewModel 的 State——
        // 直接读 Prefs 不会触发重组，拉完数据界面不会自己刷新。
        viewModel?.loadAccountInfo()
        viewModel?.loadCampusBalance()
    }
    val useCode = viewModel?.useCodeData

    // 编辑模式与卡片布局
    var editMode by remember { mutableStateOf(false) }
    var cardOrder by remember {
        mutableStateOf(UserPageCache.cardOrder ?: loadCardOrder().also { UserPageCache.cardOrder = it })
    }
    var hiddenCards by remember {
        mutableStateOf(UserPageCache.hiddenCards ?: loadHiddenCards().also { UserPageCache.hiddenCards = it })
    }

    var showLogViewer by remember { mutableStateOf(false) }
    var showBackgroundScreen by remember { mutableStateOf(false) }
    var themeMode by LocalThemeMode.current
    val themeReveal = LocalThemeReveal.current
    var themeBtnPos by remember { mutableStateOf(Offset.Zero) }

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp)) {
        // 标题栏：标题 + 编辑 + 主题
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("我的账号", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary)
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { editMode = !editMode }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text(if (editMode) "完成" else "编辑", fontSize = 14.sp, color = AppColors.Accent)
                }
                IconButton(
                    onClick = { themeReveal.toggle(themeBtnPos) },
                    modifier = Modifier.onGloballyPositioned { coords ->
                        themeBtnPos = coords.boundsInRoot().center
                    }
                ) {
                    Text(if (themeMode == ThemeMode.DARK) "🌙" else "☀️", fontSize = 20.sp)
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        // 卡片列表（编辑模式下显示全部，便于恢复隐藏项）
        val visibleCards = if (editMode) cardOrder else cardOrder.filter { it.name !in hiddenCards }
        visibleCards.forEachIndexed { index, type ->
            EditableCardSlot(
                editMode = editMode,
                title = type.title,
                isHidden = type.name in hiddenCards,
                canMoveUp = index > 0,
                canMoveDown = index < visibleCards.lastIndex,
                onMoveUp = {
                    cardOrder = moveItem(cardOrder, index, index - 1); saveCardOrder(cardOrder)
                },
                onMoveDown = {
                    cardOrder = moveItem(cardOrder, index, index + 1); saveCardOrder(cardOrder)
                },
                onToggleHide = {
                    hiddenCards = if (type.name in hiddenCards) hiddenCards - type.name
                                  else hiddenCards + type.name
                    saveHiddenCards(hiddenCards)
                }
            ) {
                when (type) {
                    UserCardType.ACCOUNT -> AccountCard(phone, viewModel)
                    UserCardType.BOUND_ROOM -> BoundRoomCard(viewModel)
                    UserCardType.USE_CODE -> UseCodeCard(useCode, viewModel)
                    UserCardType.BACKGROUND -> BackgroundCard { showBackgroundScreen = true }
                    UserCardType.UPDATE -> UpdateCard()
                    UserCardType.NOTIFY -> NotifyCard()
                    UserCardType.LOG -> LogCard { showLogViewer = true }
                    UserCardType.ABOUT -> AboutCard()
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        Spacer(Modifier.height(8.dp))

        OutlinedButton(onClick = onLogout, modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = AppColors.Danger)) {
            Text("退出登录")
        }

        Spacer(Modifier.height(8.dp))
        Text("Hualala v${BuildConfig.VERSION_NAME} · 哗啦啦啦啦让我去淋浴~",
            color = AppColors.TextSecondary, fontSize = 12.sp)
        Spacer(Modifier.height(100.dp)) // 底部留出悬浮导航栏空间
    }

    if (showLogViewer) {
        LogViewerDialog(onDismiss = { showLogViewer = false })
    }

    if (showBackgroundScreen) {
        CustomBackgroundScreen(onDismiss = { showBackgroundScreen = false })
    }
}

/** 统一的卡片外观 */
@Composable
private fun BaseCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = AppColors.Card),
        border = BorderStroke(0.8.dp, AppColors.Border),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        content = content
    )
}

/** 编辑模式下在卡片上方显示的操作条；非编辑模式直接渲染卡片 */
@Composable
private fun EditableCardSlot(
    editMode: Boolean,
    title: String,
    isHidden: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onToggleHide: () -> Unit,
    content: @Composable () -> Unit
) {
    Column {
        if (editMode) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (isHidden) "$title（已隐藏）" else title,
                    fontSize = 13.sp,
                    color = if (isHidden) AppColors.TextSecondary.copy(alpha = 0.6f) else AppColors.TextSecondary,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onMoveUp, enabled = canMoveUp, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Default.KeyboardArrowUp, "上移",
                        tint = if (canMoveUp) AppColors.Accent else AppColors.TextSecondary.copy(alpha = 0.3f))
                }
                IconButton(onClick = onMoveDown, enabled = canMoveDown, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Default.KeyboardArrowDown, "下移",
                        tint = if (canMoveDown) AppColors.Accent else AppColors.TextSecondary.copy(alpha = 0.3f))
                }
                IconButton(onClick = onToggleHide, modifier = Modifier.size(34.dp)) {
                    Icon(
                        painterResource(
                            if (isHidden) R.drawable.ic_visibility else R.drawable.ic_visibility_off
                        ),
                        if (isHidden) "显示" else "隐藏",
                        tint = if (isHidden) AppColors.Success else AppColors.TextSecondary
                    )
                }
            }
        }
        // 隐藏状态下，编辑模式中卡片半透明显示
        Box(modifier = if (editMode && isHidden) Modifier.alpha(0.45f) else Modifier) {
            content()
        }
    }
}

@Composable
private fun AccountCard(
    phone: String,
    viewModel: MainViewModel?
) {
    var showChangePhone by remember { mutableStateOf(false) }
    var showChangePassword by remember { mutableStateOf(false) }
    var manageExpanded by remember { mutableStateOf(false) }

    // ViewModel 的 State 优先（能触发重组），没有就退回 Prefs 里的持久化值
    val info = viewModel?.accountInfo
    val name = info?.name?.takeIf { it.isNotEmpty() }
        ?: PrefsHelper.userName.takeIf { it.isNotEmpty() }
    val studentId = info?.idCardNumber?.takeIf { it.isNotEmpty() }
        ?: PrefsHelper.userStudentId.takeIf { it.isNotEmpty() }
    // 学校名走 /project/info/triple 自动填，不再让用户手输——服务端本来就有
    val school = viewModel?.schoolName?.takeIf { it.isNotEmpty() } ?: PrefsHelper.schoolName

    BaseCard {
        Column(Modifier.padding(20.dp)) {
            // 没值就**整行不显示**。以前写死「未设置」，大多数人看到的是那三个字，
            // 既像报错又占位置——而学校没同步姓名是服务端的事，用户改不了。
            if (name != null) {
                InfoRow("姓名", name)
                Spacer(Modifier.height(10.dp))
            }
            InfoRow("手机号", phone)
            Spacer(Modifier.height(10.dp))
            InfoRow("学校", school)
            // 学号跟在**学校**下面：这两个是同一类信息（学籍），挨着放一眼能对上；
            // 夹在手机号和学校之间会把「账号」和「学籍」两组信息切得七零八落
            if (studentId != null) {
                Spacer(Modifier.height(10.dp))
                InfoRow("学号", studentId)
            }

            // 账号管理默认收起。这两件事一年用不上一次，铺在卡片里纯占地方；
            // 但换手机号/改密码又是必须有的出口，所以做成可展开而不是直接删掉。
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = AppColors.Border)
            CollapsibleHeader("账号管理", manageExpanded) { manageExpanded = !manageExpanded }
            if (manageExpanded) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    TextButton(onClick = { showChangePhone = true }) {
                        Text("更换手机号", fontSize = 13.sp, color = AppColors.Accent)
                    }
                    TextButton(onClick = { showChangePassword = true }) {
                        Text("修改密码", fontSize = 13.sp, color = AppColors.Accent)
                    }
                }
            }
        }
    }

    if (showChangePhone) {
        ChangePhoneDialog(viewModel) { showChangePhone = false }
    }
    if (showChangePassword) {
        ChangePasswordDialog(viewModel) { showChangePassword = false }
    }
}

/**
 * 折叠区的标题行：一行字 + 右端箭头，整行可点。
 *
 * 「我的」页面的折叠区都走这一个，免得每处自己写一遍箭头方向，
 * 出现有的地方展开了箭头朝上、有的朝下。
 */
@Composable
private fun CollapsibleHeader(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { onToggle() }
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, fontSize = 13.sp, color = AppColors.Accent)
        Spacer(Modifier.width(4.dp))
        Icon(
            if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            null, tint = AppColors.Accent, modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun BoundRoomCard(viewModel: MainViewModel?) {
    // ⚠️ 显示的是 ViewModel 里的状态，不是本地 state。
    // 用本地 state 的话首页那份 `remember(..., viewModel.boundRoom)` 不会被触发，
    // 在「我的」页改完绑定、切回首页时列表还是旧的。
    val boundRoom = viewModel?.boundRoom ?: PrefsHelper.boundRoom
    var showRoomPicker by remember { mutableStateOf(false) }

    BaseCard {
        Column(Modifier.padding(20.dp)) {
            Text("绑定寝室", fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                color = AppColors.TextPrimary)
            Spacer(Modifier.height(8.dp))
            if (boundRoom.isNotEmpty()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    // 存的就是寝室键本身（`龙川北苑-3号楼南-3层-320`），直接显示。
                    // 外面再套一次 roomKey 是为了**兼容老版本存下来的值**——
                    // 那些可能是 `320房`，甚至是完整设备名。
                    Text("🏠 ${DeviceInfo.roomKey(boundRoom) ?: boundRoom}",
                        fontWeight = FontWeight.Medium,
                        color = AppColors.TextPrimary, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(8.dp))
                    // 取消绑定走 applyBoundRoom：写 Prefs + 更新 Compose 状态（首页列表立刻刷新）
                    // + 收尾（桌面恢复成「上次使用的设备」），三件事一次做完
                    TextButton(onClick = { viewModel?.applyBoundRoom("") }) {
                        Text("取消绑定", color = AppColors.Danger, fontSize = 12.sp)
                    }
                }
            } else {
                Text("未绑定寝室，设备列表将显示全部设备",
                    color = AppColors.TextSecondary, fontSize = 12.sp)
            }
            Spacer(Modifier.height(12.dp))
            // 只有「选择附近」一条路，**没有手工输入框**。
            //
            // 手工输入存进去的是一个没和设备名核对过的字符串，筛不出来的时候
            // 用户分不清是"输错了"还是"这层真没设备"——静默失败。
            // 选择附近列出来的每条都是扫描到的真实寝室，存下来的值必然能匹配上。
            OutlinedButton(onClick = { showRoomPicker = true },
                shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                Text("选择附近")
            }
        }
    }

    if (showRoomPicker) {
        // 一间寝室一条，列出的是**寝室键**本身（`龙川北苑-3号楼南-3层-320`）。
        //
        // ⚠️ 不显示、也不保存「某一台设备的完整名」。那样的话同一间寝室会出现两种
        // 结果——只扫到热水表时是 `热水表-…-320房`，只扫到洗手台时是
        // `洗手台54-…-320洗手台`，显示不一致、"绑定"下来的东西也不一致。
        // 键是从设备名掐头去尾得来的，和扫到哪一台无关。
        //
        // 去重也就顺理成章：同一间寝室的两台设备算出来的键本来就相等。
        val devices: List<String> = run {
            val seen = mutableSetOf<String>()
            viewModel?.nearbyDevices?.mapNotNull { d ->
                DeviceInfo.roomKey(d.deviceInfo?.deviceName ?: d.name)?.takeIf { seen.add(it) }
            } ?: emptyList()
        }
        AlertDialog(
            onDismissRequest = { showRoomPicker = false },
            title = { Text("选择设备位置", fontWeight = FontWeight.Bold) },
            text = {
                if (devices.isEmpty()) {
                    Text("附近暂无设备", color = AppColors.TextSecondary)
                } else {
                    // heightIn 而不是固定高度：只有一两个寝室时卡片会跟着变矮，
                    // 固定 300dp 会拖出一大块空白，看着像还没加载完
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 210.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        items(devices) { key ->
                            TextButton(
                                onClick = {
                                    showRoomPicker = false
                                    viewModel?.applyBoundRoom(key)
                                    viewModel?.toastMessage = "已绑定寝室：$key"
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                // 头部省略、单行不换行：先缩字号，实在放不下才省略开头。
                                // 尾部是「3层-320」这一段，正是辨认寝室需要的信息
                                TailEllipsisText(
                                    text = key,
                                    color = AppColors.TextPrimary,
                                    fontSize = 14.sp,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showRoomPicker = false }) { Text("关闭") } }
        )
    }
}

@Composable
private fun UseCodeCard(useCode: UseCodeData?, viewModel: MainViewModel?) {
    var localCodeOn by remember { mutableStateOf(useCode?.useCodeStatus == 1) }
    LaunchedEffect(useCode?.useCodeStatus) { useCode?.useCodeStatus?.let { localCodeOn = it == 1 } }
    val scope = rememberCoroutineScope()
    var showRedeem by remember { mutableStateOf(false) }
    var redeemExpanded by remember { mutableStateOf(false) }

    val code = useCode?.useCode
    // 区分两种「没有码」：还没拉到（加载中）vs 服务端说这人就没有（尚未领取）。
    // 没领过码的人 `useCode` 返回的是 null，以前一律显示「加载中...」，永远等不到。
    val loaded = viewModel?.useCodeLoaded == true
    // resetAvailability == 0 = 今天已经领过了，服务端不让再领（实测文案「1天只能领取一次使用码」）。
    // 没拉到数据时是 null，按「可以点」处理——让请求自己去报错，好过凭空禁用。
    val canRedeem = useCode?.resetAvailability != 0

    BaseCard {
        Column(Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("使用码", color = AppColors.TextSecondary, fontSize = 13.sp)
                Text("后三位为手机号后三位", color = AppColors.TextSecondary, fontSize = 11.sp)
            }
            Spacer(Modifier.height(4.dp))
            when {
                !code.isNullOrEmpty() -> {
                    val prefix = code.dropLast(3)
                    val suffix = code.takeLast(3)
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(color = AppColors.TextPrimary)) { append(prefix) }
                            withStyle(SpanStyle(color = AppColors.Accent)) { append(suffix) }
                        },
                        fontSize = 28.sp, fontWeight = FontWeight.Black, letterSpacing = 6.sp
                    )
                }
                loaded -> Text("尚未领取", fontSize = 28.sp, fontWeight = FontWeight.Black,
                    color = AppColors.TextSecondary, letterSpacing = 6.sp)
                else -> Text("加载中...", fontSize = 28.sp, fontWeight = FontWeight.Black,
                    color = AppColors.TextPrimary, letterSpacing = 6.sp)
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()) {
                Text(if (localCodeOn) "使用码已开启" else "使用码已关闭",
                    color = AppColors.TextSecondary, fontSize = 12.sp)
                Switch(checked = localCodeOn, onCheckedChange = { newVal ->
                    localCodeOn = newVal
                    scope.launch {
                        try {
                            NetworkModule.apiService.updateUseCodeStatusSafe(
                                status = if (newVal) 1 else 0,
                                auth = NetworkModule.authFields()
                            )
                        } catch (e: Exception) {
                            localCodeOn = !newVal
                            val msg = e.message ?: ""
                            viewModel?.toastMessage = when {
                                msg.contains("Unable to resolve host", ignoreCase = true) ||
                                msg.contains("No address associated", ignoreCase = true) ||
                                msg.contains("Failed to connect", ignoreCase = true) ->
                                    "网络连接失败，请检查网络设置"
                                else -> "操作失败，请重试"
                            }
                        }
                    }
                }, colors = SwitchDefaults.colors(checkedThumbColor = AppColors.Accent,
                    checkedTrackColor = AppColors.Accent.copy(alpha = 0.5f)))
            }
            Text("在热水器物理键盘上输入此码",
                color = AppColors.TextSecondary, fontSize = 12.sp)

            // 领取入口同样收起。⚠️ 今天已领过时**按钮照样能点**——
            // 置灰虽然「正确」，但用户看不懂为什么不给点；让他点、然后弹一句
            // 「1天只能领取一次使用码」，比一个灰按钮说得多。
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = AppColors.Border)
            CollapsibleHeader(
                if (code.isNullOrEmpty()) "领取使用码" else "重新领取",
                redeemExpanded
            ) { redeemExpanded = !redeemExpanded }
            if (redeemExpanded) {
                if (!canRedeem) {
                    useCode?.resetAvailabilityWarMark?.let {
                        Text(it, color = AppColors.TextSecondary, fontSize = 11.sp,
                            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                    }
                    Spacer(Modifier.height(6.dp))
                }
                Button(
                    onClick = { showRedeem = true },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent)
                ) {
                    Text(if (code.isNullOrEmpty()) "领取使用码" else "重新领取")
                }
            }
        }
    }

    if (showRedeem) {
        UseCodeRedeemDialog(useCode, viewModel) { showRedeem = false }
    }
}

/** 换出来的使用码领取时限：3 分钟，超时作废 */
private const val RedeemTtlSec = 180

/**
 * 领取 / 重新领取使用码。
 *
 * **关键在于「换一个」不会动当前生效的码**——服务端的 `generate` 只是把候选码
 * 发过来（扣一次当日额度），真正生效要等 `set`，也就是「确定领取」那一下。
 * 所以「取消」是零成本的，当前码纹丝不动，这正是和官方那套的区别：
 * 官方点开页面就先自动换一个，我们让用户自己决定换不换。
 *
 * 换出来的码有 3 分钟领取时限，所以挂了个倒计时——超时后按钮置灰，
 * 得重新「换一个」。
 */
@Composable
private fun UseCodeRedeemDialog(
    useCode: UseCodeData?,
    viewModel: MainViewModel?,
    onDismiss: () -> Unit
) {
    val currentCode = useCode?.useCode
    var candidate by remember { mutableStateOf<String?>(null) }
    var remainTimes by remember { mutableStateOf<Int?>(null) }
    var swapping by remember { mutableStateOf(false) }
    var claiming by remember { mutableStateOf(false) }
    var leftSec by remember { mutableStateOf(RedeemTtlSec) }
    var error by remember { mutableStateOf<String?>(null) }

    // 每换出一个新码就重置倒计时。key 用 candidate，换一次重新计一次
    LaunchedEffect(candidate) {
        if (candidate == null) return@LaunchedEffect
        leftSec = RedeemTtlSec
        while (leftSec > 0) {
            delay(1000)
            leftSec--
        }
    }

    val expired = candidate != null && leftSec <= 0

    val swap: () -> Unit = {
        if (!swapping && !claiming) {
            swapping = true
            error = null
            if (viewModel == null) {
                error = "当前无法换码"
                swapping = false
            } else {
                viewModel.generateUseCode { r ->
                    if (r == null) error = "换码失败，请重试"
                    else {
                        candidate = r.first
                        remainTimes = r.second
                    }
                    swapping = false
                }
            }
        }
    }

    // 服务端说今天已经领过了。**不是不给点，而是点了告诉用户为什么**——
    // 所以这里不拦入口，只把弹窗内容换成一句说明。
    val blocked = useCode?.resetAvailability == 0
    val blockReason = useCode?.resetAvailabilityWarMark ?: "1天只能领取一次使用码"

    // 打开弹窗就**先换一个出来**。
    //
    // ⚠️ 这里以前的条件是「只在手上没有码时才自动换」，结果有码的用户点「重新领取」，
    // 弹窗里是八个短横线、点「确定领取」也不亮，看着就像坏了。
    // 用户点这个按钮的意图本来就明摆着——「给我看一个新码」，那就直接给。
    //
    // 代价是打开弹窗就消耗一次当日额度（每天 20 次），官方也是这个行为：
    // 点开领取页它就先给你生成一个候选码。
    LaunchedEffect(Unit) { if (!blocked) swap() }

    AlertDialog(
        onDismissRequest = { if (!claiming) onDismiss() },
        title = {
            Text(
                if (blocked) "无法领取"
                else if (currentCode.isNullOrEmpty()) "领取使用码"
                else "重新领取使用码",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            if (blocked) {
                Text(blockReason, color = AppColors.TextPrimary, fontSize = 14.sp)
            } else
            Column {
                if (!currentCode.isNullOrEmpty()) {
                    Row(Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("当前使用码", color = AppColors.TextSecondary, fontSize = 12.sp)
                        Text(currentCode, color = AppColors.TextSecondary,
                            fontSize = 12.sp, letterSpacing = 1.sp)
                    }
                    Spacer(Modifier.height(14.dp))
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("领取后替换为", color = AppColors.TextSecondary, fontSize = 12.sp)
                        Spacer(Modifier.height(2.dp))
                        val shown = candidate
                        if (shown.isNullOrEmpty()) {
                            Text(
                                "— — — — — — — —",
                                fontSize = 24.sp, fontWeight = FontWeight.Black,
                                letterSpacing = 3.sp, color = AppColors.TextSecondary
                            )
                        } else {
                            // 后三位是**手机号后三位，固定的**；每次「换一个」换的
                            // 只有前五位。所以高亮后三位、前五位用正文色——
                            // 整串一个颜色的话，换了几次看着都一个样，根本分不出变化。
                            // 卡片上是这么做的，弹窗这里以前漏了。
                            Text(
                                buildAnnotatedString {
                                    withStyle(SpanStyle(
                                        color = if (expired) AppColors.TextSecondary
                                                else AppColors.TextPrimary
                                    )) { append(shown.dropLast(3)) }
                                    withStyle(SpanStyle(
                                        color = if (expired) AppColors.TextSecondary
                                                else AppColors.Accent
                                    )) { append(shown.takeLast(3)) }
                                },
                                fontSize = 24.sp, fontWeight = FontWeight.Black,
                                letterSpacing = 3.sp
                            )
                        }
                    }
                    TextButton(onClick = swap, enabled = !swapping && !claiming) {
                        Icon(Icons.Default.Refresh, null, tint = AppColors.Accent,
                            modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (swapping) "换码中"
                            else if (candidate == null) "获取" else "换一个",
                            fontSize = 13.sp, color = AppColors.Accent
                        )
                    }
                }

                Spacer(Modifier.height(6.dp))
                if (candidate != null) {
                    Text(
                        if (expired) "已超时，请重新换一个"
                        else "请在 %d:%02d 内领取".format(leftSec / 60, leftSec % 60),
                        color = if (expired) AppColors.Danger else AppColors.TextSecondary,
                        fontSize = 11.sp
                    )
                }
                remainTimes?.let {
                    Spacer(Modifier.height(2.dp))
                    Text("今日还能换 $it 次", color = AppColors.TextSecondary, fontSize = 11.sp)
                }
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = AppColors.Danger, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            if (blocked) {
                TextButton(onClick = onDismiss) { Text("知道了") }
            } else {
                TextButton(
                    onClick = {
                        val c = candidate ?: return@TextButton
                        if (viewModel == null) return@TextButton
                        claiming = true
                        error = null
                        viewModel.claimUseCode(c) { ok ->
                            if (ok) {
                                viewModel.toastMessage = "使用码已更新"
                                onDismiss()
                            } else {
                                error = "领取失败，请重试"
                            }
                            claiming = false
                        }
                    },
                    enabled = candidate != null && !expired && !claiming && !swapping
                ) { Text(if (claiming) "领取中…" else "确定领取") }
            }
        },
        dismissButton = {
            if (!blocked) {
                TextButton(onClick = onDismiss, enabled = !claiming) { Text("取消") }
            }
        }
    )
}

@Composable
private fun LogCard(onOpen: () -> Unit) {
    BaseCard {
        Row(Modifier.fillMaxWidth().padding(20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("运行日志", fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    color = AppColors.TextPrimary)
                Spacer(Modifier.height(4.dp))
                Text("查看/导出运行日志，便于排查问题",
                    color = AppColors.TextSecondary, fontSize = 12.sp)
            }
            Button(onClick = onOpen,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent)) {
                Text("查看")
            }
        }
    }
}

// ── 背景装扮 ──
@Composable
private fun BackgroundCard(onOpen: () -> Unit) {
    val enabled = com.hualala.linyu.utils.BackgroundState.config(com.hualala.linyu.utils.BackgroundManager.SCOPE_HOME).enabled || com.hualala.linyu.utils.BackgroundState.config(com.hualala.linyu.utils.BackgroundManager.SCOPE_SHOWER).enabled
    BaseCard {
        Row(Modifier.fillMaxWidth().padding(20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("背景装扮", fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    color = AppColors.TextPrimary)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (enabled) "已启用自定义背景" else "使用自己的图片打造专属界面",
                    color = AppColors.TextSecondary, fontSize = 12.sp
                )
            }
            Button(onClick = onOpen,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent)) {
                Text("更换")
            }
        }
    }
}

/**
 * 通知设置。
 *
 * 三个开关背后是同一套后台监控（`ShowerWatchService`），
 * **关掉任何一个都只是「不发那条通知」，监控本身照常跑**——
 * 自动关停不能因为用户不想被提醒就失效（那正是小组件卡在「使用中」的老毛病）。
 */
@Composable
private fun NotifyCard() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(PrefsHelper.notifyEnabled) }
    var expanded by remember { mutableStateOf(false) }
    var inUse by remember { mutableStateOf(PrefsHelper.notifyInUse) }
    var finished by remember { mutableStateOf(PrefsHelper.notifyFinished) }
    var autoClose by remember { mutableStateOf(PrefsHelper.notifyAutoClose) }
    var alert by remember { mutableStateOf(PrefsHelper.notifyAlert) }
    // 系统层面允不允许发通知。Android 13+ 是运行时权限，用户随时能在系统设置里撤销，
    // 所以每次进这张卡片都重新读一遍，别信缓存
    var allowed by remember { mutableStateOf(Notifier.canNotify(context)) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { allowed = Notifier.canNotify(context) }

    BaseCard {
        Column(Modifier.padding(20.dp)) {
            // ── 总开关 ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(Modifier.weight(1f)) {
                    Text("通知", fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                        color = AppColors.TextPrimary)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (enabled) "用水期间和结束时收到系统提醒" else "已关闭全部通知",
                        color = AppColors.TextSecondary, fontSize = 13.sp
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = enabled,
                    onCheckedChange = { enabled = it; PrefsHelper.notifyEnabled = it },
                    colors = SwitchDefaults.colors(checkedTrackColor = AppColors.Accent)
                )
            }

            // 权限没开的话开关全是摆设，必须说清楚，否则用户会以为坏了。
            //
            // ⚠️ 但要跟着**总开关**一起判断：用户自己把通知全关了的时候，
            // 再提示「请打开通知权限」就很莫名其妙——他本来就是不想要通知，
            // 而且那个「去开启」按钮还能一键把系统权限打开，等于跟用户对着干。
            if (!allowed && enabled) {
                Spacer(Modifier.height(12.dp))
                // fillMaxWidth 不能少：Surface 默认**按内容撑宽**，不写的话
                // 这块提示只有文字那么宽，右边空一截，看着像没画完
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = AppColors.Warning.copy(alpha = 0.12f)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text("请打开通知权限", color = AppColors.Warning,
                            fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(4.dp))
                        Text("开启后才能收到用水提醒和结束通知。",
                            color = AppColors.TextSecondary, fontSize = 12.sp)
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    permLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    openNotificationSettings(context)
                                }
                            },
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent)
                        ) { Text("去开启", fontSize = 13.sp) }
                    }
                }
            }

            // ── 展开入口 + 细分开关 ──
            if (enabled) {
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { expanded = !expanded }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("详细设置", color = AppColors.Accent, fontSize = 13.sp)
                    Icon(
                        if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (expanded) "收起" else "展开",
                        tint = AppColors.Accent,
                        modifier = Modifier.size(20.dp)
                    )
                }

                if (expanded) {
                    NotifyRow("用水状态通知", "使用期间在通知栏显示已用时间", inUse) {
                        inUse = it; PrefsHelper.notifyInUse = it
                    }
                    NotifyRow("使用结束通知", "停止后显示用时和消费金额", finished) {
                        finished = it; PrefsHelper.notifyFinished = it
                    }
                    NotifyRow("自动关停提醒", "设备超时自己关闭时提醒", autoClose) {
                        autoClose = it; PrefsHelper.notifyAutoClose = it
                    }
                    // 横幅是这几条里最「吵」的一档，单独给开关。
                    // 靠切渠道实现（见 Notifier.alertChannel），不是改渠道优先级——
                    // 系统不允许 App 改已存在渠道的 importance
                    NotifyRow("横幅提醒", "开阀失败、设备被占用时从屏幕顶部弹出", alert) {
                        alert = it; PrefsHelper.notifyAlert = it
                    }
                }
            }
        }
    }
}

@Composable
private fun NotifyRow(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = AppColors.TextPrimary, fontSize = 14.sp)
            Text(desc, color = AppColors.TextSecondary, fontSize = 11.sp)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = AppColors.Accent)
        )
    }
}

/** 系统设置里本应用的通知页（Android 8+）；更低版本只能进应用详情页 */
private fun openNotificationSettings(context: android.content.Context) {
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    } else {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(android.net.Uri.fromParts("package", context.packageName, null))
    }
    runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** 更新日志默认展开几个版本，其余折叠。点「展开全部」可看全部（最多 10 个） */
private const val RECENT_RELEASE_COUNT = 3

// ── 应用信息 / 软件更新 ──
@Composable
private fun UpdateCard() {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // 用进程级缓存，避免每次切到「我的」都重新请求
    var releases by remember { mutableStateOf(UserPageCache.releases) }
    var checking by remember { mutableStateOf(false) }
    var checkedOnce by remember { mutableStateOf(UserPageCache.releasesFetched) }
    var expandedTag by remember { mutableStateOf<String?>(null) }
    // 更新日志默认只列最近几个版本，其余折叠（见 ③ 处）
    var showAllReleases by remember { mutableStateOf(false) }

    val currentVersion = ApkUpdater.currentVersion
    val latestRelease = releases.firstOrNull()
    val latestTag = latestRelease?.tagName
    // 只有确实比当前新才提示更新——避免 tag 命名差异导致误报"有新版本"
    val hasUpdate = latestTag != null && ApkUpdater.isNewer(latestTag, currentVersion)
    val downloadState = ApkUpdater.state

    // 打开卡片即自动检测一次（已拉取过则跳过）
    fun doCheck() {
        if (checking) return
        checking = true
        scope.launch {
            val list = GithubApi.fetchReleases()
            UserPageCache.releases = list
            UserPageCache.releasesFetched = true
            releases = list
            checkedOnce = true
            checking = false
            // 不自动展开任何版本（由用户手动点击展开）
        }
    }
    LaunchedEffect(Unit) { if (!UserPageCache.releasesFetched) doCheck() }

    BaseCard {
        Column(Modifier.padding(20.dp)) {
            // ① 应用信息 + 当前版本
            Text("应用信息", fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                color = AppColors.TextPrimary)
            Spacer(Modifier.height(4.dp))
            Text("当前版本 $currentVersion", color = AppColors.TextSecondary, fontSize = 13.sp)

            // ② 自动检测结果
            Spacer(Modifier.height(8.dp))
            val (statusText, statusColor) = when {
                checking -> "正在检测更新…" to AppColors.TextSecondary
                !checkedOnce -> "正在检测更新…" to AppColors.TextSecondary
                releases.isEmpty() -> "检测失败（多为网络原因）" to AppColors.Warning
                hasUpdate -> "发现新版本 $latestTag" to AppColors.Accent
                else -> "当前已是最新版本" to AppColors.Success
            }
            Text(statusText, color = statusColor, fontSize = 13.sp,
                fontWeight = FontWeight.Medium)

            // ③ 更新日志（点击版本号展开）
            if (releases.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                HorizontalDivider(color = AppColors.Border)
                Spacer(Modifier.height(10.dp))
                Text("更新日志", fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    color = AppColors.TextPrimary)
                Spacer(Modifier.height(2.dp))
                // 默认只列最近几个版本。全部铺开会把卡片拉得很长，
                // 「检查更新」按钮被顶到屏幕外，想更新的人还得先划过一堆旧日志。
                val shown = if (showAllReleases) releases else releases.take(RECENT_RELEASE_COUNT)
                shown.forEach { r ->
                    ReleaseRow(
                        release = r,
                        isCurrent = r.tagName == currentVersion,
                        expanded = expandedTag == r.tagName,
                        onToggle = { expandedTag = if (expandedTag == r.tagName) null else r.tagName }
                    )
                }
                if (releases.size > RECENT_RELEASE_COUNT) {
                    TextButton(
                        onClick = { showAllReleases = !showAllReleases },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (showAllReleases) "收起" else "展开全部",
                            color = AppColors.Accent, fontSize = 12.sp
                        )
                    }
                }
            }

            // ④ 检查更新按钮
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = {
                    ApkUpdater.reset()
                    doCheck()
                },
                enabled = !checking,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent)
            ) { Text(if (checking) "检查中..." else "检查更新") }

            // ⑤ 下载区：放在「检查更新」下方，仍在本卡片内
            if (hasUpdate) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = AppColors.Border)
                Spacer(Modifier.height(12.dp))
                DownloadSection(
                    context = context,
                    release = latestRelease,
                    state = downloadState
                )
            }
        }
    }
}

/**
 * 下载更新的四种形态：可下载 / 下载中 / 已下载待安装 / 失败。
 *
 * 全部收在这张卡片里，不往外弹任何东西。
 */
@Composable
private fun DownloadSection(
    context: android.content.Context,
    release: GithubRelease?,
    state: ApkDownloadState
) {
    // 点「立即安装」后如果调起失败（包丢了 / 没有可用设置页），要在这里说清楚
    var installError by remember { mutableStateOf<String?>(null) }

    val asset = release?.apkAsset
    // 只读一次：EncryptedSharedPreferences 每次读都要过一遍 Keystore 解密，别放进重组路径
    var useMirror by remember { mutableStateOf(PrefsHelper.useMirrorDownload) }
    // 镜像地址拼不出来（tag 为空之类）就回落到 GitHub，别让用户点了个死链
    val downloadUrl = if (useMirror) (release?.giteeApkUrl ?: asset?.downloadUrl) else asset?.downloadUrl

    val startDownload: () -> Unit = {
        installError = null
        if (downloadUrl != null && asset != null) {
            ApkUpdater.start(
                context = context,
                url = downloadUrl,
                fileName = asset.name,
                expectedSize = asset.sizeBytes,
                releaseTag = release.tagName
            )
        }
    }
    val toggleSource: (Boolean) -> Unit = {
        useMirror = it
        PrefsHelper.useMirrorDownload = it
    }

    when (state) {
        is ApkDownloadState.Running -> {
            val pct = state.percent
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("正在下载更新包…", color = AppColors.TextSecondary, fontSize = 13.sp)
                Text(
                    // 显示「已下载 / 总大小」，比单纯一个百分比更有信息量
                    if (state.total > 0) {
                        "${formatBytes(state.downloaded)} / ${formatBytes(state.total)}"
                    } else {
                        formatBytes(state.downloaded)
                    },
                    color = AppColors.Accent, fontSize = 13.sp, fontWeight = FontWeight.Medium
                )
            }
            Spacer(Modifier.height(8.dp))
            if (pct >= 0) {
                LinearProgressIndicator(
                    progress = { pct / 100f },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = AppColors.Accent,
                    trackColor = AppColors.Border
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = AppColors.Accent,
                    trackColor = AppColors.Border
                )
            }
            Spacer(Modifier.height(4.dp))
            TextButton(
                onClick = { ApkUpdater.cancel() },
                modifier = Modifier.fillMaxWidth()
            ) { Text("取消下载", color = AppColors.TextSecondary, fontSize = 13.sp) }
        }

        is ApkDownloadState.Done -> {
            Text("更新包已下载完成", color = AppColors.Success, fontSize = 13.sp,
                fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    installError = when (val r = ApkUpdater.installApk(context, state.file)) {
                        is ApkInstallResult.Error -> r.message
                        else -> null
                    }
                },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Success)
            ) { Text("立即安装") }
            Spacer(Modifier.height(6.dp))
            Text(
                "请在系统设置里允许「安装未知应用」",
                color = AppColors.TextSecondary, fontSize = 12.sp
            )
        }

        is ApkDownloadState.Failed -> {
            Surface(shape = RoundedCornerShape(10.dp),
                color = AppColors.Warning.copy(alpha = 0.12f)) {
                Text(state.message, modifier = Modifier.padding(10.dp),
                    color = AppColors.Warning, fontSize = 12.sp)
            }
            Spacer(Modifier.height(8.dp))
            DownloadSourceRow(useMirror = useMirror, onToggle = toggleSource)
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = startDownload,
                enabled = downloadUrl != null,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent)
            ) { Text("重新下载") }
        }

        ApkDownloadState.Idle -> {
            DownloadSourceRow(useMirror = useMirror, onToggle = toggleSource)
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = startDownload,
                enabled = downloadUrl != null,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent)
            ) { Text("下载更新") }
        }
    }

    installError?.let { msg ->
        Spacer(Modifier.height(8.dp))
        Surface(shape = RoundedCornerShape(10.dp),
            color = AppColors.Warning.copy(alpha = 0.12f)) {
            Text(msg, modifier = Modifier.padding(10.dp),
                color = AppColors.Warning, fontSize = 12.sp)
        }
    }
}

/**
 * 下载源开关。
 *
 * 默认走国内镜像而不是自动回落——Gitee 的仓库状态不像 GitHub 那么确定，
 * 与其猜哪个能用，不如把选择权给用户，下载失败时切一下就行。
 */
@Composable
private fun DownloadSourceRow(useMirror: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(Modifier.weight(1f)) {
            Text("国内镜像下载", color = AppColors.TextPrimary, fontSize = 13.sp,
                fontWeight = FontWeight.Medium)
            Text(
                if (useMirror) "Gitee 直连，通常几秒下完" else "GitHub 源，国内可能要几分钟",
                color = AppColors.TextSecondary, fontSize = 11.sp
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = useMirror,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(checkedTrackColor = AppColors.Accent)
        )
    }
}

/** 字节数转成人看的单位 */
private fun formatBytes(bytes: Long): String = when {
    bytes <= 0L -> ""
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
}

/**
 * 从发行版说明中只抽出「新增功能」与「修复」两类内容，其余段落（安装步骤、注意事项等）丢弃。
 * 发行版说明是 Markdown，按 `#` 标题切段落，命中关键词的段落保留整段。
 */
private fun extractReleaseHighlights(body: String): String {
    val out = StringBuilder()
    var keeping = false
    body.lines().forEach { raw ->
        val line = raw.trimEnd()
        if (line.trimStart().startsWith("#")) {
            val title = plainHeading(line)
            keeping = title.contains("新增") || title.contains("功能") ||
                      title.contains("修复") || title.contains("BUG", ignoreCase = true)
            if (keeping) {
                if (out.isNotEmpty()) out.append('\n')
                out.append(stripMarkdown(title)).append('\n')
            }
        } else if (keeping) {
            out.append(stripMarkdown(line)).append('\n')
        }
    }
    return out.toString().trim()
}

/**
 * 去掉 Markdown 强调标记。
 *
 * 发行版说明是按 Markdown 写的，但 App 里用的是普通 Text 渲染（没有 Markdown 解析），
 * 不处理的话 `**加粗**` 会把星号原样显示出来，`反引号` 同理。
 */
private fun stripMarkdown(s: String): String =
    s.replace("**", "").replace("__", "").replace("`", "")

/** 去掉标题里的 `#`、表情符号和多余空白，只保留中日韩文字与 ASCII */
private fun plainHeading(line: String): String =
    line.trimStart().trimStart('#').trim()
        .filter { c -> c.code in 0x4E00..0x9FFF || c.code in 0x3000..0x303F || c.code in 0x20..0x7E }
        .trim()

@Composable
private fun ReleaseRow(
    release: GithubRelease,
    isCurrent: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 去掉点击水波纹：整行铺开的矩形反馈在卡片上很突兀，
                // 展开/收起本身有内容变化，已经够作反馈了
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onToggle
                )
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(release.tagName, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                    color = AppColors.TextPrimary)
                if (isCurrent) {
                    Spacer(Modifier.width(6.dp))
                    Text("当前", fontSize = 10.sp, color = AppColors.Success,
                        modifier = Modifier.clip(RoundedCornerShape(4.dp))
                            .background(AppColors.Success.copy(alpha = 0.12f))
                            .padding(horizontal = 5.dp, vertical = 1.dp))
                }
            }
            Icon(
                if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "收起" else "展开",
                tint = AppColors.TextSecondary
            )
        }
        if (expanded) {
            Text(
                extractReleaseHighlights(release.body).ifBlank { "本版本未填写更新详情。" },
                fontSize = 12.sp, color = AppColors.TextSecondary,
                modifier = Modifier.padding(bottom = 6.dp)
            )
            Text(
                release.publishedAt.take(10),
                fontSize = 11.sp, color = AppColors.TextSecondary.copy(alpha = 0.7f),
                modifier = Modifier.padding(bottom = 6.dp)
            )
            HorizontalDivider(color = AppColors.Border)
        }
    }
}

// ── 关于项目 ──
@Composable
private fun AboutCard() {
    val context = LocalContext.current
    // 用进程级缓存，避免每次切到「我的」都重新请求
    var repo by remember { mutableStateOf(UserPageCache.repoInfo) }

    LaunchedEffect(Unit) {
        if (!UserPageCache.repoInfoFetched) {
            val r = GithubApi.fetchRepoInfo()
            UserPageCache.repoInfo = r
            UserPageCache.repoInfoFetched = true
            repo = r
        }
    }

    BaseCard {
        Column(Modifier.padding(20.dp)) {
            Text("关于项目", fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                color = AppColors.TextPrimary)
            Spacer(Modifier.height(10.dp))

            Text("一款趣智校园第三方客户端", color = AppColors.TextSecondary, fontSize = 13.sp)

            Spacer(Modifier.height(6.dp))
            // Star 数来自 GitHub API，拉取失败时省略（不显示假数据）
            val stars = repo?.stars
            Text(
                "项目名：淋浴（Hualala）" + (stars?.let { " · ⭐ $it" } ?: ""),
                color = AppColors.TextSecondary, fontSize = 13.sp
            )

            Spacer(Modifier.height(6.dp))
            Text("制作者：${repo?.ownerLogin?.takeIf { it.isNotBlank() } ?: "yehu-imei"}",
                color = AppColors.TextSecondary, fontSize = 13.sp)

            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("联系方式：", color = AppColors.TextSecondary, fontSize = 13.sp)
                Text(CONTACT_EMAIL, color = AppColors.Accent, fontSize = 13.sp,
                    modifier = Modifier.clickable { sendEmail(context) })
            }

            Spacer(Modifier.height(14.dp))
            Button(
                onClick = { openUrl(context, repo?.htmlUrl ?: GithubApi.REPO_URL) },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent)
            ) { Text("访问 GitHub 仓库") }
        }
    }
}

/** 项目联系方式（点一下会调起邮件应用） */
private const val CONTACT_EMAIL = "2276391153@qq.com"

/** 点击邮箱调起系统邮件应用 */
private fun sendEmail(context: android.content.Context) {
    runCatching {
        context.startActivity(
            android.content.Intent(android.content.Intent.ACTION_SENDTO,
                android.net.Uri.parse("mailto:$CONTACT_EMAIL"))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/** 打开外部链接（失败静默，不影响使用） */
private fun openUrl(context: android.content.Context, url: String) {
    runCatching {
        context.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = InfoRowMinHeight),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = AppColors.TextSecondary)
        Text(value, fontWeight = FontWeight.Medium, color = AppColors.TextPrimary)
    }
}

// EditableInfoRow（带「修改」按钮的信息行）已移除：唯一的用处是「学校」，
// 而学校名现在由 /project/info/triple 自动填，不需要手改。
// 以后再加可编辑行时注意——**别用 TextButton**，它有 48dp 最小高度，
// 会把那一行顶得比别的行高一截，行距看着就散了。用 Text + clickable。


/** 信息行的最小高度。让「值 + 修改」那行和纯文字行一样高 */
private val InfoRowMinHeight = 26.dp

/**
 * 更换手机号。
 *
 * ⚠️ 验证码是发到**当前绑定**的手机号，不是新号——服务端要确认是本人操作。
 * 所以这个流程要求用户手上能拿到旧号。
 */
@Composable
private fun ChangePhoneDialog(viewModel: MainViewModel?, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var newPhone by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    var countdown by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    val currentPhone = PrefsHelper.telephone

    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = { Text("更换手机号", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                // ⚠️ 验证码发到**新**手机号（typeId=5），不是当前绑定的那个——
                // 抓包实测：`telephone=新号&typeId=5`，旧号只作为认证参数 `telPhone` 出现。
                // 发到旧号用户根本收不到，会一直提示验证码错误。
                Text(
                    "当前手机号：${currentPhone}",
                    color = AppColors.TextSecondary, fontSize = 12.sp
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = newPhone, onValueChange = { newPhone = it.trim() },
                    label = { Text("新手机号") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = code, onValueChange = { code = it.trim() },
                        label = { Text("验证码") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(10.dp))
                    Button(
                        onClick = {
                            sending = true; error = null
                            scope.launch {
                                val r = AuthRepository.sendSmsCode(newPhone, typeId = 5)
                                if (r.isSuccess) {
                                    countdown = 60
                                    while (countdown > 0) { delay(1000); countdown-- }
                                } else {
                                    error = r.exceptionOrNull()?.message ?: "验证码发送失败"
                                }
                                sending = false
                            }
                        },
                        enabled = !sending && countdown == 0 && newPhone.length == 11,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.width(86.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp)
                    ) {
                        Text(if (countdown > 0) "${countdown}s" else "发送",
                            fontSize = 13.sp, maxLines = 1)
                    }
                }
                error?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, color = AppColors.Danger, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    submitting = true; error = null
                    scope.launch {
                        try {
                            val r = NetworkModule.apiService.updatePhoneSafe(
                                newPhone, code, NetworkModule.authFields()
                            )
                            if (r.success) {
                                // 认证参数里的 telephone 也得跟着换，否则后续请求还在用旧号
                                PrefsHelper.telephone = newPhone
                                NetworkModule.restoreFromPrefs()
                                // 界面读的是 ViewModel 的 phone，不写它就还显示旧号，
                                // 得等下次重新登录才更新
                                viewModel?.phone = newPhone
                                viewModel?.toastMessage = "手机号已更换"
                                onDismiss()
                            } else {
                                error = r.displayMessage ?: "更换失败"
                            }
                        } catch (_: Exception) {
                            error = "网络异常，请重试"
                        }
                        submitting = false
                    }
                },
                enabled = !submitting && newPhone.length == 11 && code.isNotEmpty()
            ) { Text(if (submitting) "提交中…" else "确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !submitting) { Text("取消") }
        }
    )
}

/**
 * 修改密码。两条路：
 *
 * - **知道当前密码**：`/user/password/update`，要 `oldPassword`。
 * - **不知道 / 从没设过**：`/user/password/forget`，只用手机验证码，不要旧密码。
 *   手机验证码登录注册的账号（我们 App 的默认注册方式）根本没有密码可填，
 *   只能走这条——否则「修改密码」对他们就是个死按钮。
 *
 * 两个密码都用和登录同一套加密（MD5 取后 10 位大写），抓包实测官方就这么传。
 */
@Composable
private fun ChangePasswordDialog(viewModel: MainViewModel?, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    // false = 知道当前密码，true = 走短信验证码
    var smsMode by remember { mutableStateOf(false) }
    var oldPwd by remember { mutableStateOf("") }
    var newPwd by remember { mutableStateOf("") }
    var confirmPwd by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var countdown by remember { mutableStateOf(0) }
    var submitting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // 预填当前账号绑定的手机号，但**允许改**——服务端认的是请求里的这个号，
    // 换号之后 Prefs 万一没跟上，用户还能自己填对，不至于卡死在验证码发不出去
    var phone by remember { mutableStateOf(PrefsHelper.telephone) }

    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = { Text("修改密码", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                if (smsMode) {
                    OutlinedTextField(
                        value = phone, onValueChange = { phone = it.trim() },
                        label = { Text("手机号") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = code, onValueChange = { code = it.trim() },
                            label = { Text("验证码") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(10.dp))
                        Button(
                            onClick = {
                                sending = true; error = null
                                scope.launch {
                                    // typeId = 2 才是「改密码」的验证码
                                    val r = AuthRepository.sendSmsCode(phone, typeId = 2)
                                    if (r.isSuccess) {
                                        countdown = 60
                                        while (countdown > 0) { delay(1000); countdown-- }
                                    } else {
                                        error = r.exceptionOrNull()?.message ?: "验证码发送失败"
                                    }
                                    sending = false
                                }
                            },
                            enabled = !sending && countdown == 0 && phone.length == 11,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.width(86.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp)
                        ) {
                            Text(if (countdown > 0) "${countdown}s" else "发送",
                                fontSize = 13.sp, maxLines = 1)
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = oldPwd, onValueChange = { oldPwd = it },
                        label = { Text("当前密码") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = newPwd, onValueChange = { newPwd = it },
                    label = { Text("新密码") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = confirmPwd, onValueChange = { confirmPwd = it },
                    label = { Text("确认新密码") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(10.dp))
                Text(
                    if (smsMode) "直接修改密码" else "短信验证修改密码",
                    fontSize = 12.sp, color = AppColors.Accent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable(enabled = !submitting) {
                            smsMode = !smsMode
                            error = null
                        }
                        .padding(vertical = 2.dp, horizontal = 3.dp)
                )

                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = AppColors.Danger, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    // 只在本地校验「两次输入一致」；密码规则交给服务端，免得自己定的规则
                    // 和服务端不一致，把合法密码拦下来
                    if (newPwd != confirmPwd) { error = "两次输入的新密码不一致"; return@TextButton }
                    submitting = true; error = null
                    scope.launch {
                        try {
                            val r = if (smsMode) {
                                NetworkModule.apiService.forgetPasswordSafe(
                                    MD5Utils.encryptPassword(newPwd), code,
                                    NetworkModule.authFields()
                                )
                            } else {
                                NetworkModule.apiService.updatePasswordSafe(
                                    MD5Utils.encryptPassword(oldPwd),
                                    MD5Utils.encryptPassword(newPwd),
                                    NetworkModule.authFields()
                                )
                            }
                            if (r.success) {
                                viewModel?.toastMessage = "密码已修改"
                                onDismiss()
                            } else {
                                error = r.displayMessage ?: "修改失败"
                            }
                        } catch (_: Exception) {
                            error = "网络异常，请重试"
                        }
                        submitting = false
                    }
                },
                enabled = !submitting && newPwd.isNotEmpty() && confirmPwd.isNotEmpty() &&
                    (if (smsMode) code.isNotEmpty() else oldPwd.isNotEmpty())
            ) { Text(if (submitting) "提交中…" else "确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !submitting) { Text("取消") }
        }
    )
}
