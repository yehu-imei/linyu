package com.hualala.linyu.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hualala.linyu.ui.theme.AppColors
import com.hualala.linyu.ui.theme.LocalThemeMode
import com.hualala.linyu.ui.theme.ThemeMode
import com.hualala.linyu.utils.BackgroundManager
import com.hualala.linyu.utils.BackgroundState

@Composable
fun ShowerScreen(
    emoji: String,
    statusText: String,
    location: String,
    remaining: String,
    elapsedSec: Int,
    autoDisConSec: Int = 0,
    isStopping: Boolean = false,
    onStopClick: () -> Unit,
    onMinimizeClick: () -> Unit = {}
) {
    BackHandler(onBack = onMinimizeClick)
    val minutes = elapsedSec / 60
    val seconds = elapsedSec % 60
    val timeText = if (minutes > 0) "${minutes}分${seconds}秒" else "${seconds}秒"
    val themeMode = LocalThemeMode.current.value
    val isDark = themeMode == ThemeMode.DARK

    // 使用页是否套用了自定义背景
    val customBg = BackgroundState.config(BackgroundManager.SCOPE_SHOWER).enabled

    // 背景应用到「使用页」时不再画自身渐变，让底层背景图透出来
    val bgModifier = if (customBg) Modifier
    else Modifier.background(
        if (isDark) Brush.verticalGradient(listOf(Color(0xFF1A237E), Color(0xFF283593), Color(0xFF3949AB)))
        else Brush.verticalGradient(listOf(Color(0xFFE3F2FD), Color(0xFFBBDEFB), Color(0xFF90CAF9)))
    )

    Box(modifier = Modifier.fillMaxSize().then(bgModifier)) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // 套了自定义背景就不画 emoji：背景图本身就是画面主体，
            // 再叠一个 72sp 的表情符号会直接盖在图上，很突兀。
            // 只针对自定义背景；默认渐变背景保留 emoji（洗手台 / 卫生间都一样）
            if (!customBg) {
                // 尺寸保持 72 —— 原来 emoji 是 72sp，图标给 72dp 视觉体量才对得上
                DeviceGlyph(emoji, emojiSize = 72.sp, iconSize = 72.dp)

                Spacer(Modifier.height(8.dp))
            }

            Text(statusText, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = AppColors.Accent)

            Spacer(Modifier.height(4.dp))

            Text(location, style = MaterialTheme.typography.bodyLarge, color = AppColors.TextSecondary)

            Spacer(Modifier.height(32.dp))

            // 预扣金额卡片
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = AppColors.Card,
                border = BorderStroke(1.dp, Color.White.copy(alpha = if (isDark) 0.18f else 0.6f)),
                shadowElevation = 0.dp // 去掉阴影：半透明卡片叠在渐变上时阴影会显脏
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("💧 已预扣 ¥$remaining", fontSize = 18.sp,
                        fontWeight = FontWeight.Bold, color = AppColors.Accent)
                    Spacer(Modifier.height(4.dp))
                    Text("计费以热水器显示为准", fontSize = 12.sp, color = AppColors.TextSecondary)
                }
            }

            Spacer(Modifier.height(24.dp))

            // 计时器
            Surface(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(AppColors.Card.copy(alpha = 0.6f)),
                shape = RoundedCornerShape(12.dp),
                color = Color.Transparent // 背景已由 Modifier 绘制，避免 Surface 默认的不透明底色盖住
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("⏱", fontSize = 18.sp)
                    Spacer(Modifier.width(8.dp))
                    Text("已用 $timeText", fontSize = 16.sp,
                        fontWeight = FontWeight.Medium, color = AppColors.TextPrimary)
                }
            }

            // 自动关停倒计时
            if (autoDisConSec > 0) {
                val dMin = autoDisConSec / 60
                val dSec = autoDisConSec % 60
                val countdownText = if (dMin > 0) "${dMin}分${dSec}秒" else "${dSec}秒"
                Spacer(Modifier.height(12.dp))
                Surface(
                    modifier = Modifier.clip(RoundedCornerShape(12.dp))
                        .background(AppColors.Warning.copy(alpha = 0.12f)),
                    shape = RoundedCornerShape(12.dp),
                    color = Color.Transparent // 同上：背景由 Modifier 绘制
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("⏳", fontSize = 18.sp)
                        Spacer(Modifier.width(8.dp))
                        Text("闲置约 $countdownText 后自动关闭",
                            fontSize = 13.sp,
                            color = AppColors.Warning)
                    }
                }
            }

            Spacer(Modifier.height(48.dp))

            Button(
                onClick = onStopClick,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                enabled = !isStopping,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFE53935),
                    disabledContainerColor = Color(0xFFE53935).copy(alpha = 0.4f)
                ),
                shape = CircleShape,
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
            ) {
                if (isStopping) {
                    CircularProgressIndicator(
                        Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = Color.White
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("正在关闭...", color = Color.White, fontSize = 18.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
                } else {
                    Text("结 束 使 用", color = Color.White, fontSize = 18.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
                }
            }

            Spacer(Modifier.height(16.dp))

            Text("费率: 0.041元/升 · 设备: ${location.takeLast(6)}",
                fontSize = 11.sp, color = AppColors.TextSecondary, textAlign = TextAlign.Center)
        }

        // 左上角返回：必须放在 Column 之后（上层），否则会被 full-size 的内容层拦截点击
        IconButton(
            onClick = onMinimizeClick,
            modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp)
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回（不结束用水）",
                tint = AppColors.TextPrimary
            )
        }
    }
}
