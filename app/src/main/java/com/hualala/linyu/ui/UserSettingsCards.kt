package com.hualala.linyu.ui

// 从 UserScreen.kt 拆出：设置页的四张卡片。它们只依赖 PrefsHelper / Notifier，
// 不碰 MainViewModel 状态，是最独立的一块（拆分见 docs 里的可维护性说明）。

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.hualala.linyu.BuildConfig
import com.hualala.linyu.R
import com.hualala.linyu.service.ShowerWatchService
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

@Composable
internal fun NotifyCard() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(PrefsHelper.notifyEnabled) }
    var systemAllowed by remember { mutableStateOf(Notifier.canNotify(context)) }
    var hasLegacyRestrictions by remember { mutableStateOf(PrefsHelper.hasLegacyNotificationRestrictions) }

    fun refreshSystemPermission() {
        val allowed = Notifier.canNotify(context)
        if (allowed && !systemAllowed && enabled) ShowerWatchService.refreshNotification(context)
        systemAllowed = allowed
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshSystemPermission() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refreshSystemPermission() }

    BaseCard {
        Row(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(Modifier.weight(1f)) {
                Text("通知", fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    color = AppColors.TextPrimary)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = when {
                        !enabled -> "用水通知已关闭"
                        !systemAllowed -> "系统未允许通知，点击前往设置"
                        else -> "通知已开启，点击跳转详细设置"
                    },
                    modifier = if (enabled) Modifier.clickable {
                        openNotificationSettings(context)
                    } else Modifier,
                    color = if (enabled && !systemAllowed) AppColors.Warning else AppColors.TextSecondary,
                    fontSize = 13.sp
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = enabled,
                onCheckedChange = { value ->
                    enabled = value
                    PrefsHelper.notifyEnabled = value
                    ShowerWatchService.refreshNotification(context)
                    if (value && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                            PackageManager.PERMISSION_GRANTED
                    ) {
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                },
                colors = SwitchDefaults.colors(checkedTrackColor = AppColors.Accent)
            )
        }
        if (enabled && hasLegacyRestrictions) {
            Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 12.dp)) {
                Text("旧版关闭的通知仍未开启，恢复后可在系统设置中管理。",
                    color = AppColors.TextSecondary, fontSize = 13.sp)
                TextButton(onClick = {
                    PrefsHelper.restoreLegacyNotificationOptions()
                    hasLegacyRestrictions = false
                    ShowerWatchService.refreshNotification(context)
                }) { Text("恢复分项通知") }
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
                    if (enabled) "在账单页显示消费统计图" else "已隐藏账单页的消费统计图",
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
internal fun KeepScreenOnCard() {
    var enabled by remember { mutableStateOf(PrefsHelper.keepScreenOnDuringUse) }
    BaseCard {
        Row(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(Modifier.weight(1f)) {
                Text("屏幕常亮", fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    color = AppColors.TextPrimary)
                Spacer(Modifier.height(4.dp))
                Text("使用设备时保持屏幕亮起", color = AppColors.TextSecondary, fontSize = 13.sp)
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = enabled,
                onCheckedChange = { enabled = it; PrefsHelper.keepScreenOnDuringUse = it },
                colors = SwitchDefaults.colors(checkedTrackColor = AppColors.Accent)
            )
        }
    }
}

/** 进入系统设置前刷新渠道名称，保留已有渠道上的用户选择。 */
internal fun openNotificationSettings(context: android.content.Context) {
    Notifier.ensureChannels(context)
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
