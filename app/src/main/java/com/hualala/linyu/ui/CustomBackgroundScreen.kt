package com.hualala.linyu.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hualala.linyu.ui.theme.AppColors
import com.hualala.linyu.utils.BackgroundManager
import com.hualala.linyu.utils.BackgroundState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 自定义背景设置页（全屏）。
 *
 * 「主页」（首页/账单/我的）与「使用页」是**两套互相独立**的背景，
 * 顶部可切换当前编辑哪一套。
 *
 * ⚠️ 只负责"背景图片 + 效果"，**不包含任何深色/浅色模式控件**，与主题切换完全解耦。
 */
@Composable
fun CustomBackgroundScreen(onDismiss: () -> Unit) {
    BackHandler(onBack = onDismiss)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var bgScope by remember { mutableStateOf(BackgroundManager.SCOPE_HOME) }
    val cfg = BackgroundState.config(bgScope)

    var preview by remember(bgScope, BackgroundState.imageVersion) {
        mutableStateOf(BackgroundManager.loadBitmap(context, bgScope))
    }
    var showResetConfirm by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                if (BackgroundManager.saveFromUri(context, uri, bgScope)) {
                    preview = BackgroundManager.loadBitmap(context, bgScope)
                    BackgroundState.setEnabled(bgScope, true)
                    // 换图后把三个效果参数重置为默认：透明度 100% / 模糊 0 / 亮度 100%，
                    // 否则会沿用它上一张图调过的参数，新图看着"莫名其妙变暗/变糊"
                    BackgroundState.setOpacity(bgScope, BackgroundState.DEFAULT_OPACITY)
                    BackgroundState.setBlur(bgScope, BackgroundState.DEFAULT_BLUR)
                    BackgroundState.setBrightness(bgScope, BackgroundState.DEFAULT_BRIGHTNESS)
                    BackgroundState.notifyImageChanged() // 让全局背景层重新取图
                    // 自动提取主题色（解码 + 统计放 IO 线程，避免卡 UI）
                    val color = withContext(Dispatchers.IO) {
                        BackgroundManager.extractThemeColor(context, bgScope)
                    }
                    BackgroundState.setThemeColor(bgScope, color)
                }
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(Modifier.fillMaxSize(), color = AppColors.SolidSurface) {
            Column(
                Modifier.fillMaxSize().statusBarsPadding()
                    .verticalScroll(rememberScrollState()).padding(20.dp)
            ) {
                // 顶栏
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = AppColors.TextPrimary)
                    }
                    Text("自定义背景", fontSize = 20.sp, fontWeight = FontWeight.Bold,
                        color = AppColors.TextPrimary)
                }
                Spacer(Modifier.height(12.dp))

                // 页面切换：主页 / 使用页（两套独立背景）
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ScopeChip("主页", bgScope == BackgroundManager.SCOPE_HOME,
                        Modifier.weight(1f)) { bgScope = BackgroundManager.SCOPE_HOME }
                    ScopeChip("使用页", bgScope == BackgroundManager.SCOPE_SHOWER,
                        Modifier.weight(1f)) { bgScope = BackgroundManager.SCOPE_SHOWER }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    if (bgScope == BackgroundManager.SCOPE_HOME) "应用于：首页 / 账单 / 我的"
                    else "应用于：洗澡中的使用页",
                    color = AppColors.TextSecondary, fontSize = 12.sp
                )
                Spacer(Modifier.height(14.dp))

                // 预览区：背景 + 模拟卡片，直观感受可读性
                Box(
                    Modifier.fillMaxWidth().height(200.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(AppColors.Background),
                    contentAlignment = Alignment.Center
                ) {
                    if (preview != null) {
                        Image(
                            bitmap = preview!!,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize()
                                .graphicsLayer { alpha = cfg.opacity }
                                .blur(cfg.blur.dp),
                            contentScale = ContentScale.Crop,
                            // 亮度：与全局背景层保持一致，预览即所得
                            colorFilter = ColorFilter.colorMatrix(
                                ColorMatrix().apply {
                                    val b = cfg.brightness
                                    setToScale(b, b, b, 1f)
                                }
                            )
                        )
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = AppColors.Card,
                            border = BorderStroke(0.8.dp, AppColors.Border),
                            modifier = Modifier.fillMaxWidth(0.7f).padding(16.dp)
                        ) {
                            Column(Modifier.padding(14.dp)) {
                                Text("示例卡片", fontWeight = FontWeight.SemiBold,
                                    color = AppColors.TextPrimary, fontSize = 14.sp)
                                Spacer(Modifier.height(4.dp))
                                Text("文字在背景上的可读性效果", color = AppColors.TextSecondary, fontSize = 12.sp)
                            }
                        }
                    } else {
                        Text("未设置背景", color = AppColors.TextSecondary)
                    }
                }

                Spacer(Modifier.height(16.dp))

                // 选择 / 更换图片（存到当前选中的那套）
                Button(
                    onClick = {
                        picker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent)
                ) { Text(if (preview == null) "选择背景图片" else "更换背景图片") }

                // 效果调节（有背景时才显示）
                if (preview != null) {
                    Spacer(Modifier.height(20.dp))

                    // 自动提取的主题色
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("主题色", color = AppColors.TextSecondary, fontSize = 13.sp)
                        Spacer(Modifier.width(10.dp))
                        val tc = cfg.themeColor
                        Box(
                            Modifier.size(20.dp).clip(CircleShape)
                                .background(if (tc != null) Color(tc) else AppColors.Accent)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (tc != null) "已根据图片自动提取" else "使用默认强调色",
                            color = AppColors.TextSecondary, fontSize = 12.sp
                        )
                    }
                    Spacer(Modifier.height(8.dp))

                    SliderRow(
                        label = "透明度",
                        value = cfg.opacity,
                        range = 0.2f..1.0f,
                        display = { "%.0f%%".format(it * 100) },
                        onChange = { BackgroundState.setOpacity(bgScope, it) }
                    )
                    SliderRow(
                        label = "模糊度",
                        value = cfg.blur,
                        range = 0f..20f,
                        display = { "%.0f".format(it) },
                        onChange = { BackgroundState.setBlur(bgScope, it) }
                    )
                    SliderRow(
                        label = "亮度",
                        value = cfg.brightness,
                        range = 0.4f..1.5f,
                        display = { "%.0f%%".format(it * 100) },
                        onChange = { BackgroundState.setBrightness(bgScope, it) }
                    )
                }

                Spacer(Modifier.height(24.dp))

                OutlinedButton(
                    onClick = { showResetConfirm = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AppColors.Danger)
                ) { Text("恢复默认背景（仅当前页面）") }

                Spacer(Modifier.height(60.dp))
            }
        }
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            shape = RoundedCornerShape(20.dp),
            containerColor = AppColors.SolidSurface,
            title = { Text("恢复默认背景？", fontWeight = FontWeight.Bold) },
            text = { Text("这将移除当前页面的自定义背景及相关效果。\n不会影响深色 / 浅色模式。",
                color = AppColors.TextSecondary) },
            confirmButton = {
                Button(onClick = {
                    BackgroundManager.clear(context, bgScope)
                    BackgroundState.setEnabled(bgScope, false)
                    BackgroundState.setThemeColor(bgScope, null)
                    BackgroundState.notifyImageChanged()
                    preview = null
                    showResetConfirm = false
                }) { Text("恢复默认") }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) { Text("取消") }
            }
        )
    }
}

/** 主页 / 使用页 切换按钮 */
@Composable
private fun ScopeChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) AppColors.Accent else AppColors.Card,
        border = BorderStroke(0.8.dp, AppColors.Border)
    ) {
        Box(Modifier.padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                color = if (selected) Color.White else AppColors.TextPrimary)
        }
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: (Float) -> String,
    onChange: (Float) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = AppColors.TextSecondary, fontSize = 13.sp)
            Text(display(value), color = AppColors.TextPrimary, fontSize = 13.sp)
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = AppColors.Accent,
                activeTrackColor = AppColors.Accent
            )
        )
    }
}
