package com.hualala.linyu.ui

// 从 UserScreen.kt 拆出：使用码卡片与兑换弹窗（原文件里自成一块，约 310 行）。

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


@Composable
internal fun UseCodeCard(useCode: UseCodeData?, viewModel: MainViewModel?) {
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
internal fun UseCodeRedeemDialog(
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
