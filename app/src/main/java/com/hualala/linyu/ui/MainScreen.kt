package com.hualala.linyu.ui

import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import androidx.compose.ui.res.painterResource
import com.hualala.linyu.QrScanActivity
import com.hualala.linyu.R
import com.hualala.linyu.data.BalanceEstimator
import com.hualala.linyu.model.DeviceInfo
import com.hualala.linyu.model.NearbyDevice
import com.hualala.linyu.ui.theme.AppColors
import com.hualala.linyu.utils.BackgroundManager
import com.hualala.linyu.utils.BackgroundState
import com.hualala.linyu.utils.PrefsHelper
import com.hualala.linyu.utils.ScanPermission

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterialApi::class)
@Composable
fun MainScreen(phone: String, viewModel: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var visible by remember { mutableStateOf(false) }
    // 是否已获得扫描权限。做成状态：用户授权后要立刻反映到界面上
    var hasScanPerm by remember { mutableStateOf(ScanPermission.granted(context)) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val ok = perms.entries.all { it.value }
        hasScanPerm = ok
        if (ok) viewModel.startScan() else viewModel.toastMessage = ScanPermission.deniedMessage()
    }

    // 扫描前才要权限：已授权直接扫，没授权才弹系统对话框。
    // 这样 Android 12+ 的用户全程只会看到「附近的设备」，不会看到定位
    fun scanWithPermission() {
        if (ScanPermission.granted(context)) viewModel.startScan()
        else permissionLauncher.launch(ScanPermission.required)
    }

    // 扫码绑定设备
    val scanLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val sn = result.data?.getStringExtra(QrScanActivity.EXTRA_SN_CODE)
            if (!sn.isNullOrEmpty()) {
                viewModel.scanBind(sn)
            } else {
                viewModel.toastMessage = "未识别到有效二维码"
            }
        } else {
            // 扫码页自己弹的说明（没相机权限、相机起不来）挂在 error extra 上。
            // 以前这里只看 RESULT_OK，这些消息全被丢掉了——用户看到的就是
            // 点了扫码、黑屏一闪、回到主页，什么提示都没有。
            result.data?.getStringExtra(QrScanActivity.EXTRA_ERROR)
                ?.takeIf { it.isNotEmpty() }
                ?.let { viewModel.toastMessage = it }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.refreshWallet()
        viewModel.loadBills()
        // 余额和学校名是账号级的，跟哪个页面无关。以前只在「我的」页面拉，
        // 结果先进主页/钱包页的时候 `campusBalance` 还是 null——界面就当拿不到
        // 真实余额，继续显示「手动填写」和「并非真实余额」。三个入口都拉一次。
        viewModel.loadCampusBalance()
        viewModel.loadSchoolName()
        // 未支付账单也要在这里拉一次：开阀失败弹窗的「立即补扣」靠它。
        // 只在钱包页拉的话，冷启动直接开阀失败时列表是空的，
        // 弹窗会退化成只有「知道了」——正好把这个功能的主要场景漏掉
        viewModel.loadUnpaidBills()
        viewModel.initManagers(context)
        // 已授权就静默开始扫描（老用户无感知）；没授权不再自动弹窗，
        // 改由界面上的「开启扫描」按钮触发，避免登录完突然被要定位权限
        if (ScanPermission.granted(context)) viewModel.startScan()
        visible = true
    }

    var pullRefreshing by remember { mutableStateOf(false) }
    val pullState = rememberPullRefreshState(
        refreshing = pullRefreshing,
        onRefresh = {
            pullRefreshing = true
            viewModel.pullRefresh()
            scanWithPermission()
            // 1秒后隐藏顶部指示器
            scope.launch {
                kotlinx.coroutines.delay(1000)
                pullRefreshing = false
            }
        }
    )

    // 按寝室筛选设备：用 remember 缓存，仅当设备列表或绑定寝室变化时才重新过滤
    val filteredDevices = remember(
        viewModel.nearbyDevices.toList(),
        // ⚠️ 用 ViewModel 里的状态，不是 PrefsHelper.boundRoom。
        // 读 pref 不会触发重组，取消绑定后列表不会立刻更新（要等下一次扫描）。
        viewModel.boundRoom
    ) {
        viewModel.nearbyDevices.filter {
            val n = it.deviceInfo?.deviceName ?: it.name
            viewModel.matchesBoundRoom(n)
        }
    }

    // 余额：接口拿得到就用真实的，拿不到才回退估算。含日期解析，开销较大，
    // 所以放在 LazyColumn 之外并用 remember 缓存，避免滚动时 item 反复组合/销毁
    // 导致重新解析日期而掉帧。
    // 账单没到位时返回 null：那时候减数为 0，算出来的就是用户填的初始余额，
    // 直接显示出来会先亮一个错数字再跳变。宁可先显示「—」——
    // 但真实余额不用等账单，它是现成的数字。
    val displayBalance = remember(
        viewModel.billList, viewModel.billsLoaded, viewModel.campusBalance,
        PrefsHelper.manualBalance, PrefsHelper.manualBalanceTime
    ) {
        if (viewModel.campusBalance != null || viewModel.billsLoaded)
            BalanceEstimator.estimate(viewModel.billList)
        else null
    }

    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
        Scaffold(
            // 背景应用到「主页」时透明，让最底层的背景图透出来
            containerColor = if (BackgroundState.config(BackgroundManager.SCOPE_HOME).enabled) Color.Transparent else AppColors.Background
        ) { padding ->
            if (viewModel.isShowering) {
                ShowerScreen(
                    emoji = viewModel.selectedDevice?.typeEmoji ?: "🚿",
                    statusText = viewModel.selectedDevice?.statusText ?: "正在沐浴中",
                    location = viewModel.selectedDevice?.locationOnly ?: "",
                    remaining = viewModel.showerRemaining,
                    elapsedSec = viewModel.showerElapsedSec,
                    autoDisConSec = viewModel.autoDisConSec,
                    isStopping = viewModel.isStopping,
                    onStopClick = { viewModel.stopShower() },
                    onMinimizeClick = { viewModel.minimizeShower() }
                )
            } else {
                Box(modifier = Modifier.padding(padding).fillMaxSize()) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().pullRefresh(pullState).padding(horizontal = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        item { Spacer(Modifier.height(8.dp)) }

                        item {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically) {
                                Column {
                                    Text("淋浴", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary)
                                    Text("Hualala", color = AppColors.TextSecondary, fontSize = 14.sp)
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    // 扫码按钮
                                    Box(Modifier.size(40.dp).clip(CircleShape).background(AppColors.Card),
                                        contentAlignment = Alignment.Center) {
                                        IconButton(onClick = {
                                            scanLauncher.launch(Intent(context, QrScanActivity::class.java))
                                        }, modifier = Modifier.size(40.dp)) {
                                            Icon(painterResource(R.drawable.ic_qr_code_scanner), null,
                                                tint = AppColors.TextPrimary, modifier = Modifier.size(20.dp))
                                        }
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    // 刷新按钮
                                    Box(Modifier.size(40.dp).clip(CircleShape).background(AppColors.Card),
                                        contentAlignment = Alignment.Center) {
                                        IconButton(onClick = { viewModel.pullRefresh(); scanWithPermission() },
                                            modifier = Modifier.size(40.dp)) {
                                            Icon(Icons.Default.Refresh, null, tint = AppColors.TextPrimary, modifier = Modifier.size(20.dp))
                                        }
                                    }
                                }
                            }
                        }

                        // 使用中的设备（多个）
                        if (viewModel.activeOrders.isNotEmpty()) {
                            item {
                                Text("使用中的设备", fontWeight = FontWeight.Bold, fontSize = 18.sp,
                                    color = AppColors.TextPrimary)
                            }
                            viewModel.activeOrders.forEach { order ->
                                item { ActiveOrderCard(order, viewModel, phone) }
                            }
                        } else if (viewModel.lastDeviceMac.isNotEmpty()) {
                            item {
                                Text("上次使用设备", fontWeight = FontWeight.Bold, fontSize = 18.sp,
                                    color = AppColors.TextPrimary)
                            }
                            item { LastDeviceCard(viewModel, phone) }
                        }

                        item {
                            Row(Modifier.fillMaxWidth().padding(top = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically) {
                                Text("附近设备", fontWeight = FontWeight.Bold, fontSize = 18.sp,
                                    color = AppColors.TextPrimary)
                                // 余额在 LazyColumn 外已算好并缓存
                                Text(displayBalance?.let { "余额 ¥%.2f".format(it) } ?: "余额 ¥ —",
                                    color = AppColors.TextSecondary, fontSize = 14.sp)
                            }
                        }

                        // 寝室筛选状态
                        if (viewModel.hasBoundRoom) {
                            item {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    // 存的就是寝室键，但老版本存过 `320房` / 完整设备名，
                                    // 过一遍 roomKey 让两种历史值都显示成同一个样子
                                    Text("🏠 已筛选：${DeviceInfo.roomKey(viewModel.boundRoom) ?: viewModel.boundRoom}",
                                        color = AppColors.Accent, fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium)
                                    Spacer(Modifier.width(8.dp))
                                    TextButton(onClick = {
                                        viewModel.applyBoundRoom("")
                                        viewModel.toastMessage = "已取消寝室筛选"
                                    }, contentPadding = PaddingValues(0.dp)) {
                                        Text("取消筛选", fontSize = 12.sp, color = AppColors.TextSecondary)
                                    }
                                }
                            }
                        }

                        if (viewModel.isScanning) {
                            item {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = AppColors.Accent)
                                        Spacer(Modifier.width(8.dp))
                                        Text("扫描中...", fontSize = 13.sp, color = AppColors.TextSecondary)
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        LegendDot(AppColors.Success, "强")
                                        Spacer(Modifier.width(8.dp))
                                        LegendDot(AppColors.Warning, "中")
                                        Spacer(Modifier.width(8.dp))
                                        LegendDot(AppColors.Danger, "弱")
                                    }
                                }
                            }
                        }

                        if (!hasScanPerm) {
                            // 未授权时不自动弹系统对话框，改为显式按钮，用户自己决定何时授权
                            item { ScanPermissionCard { scanWithPermission() } }
                        } else if (filteredDevices.isEmpty() && !viewModel.isScanning) {
                            item {
                                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                                    Text(
                                        if (viewModel.hasBoundRoom) "未找到寝室内的热水器"
                                        else "未发现热水器",
                                        color = AppColors.TextSecondary
                                    )
                                }
                            }
                        }

                        items(filteredDevices, key = { it.mac }) { device ->
                            DeviceCard(device) { viewModel.fetchDeviceInfo(device.mac) }
                        }

                        item { Spacer(Modifier.height(110.dp)) }
                    }

                    PullRefreshIndicator(
                        refreshing = pullRefreshing,
                        state = pullState,
                        modifier = Modifier.align(Alignment.TopCenter),
                        backgroundColor = AppColors.SolidSurface, // 不透明，避免半透明叠加导致内外不一致
                        contentColor = AppColors.Accent
                    )
                }
            }
        }
    }

    if (viewModel.showDeviceDetail) {
        val isActive = viewModel.selectedDevice?.snCode?.let { viewModel.isDeviceActive(it) } ?: false
        DeviceDetailDialog(viewModel.selectedDevice, isActive, viewModel.isOwner,
            onDismiss = { viewModel.showDeviceDetail = false },
            onConfirm = { viewModel.startShower(phone) })
    }

    // ── 开阀失败 ──
    //
    // 以前 `showerError` 是**只写不读**的：失败时用户只看到「正在开启热水器…」消失，
    // 然后什么都不发生，完全不知道是失败了还是没点上。
    viewModel.showerError?.let { err ->
        // ⚠️ **只有服务端明确拒绝**才可能和欠费有关。网络异常、设备被占用、结果未知
        // 都跟账单无关——把它们也引导到「补扣」，用户会因为一次 WiFi 掉线去白扣钱
        val bills = if (err.kind == ShowerErrorKind.SERVER_REJECTED) viewModel.deductibleBills
                    else emptyList()

        // 金额是服务端给的**字符串**，解析不出来时**不能悄悄按 0 算**：
        // 这张弹窗存在的唯一目的就是「会动钱，再停一下」，数字失真就失去意义了
        val amounts = bills.map { it.consumeMoney?.toDoubleOrNull() }
        val total = amounts.sumOf { it ?: 0.0 }
        val amountKnown = amounts.all { it != null }

        // key 带上 err：错误换了一条时确认态要跟着复位，不然「确认扣款」会
        // 叠在一条新错误上
        var confirming by remember(err) { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { viewModel.clearShowerError() },
            title = {
                Text(
                    if (bills.isEmpty()) "开阀失败" else "开阀失败 · 有未扣账单",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column {
                    Text(err.message, color = AppColors.TextPrimary, fontSize = 14.sp)
                    if (bills.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Text("检测到 ${bills.size} 笔没扣成功的账单：",
                            color = AppColors.Warning, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(6.dp))
                        bills.forEach { bill ->
                            Text(
                                "· ¥${bill.consumeMoney ?: "?"}   ${bill.consumeDate?.take(16) ?: ""}",
                                color = AppColors.TextSecondary, fontSize = 12.sp
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("账户有欠费时服务端会拒绝开阀",
                            color = AppColors.TextSecondary, fontSize = 11.sp)
                    }
                }
            },
            confirmButton = {
                if (bills.isNotEmpty()) {
                    Button(
                        onClick = { confirming = true },
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Warning)
                    ) { Text("立即补扣") }
                } else {
                    TextButton(onClick = { viewModel.clearShowerError() }) { Text("知道了") }
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.clearShowerError() }) { Text("关闭") }
            }
        )

        // 扣钱前的二次确认（和钱包页单笔代扣共用同一个组件，文案不会各写一份）
        if (confirming) {
            DeductConfirmDialog(
                amount = if (amountKnown) total else null,
                detail = "共 ${bills.size} 笔",
                onConfirm = {
                    confirming = false
                    viewModel.clearShowerError()
                    viewModel.deductAllUnpaid { s ->
                        viewModel.toastMessage = when {
                            // null = 一笔都没发出去（没有可补的，或被另一个代扣挡住）
                            s == null -> "没有可补扣的账单，或正在处理另一笔代扣"
                            s.unknown > 0 && s.ok == 0 && s.failed == 0 ->
                                "补扣结果未知，请下拉刷新确认后再操作"
                            s.unknown > 0 ->
                                "成功 ${s.ok} 笔、失败 ${s.failed} 笔、${s.unknown} 笔结果未知"
                            s.failed == 0 -> "补扣成功，再试一次开阀"
                            s.ok == 0 -> s.firstFailReason ?: "补扣失败，请到「钱包」页重试"
                            else -> "成功 ${s.ok} 笔、失败 ${s.failed} 笔"
                        }
                    }
                },
                onDismiss = { confirming = false }
            )
        }
    }

    // 开始使用中的加载提示（开阀确认需要几秒）
    if (viewModel.isStartingShower) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("正在开启热水器...", fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()) },
            text = {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp, color = AppColors.Accent)
                    Spacer(Modifier.width(12.dp))
                    Text("正在确认设备是否开启，请稍候", color = AppColors.TextSecondary)
                }
            },
            confirmButton = {},
            dismissButton = {}
        )
    }

    // 自动关停确认弹窗
    if (viewModel.showAutoCloseDialog) {
        AlertDialog(
            onDismissRequest = {},
            title = {
                Text("⚠️ 热水器已自动关闭", fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth())
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🚿 ", fontSize = 16.sp)
                        Text(viewModel.autoCloseDeviceName, fontWeight = FontWeight.Medium, color = AppColors.TextPrimary)
                    }
                    Spacer(Modifier.height(6.dp))
                    val sec = viewModel.autoCloseElapsed
                    val t = if (sec / 60 > 0) "${sec / 60}分${sec % 60}秒" else "${sec}秒"
                    Text("⏱ 已用 $t", color = AppColors.TextSecondary)
                    Spacer(Modifier.height(12.dp))
                    if (viewModel.autoCloseLoading) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = AppColors.Accent)
                            Spacer(Modifier.width(6.dp))
                            Text("结算中...", color = AppColors.TextSecondary)
                        }
                    } else {
                        // 金额可能是 null（结算没拿到）——那是「还不知道」，
                        // 不能显示成 ¥0.00，否则用户以为没花钱
                        Text(
                            viewModel.autoCloseConsumed?.let { "本次消费：¥%.2f".format(it) }
                                ?: "消费金额稍后可在账单中查看",
                            fontWeight = FontWeight.Bold, color = AppColors.Accent
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = { viewModel.confirmAutoClose() },
                    modifier = Modifier.fillMaxWidth()) {
                    Text("确 认")
                }
            },
            dismissButton = {}
        )
    }
}

@Composable
private fun ActiveOrderCard(order: com.hualala.linyu.model.ActiveOrder, viewModel: MainViewModel, phone: String) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = AppColors.ActiveBg),
        border = BorderStroke(0.8.dp, AppColors.Border), elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp))
                .background(
                    when (order.deviceEmoji) {
                        "🪥" -> Color(0xFFFFCC80)
                        "❄️", "♨️", "🚰" -> Color(0xFF10B981)
                        else -> AppColors.Accent
                    }
                ),
                contentAlignment = Alignment.Center) {
                // 花洒/牙刷走图标，其余（饮水机）仍画 emoji，见 DeviceGlyph
                DeviceGlyph(order.deviceEmoji, emojiSize = 22.sp, iconSize = 26.dp)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                // 和下面的「上次使用」卡片一样用 TailEllipsisText：
                // 设备名有辨识度的部分在**结尾**（房号），而且必须单行——
                // 普通 Text 放不下会折成两三行，把这张卡片撑变形。
                TailEllipsisText(
                    text = order.deviceName.ifEmpty { "热水器" },
                    modifier = Modifier.fillMaxWidth(),
                    fontWeight = FontWeight.SemiBold,
                    color = AppColors.TextPrimary
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.clip(RoundedCornerShape(6.dp)).background(AppColors.ActiveBg)
                        .padding(horizontal = 6.dp, vertical = 2.dp)) {
                        Text("使用中", color = AppColors.Warning, fontSize = 11.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text("点击恢复订单", color = AppColors.TextSecondary, fontSize = 12.sp)
                }
            }
            Button(onClick = {
                viewModel.lastDeviceSnCode = order.snCode
                viewModel.lastDeviceMac = order.deviceMac
                viewModel.startLastDevice(phone)
            }, shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent)) { Text("恢复") }
        }
    }
}

@Composable
private fun LastDeviceCard(viewModel: MainViewModel, phone: String) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = AppColors.Card),
        border = BorderStroke(0.8.dp, AppColors.Border), elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp))
                .background(
                    when (viewModel.lastDeviceEmoji) {
                        "🪥" -> Color(0xFFFFCC80)
                        "❄️", "♨️", "🚰" -> Color(0xFF10B981)
                        // 固定色，与「附近设备」卡片保持一致；不跟随背景主题色变化
                        else -> Color(0xFF2563EB)
                    }
                ),
                contentAlignment = Alignment.Center) {
                DeviceGlyph(viewModel.lastDeviceEmoji, emojiSize = 22.sp, iconSize = 26.dp)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                // 用尾部优先省略：设备名和 MAC 都是后半段才有辨识度
                // （房号在末尾、MAC 的厂商前缀是固定的）。同时彻底不换行——
                // 有的手机系统字体调到很大，普通 Text 会折成两三行把卡片撑变形。
                TailEllipsisText(
                    text = viewModel.lastDeviceName.ifEmpty { "热水器" },
                    modifier = Modifier.fillMaxWidth(),
                    fontWeight = FontWeight.SemiBold,
                    color = AppColors.TextPrimary
                )
                Spacer(Modifier.height(4.dp))
                TailEllipsisText(
                    text = "MAC: ${viewModel.lastDeviceMac}",
                    modifier = Modifier.fillMaxWidth(),
                    color = AppColors.TextSecondary,
                    fontSize = 12.sp
                )
            }
            Button(onClick = { viewModel.startLastDevice(phone) },
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent)) { Text("开始") }
        }
    }
}

/**
 * 未授予扫描权限时的引导卡片。
 * Android 12+ 只会要「附近的设备」；Android 11 及以下系统强制要定位权限，文案里说明清楚。
 */
@Composable
private fun ScanPermissionCard(onGrant: () -> Unit) {
    val needLocation = Build.VERSION.SDK_INT < Build.VERSION_CODES.S
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = AppColors.Card),
        border = BorderStroke(0.8.dp, AppColors.Border),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.padding(20.dp)) {
            Text("${if (needLocation) "🔍" else "📶"} 开启扫描，发现附近设备",
                fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary)
            Spacer(Modifier.height(6.dp))
            Text(
                if (needLocation)
                    "Android 11 及以下系统规定：扫描蓝牙设备必须授予定位权限。" +
                        "授权后仅用于发现附近的热水器。"
                else
                    "需要「附近的设备」权限才能扫描附近的热水器。",
                color = AppColors.TextSecondary, fontSize = 12.sp
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onGrant, shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent)) {
                Text("授予权限并扫描")
            }
            Spacer(Modifier.height(6.dp))
            Text("也可以直接用右上角 📷 扫码绑定设备，无需任何权限",
                color = AppColors.TextSecondary, fontSize = 11.sp)
        }
    }
}

@Composable
private fun DeviceCard(device: NearbyDevice, onClick: () -> Unit) {
    val signalColor = when {
        device.rssi >= -70 -> AppColors.Success
        device.rssi >= -85 -> AppColors.Warning
        else -> AppColors.Danger
    }

    // 按下反馈：轻微缩小 + 水波纹
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = spring(stiffness = 700f),
        label = "DeviceCardPress"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .scale(pressScale)
            .clip(RoundedCornerShape(22.dp)) // 让点击涟漪也贴合卡片圆角
            .clickable(
                interactionSource = interactionSource,
                indication = rememberRipple(color = AppColors.Accent.copy(alpha = 0.25f)),
                onClick = onClick
            ),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = AppColors.Card),
        border = BorderStroke(0.8.dp, AppColors.Border), elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(device.typeColor),
                contentAlignment = Alignment.Center) {
                DeviceGlyph(device.typeEmoji, emojiSize = 22.sp, iconSize = 26.dp)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(device.displayName, fontWeight = FontWeight.SemiBold,
                    color = AppColors.TextPrimary)
                Spacer(Modifier.height(6.dp))
                Text(device.signalText, color = signalColor, fontSize = 13.sp)
                Spacer(Modifier.height(2.dp))
                Text(device.mac, color = AppColors.TextSecondary, fontSize = 12.sp)
            }
            Icon(Icons.Default.KeyboardArrowRight, null, tint = AppColors.TextSecondary)
        }
    }
}

@Composable
fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(8.dp)) { drawCircle(color) }
        Spacer(Modifier.width(3.dp))
        Text(label, fontSize = 11.sp, color = AppColors.TextSecondary)
    }
}
