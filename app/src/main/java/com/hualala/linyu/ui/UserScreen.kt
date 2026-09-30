package com.hualala.linyu.ui

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.vector.ImageVector
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
    TREND("消费趋势"),
    BACKGROUND("背景装扮"),
    BOUND_ROOM("绑定寝室"),
    UPDATE("软件更新"),
    ABOUT("关于项目"),
    LOG("运行日志")
}

private fun <T> moveItem(list: List<T>, from: Int, to: Int): List<T> {
    if (from == to || from !in list.indices || to !in list.indices) return list
    val mutable = list.toMutableList()
    val item = mutable.removeAt(from)
    mutable.add(to, item)
    return mutable
}

private fun loadHiddenCards(value: String): Set<String> =
    value.split(',').map(String::trim).filter(String::isNotEmpty).toSet()

private fun saveCardOrder(order: List<UserCardType>, save: (String) -> Unit) {
    save(order.joinToString(",") { it.name })
}

private fun saveHiddenCards(hidden: Set<String>, save: (String) -> Unit) {
    save(hidden.joinToString(","))
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
    var releasesFetched: Boolean = false
    var releases: List<GithubRelease> = emptyList()
    var repoInfoFetched: Boolean = false
    var repoInfo: GithubRepoInfo? = null
}

@Composable
fun UserScreen(
    phone: String,
    onLogout: () -> Unit,
    viewModel: MainViewModel? = null,
    onSettingsVisibilityChanged: (Boolean) -> Unit = {}
) {
    LaunchedEffect(Unit) {
        viewModel?.loadUseCode()
        // 姓名 / 学号走 /account/info，一卡通余额走 /settlement/campus/userInfo。
        // 这两个都写进 Prefs，但界面读的是 ViewModel 的 State——
        // 直接读 Prefs 不会触发重组，拉完数据界面不会自己刷新。
        viewModel?.loadAccountInfo()
        viewModel?.loadCampusBalance()
    }
    val useCode = viewModel?.useCodeData

    var showSettings by remember { mutableStateOf(false) }
    var showBackgroundScreen by remember { mutableStateOf(false) }
    var showLogViewer by remember { mutableStateOf(false) }
    var editMode by remember { mutableStateOf(false) }
    val migrateLegacyLayout = remember {
        PrefsHelper.userProfileCardOrder.isBlank() &&
            PrefsHelper.userSettingsCardOrder.isBlank() &&
            PrefsHelper.userCardOrder.isNotBlank()
    }
    val restoredPages = remember {
        UserCardLayoutPolicy.restorePages(
            PrefsHelper.userProfileCardOrder,
            PrefsHelper.userSettingsCardOrder,
            PrefsHelper.userCardOrder
        )
    }
    var profileCardOrder by remember { mutableStateOf(restoredPages.profile) }
    var settingsCardOrder by remember { mutableStateOf(restoredPages.settings) }
    val legacyHiddenCards = remember { loadHiddenCards(PrefsHelper.userHiddenCards) }
    var profileHiddenCards by remember {
        mutableStateOf(
            if (migrateLegacyLayout) legacyHiddenCards else loadHiddenCards(PrefsHelper.userProfileHiddenCards)
        )
    }
    var settingsHiddenCards by remember {
        mutableStateOf(
            if (migrateLegacyLayout) legacyHiddenCards else loadHiddenCards(PrefsHelper.userSettingsHiddenCards)
        )
    }
    var themeMode by LocalThemeMode.current
    val themeReveal = LocalThemeReveal.current
    var themeBtnPos by remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(showSettings) { onSettingsVisibilityChanged(showSettings) }
    LaunchedEffect(migrateLegacyLayout) {
        if (migrateLegacyLayout) {
            saveCardOrder(profileCardOrder) { PrefsHelper.userProfileCardOrder = it }
            saveCardOrder(settingsCardOrder) { PrefsHelper.userSettingsCardOrder = it }
            saveHiddenCards(profileHiddenCards) { PrefsHelper.userProfileHiddenCards = it }
            saveHiddenCards(settingsHiddenCards) { PrefsHelper.userSettingsHiddenCards = it }
        }
    }
    DisposableEffect(Unit) { onDispose { onSettingsVisibilityChanged(false) } }

    if (showSettings) {
        SettingsScreen(
            phone = phone,
            useCode = useCode,
            viewModel = viewModel,
            cardOrder = settingsCardOrder,
            hiddenCards = settingsHiddenCards,
            onCardOrderChange = {
                settingsCardOrder = it
                saveCardOrder(it) { saved -> PrefsHelper.userSettingsCardOrder = saved }
            },
            onHiddenCardsChange = {
                settingsHiddenCards = it
                saveHiddenCards(it) { saved -> PrefsHelper.userSettingsHiddenCards = saved }
            },
            onMoveToProfile = { type ->
                val pages = UserCardLayoutPolicy.moveToProfile(type, profileCardOrder, settingsCardOrder)
                profileCardOrder = pages.profile
                settingsCardOrder = pages.settings
                profileHiddenCards = profileHiddenCards - type.name
                settingsHiddenCards = settingsHiddenCards - type.name
                saveCardOrder(profileCardOrder) { PrefsHelper.userProfileCardOrder = it }
                saveCardOrder(settingsCardOrder) { PrefsHelper.userSettingsCardOrder = it }
                saveHiddenCards(profileHiddenCards) { PrefsHelper.userProfileHiddenCards = it }
                saveHiddenCards(settingsHiddenCards) { PrefsHelper.userSettingsHiddenCards = it }
            },
            onShowBackground = { showBackgroundScreen = true },
            onShowLog = { showLogViewer = true },
            onBack = { showSettings = false },
            onLogout = onLogout
        )
        if (showLogViewer) LogViewerDialog(onDismiss = { showLogViewer = false })
        if (showBackgroundScreen) CustomBackgroundScreen(onDismiss = { showBackgroundScreen = false })
        return
    }

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp)) {
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
                IconButton(onClick = { showSettings = true }) {
                    Icon(Icons.Default.Settings, contentDescription = "打开设置", tint = AppColors.TextSecondary)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        if (PrefsHelper.usingPlaintextFallback) {
            PlaintextStorageWarning()
            Spacer(Modifier.height(16.dp))
        }
        val visibleCards = if (editMode) profileCardOrder else profileCardOrder.filter { it.name !in profileHiddenCards }
        visibleCards.forEachIndexed { index, type ->
            EditableCardSlot(
                editMode = editMode,
                title = type.title,
                isHidden = type.name in profileHiddenCards,
                canMoveUp = index > 0,
                canMoveDown = index < visibleCards.lastIndex,
                onMoveUp = {
                    profileCardOrder = moveItem(profileCardOrder, index, index - 1)
                    saveCardOrder(profileCardOrder) { PrefsHelper.userProfileCardOrder = it }
                },
                onMoveDown = {
                    profileCardOrder = moveItem(profileCardOrder, index, index + 1)
                    saveCardOrder(profileCardOrder) { PrefsHelper.userProfileCardOrder = it }
                },
                onToggleHide = {
                    profileHiddenCards = if (type.name in profileHiddenCards) {
                        profileHiddenCards - type.name
                    } else {
                        profileHiddenCards + type.name
                    }
                    saveHiddenCards(profileHiddenCards) { PrefsHelper.userProfileHiddenCards = it }
                },
                moveToOtherPageIcon = Icons.AutoMirrored.Filled.ArrowForward,
                moveToOtherPageDescription = "移到设置页面",
                onMoveToOtherPage = {
                    val pages = UserCardLayoutPolicy.moveToSettings(type, profileCardOrder, settingsCardOrder)
                    profileCardOrder = pages.profile
                    settingsCardOrder = pages.settings
                    profileHiddenCards = profileHiddenCards - type.name
                    settingsHiddenCards = settingsHiddenCards - type.name
                    saveCardOrder(profileCardOrder) { PrefsHelper.userProfileCardOrder = it }
                    saveCardOrder(settingsCardOrder) { PrefsHelper.userSettingsCardOrder = it }
                    saveHiddenCards(profileHiddenCards) { PrefsHelper.userProfileHiddenCards = it }
                    saveHiddenCards(settingsHiddenCards) { PrefsHelper.userSettingsHiddenCards = it }
                }
            ) {
                UserCardContent(type, phone, useCode, viewModel, { showBackgroundScreen = true }, { showLogViewer = true })
            }
            Spacer(Modifier.height(16.dp))
        }
        Spacer(Modifier.height(100.dp)) // 底部留出悬浮导航栏空间
    }
    if (showLogViewer) LogViewerDialog(onDismiss = { showLogViewer = false })
    if (showBackgroundScreen) CustomBackgroundScreen(onDismiss = { showBackgroundScreen = false })
}

/**
 * 加密存储降级警告。
 *
 * `PrefsHelper` 在 `EncryptedSharedPreferences` 初始化失败（设备不支持 / Keystore 损坏）
 * 时会退回明文文件 `linyu_prefs_plain`。这个降级此前是**完全静默**的——只往日志里
 * 写一行，而 `usingPlaintextFallback` 这个观察口从来没有人读。
 *
 * 后果不只是「少了个提示」：用户的 `loginCode` / `userId` / 手机号以明文躺在本机，
 * 界面上却一切如常，看上去仍然「已加密」。而 `SECURITY.md` 里明确写着
 * 「手机被 root 之前，这些数据是解不开的」——降级之后这句话**就不成立了**，
 * 属于文档承诺与现实不符。
 *
 * ⚠️ 这条**故意不做可关闭、也不进卡片排序系统**：它是安全警告，
 * 一旦能被用户隐藏或排序到角落，就等于没有提示。
 */
@Composable
private fun PlaintextStorageWarning() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = AppColors.Card),
        border = BorderStroke(1.dp, AppColors.Danger)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.Top
        ) {
            Text("⚠️", fontSize = 18.sp)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    "凭证未加密存储",
                    color = AppColors.Danger,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "本机加密存储不可用，登录凭证目前以明文保存在本地文件里。" +
                        "建议退出登录并清除应用数据后重试；若仍然如此，说明该设备不支持加密存储。",
                    color = AppColors.TextSecondary,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    phone: String,
    useCode: UseCodeData?,
    viewModel: MainViewModel?,
    cardOrder: List<UserCardType>,
    hiddenCards: Set<String>,
    onCardOrderChange: (List<UserCardType>) -> Unit,
    onHiddenCardsChange: (Set<String>) -> Unit,
    onMoveToProfile: (UserCardType) -> Unit,
    onShowBackground: () -> Unit,
    onShowLog: () -> Unit,
    onBack: () -> Unit,
    onLogout: () -> Unit
) {
    var editMode by remember { mutableStateOf(false) }
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回我的页面", tint = AppColors.TextPrimary)
            }
            Text("设置", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { editMode = !editMode }) {
                Text(if (editMode) "完成" else "编辑", color = AppColors.Accent)
            }
        }
        Spacer(Modifier.height(16.dp))
        val visibleCards = if (editMode) cardOrder else cardOrder.filter { it.name !in hiddenCards }
        visibleCards.forEachIndexed { index, type ->
            EditableCardSlot(
                editMode = editMode,
                title = type.title,
                isHidden = type.name in hiddenCards,
                canMoveUp = index > 0,
                canMoveDown = index < visibleCards.lastIndex,
                onMoveUp = {
                    onCardOrderChange(moveItem(cardOrder, index, index - 1))
                },
                onMoveDown = {
                    onCardOrderChange(moveItem(cardOrder, index, index + 1))
                },
                onToggleHide = {
                    onHiddenCardsChange(
                        if (type.name in hiddenCards) hiddenCards - type.name else hiddenCards + type.name
                    )
                },
                moveToOtherPageIcon = Icons.AutoMirrored.Filled.ArrowBack,
                moveToOtherPageDescription = "移到我的页面",
                onMoveToOtherPage = { onMoveToProfile(type) }
            ) {
                UserCardContent(type, phone, useCode, viewModel, onShowBackground, onShowLog)
            }
            Spacer(Modifier.height(16.dp))
        }
        OutlinedButton(onClick = onLogout, modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = AppColors.Danger)) {
            Text("退出登录")
        }
        Spacer(Modifier.height(8.dp))
        Text("Hualala v${BuildConfig.VERSION_NAME} · 哗啦啦啦啦让我去淋浴~",
            color = AppColors.TextSecondary, fontSize = 12.sp)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun UserCardContent(
    type: UserCardType,
    phone: String,
    useCode: UseCodeData?,
    viewModel: MainViewModel?,
    onShowBackground: () -> Unit,
    onShowLog: () -> Unit
) {
    when (type) {
        UserCardType.ACCOUNT -> AccountCard(phone, viewModel)
        UserCardType.USE_CODE -> UseCodeCard(useCode, viewModel)
        UserCardType.BOUND_ROOM -> BoundRoomCard(viewModel)
        UserCardType.NOTIFY -> NotifyCard()
        UserCardType.TREND -> TrendToggleCard()
        UserCardType.BACKGROUND -> BackgroundCard(onShowBackground)
        UserCardType.LOG -> LogCard(onShowLog)
        UserCardType.ABOUT -> AboutCard()
        UserCardType.UPDATE -> UpdateCard()
    }
}

/** 统一的卡片外观 */
@Composable
internal fun BaseCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
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
    moveToOtherPageIcon: ImageVector,
    moveToOtherPageDescription: String,
    onMoveToOtherPage: () -> Unit,
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
                IconButton(onClick = onMoveToOtherPage, modifier = Modifier.size(34.dp)) {
                    Icon(moveToOtherPageIcon, moveToOtherPageDescription, tint = AppColors.Accent)
                }
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
internal fun CollapsibleHeader(
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
