package com.hualala.linyu.ui

// 从 UserScreen.kt 拆出：设置页的四张卡片。它们只依赖 PrefsHelper / Notifier，
// 不碰 MainViewModel 状态，是最独立的一块（拆分见 docs 里的可维护性说明）。

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

/**
 * 通知设置。
 *
 * 三个开关背后是同一套后台监控（`ShowerWatchService`），
 * **关掉任何一个都只是「不发那条通知」，监控本身照常跑**——
 * 自动关停不能因为用户不想被提醒就失效（那正是小组件卡在「使用中」的老毛病）。
 */
@Composable
internal fun NotifyCard() {
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

/**
 * 消费趋势开关。
 *
 * 控制钱包页那张「消费趋势」统计卡片（`SpendingTrendCard`）的显隐。
 * 跟通知开关一个道理：只控制显隐，不影响账单数据本身。
 */
@Composable
internal fun TrendToggleCard() {
    var enabled by remember { mutableStateOf(PrefsHelper.trendEnabled) }
    BaseCard {
        Row(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(Modifier.weight(1f)) {
                Text("消费趋势", fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    color = AppColors.TextPrimary)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (enabled) "在钱包页显示消费统计图" else "已隐藏钱包页的消费统计图",
                    color = AppColors.TextSecondary, fontSize = 13.sp
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = enabled,
                onCheckedChange = { enabled = it; PrefsHelper.trendEnabled = it },
                colors = SwitchDefaults.colors(checkedTrackColor = AppColors.Accent)
            )
        }
    }
}

@Composable
internal fun NotifyRow(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
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
internal fun openNotificationSettings(context: android.content.Context) {
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    } else {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(android.net.Uri.fromParts("package", context.packageName, null))
    }
    runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
