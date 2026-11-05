package com.hualala.linyu.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hualala.linyu.model.DeviceInfo
import com.hualala.linyu.model.WasherMode
import com.hualala.linyu.ui.theme.AppColors
import com.hualala.linyu.ui.theme.LocalThemeMode
import com.hualala.linyu.ui.theme.ThemeMode
import com.hualala.linyu.utils.MoneyFormat

@Composable
fun WasherScreen(
    device: DeviceInfo,
    mode: WasherMode,
    stageText: String,
    elapsedSec: Int,
    remainingSec: Int,
    onMinimizeClick: () -> Unit
) {
    BackHandler(onBack = onMinimizeClick)
    val isDark = LocalThemeMode.current.value == ThemeMode.DARK
    val background = if (isDark) {
        Brush.verticalGradient(listOf(Color(0xFF2A1745), Color(0xFF24113C), Color(0xFF15102B)))
    } else {
        Brush.verticalGradient(listOf(Color(0xFFF4ECFF), Color(0xFFE9D8FD), Color(0xFFDBEAFE)))
    }

    Box(Modifier.fillMaxSize().background(background)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(device.typeEmoji, fontSize = 68.sp)
            Spacer(Modifier.height(10.dp))
            Text(
                "洗衣机 · 运行中",
                fontSize = 16.sp,
                color = AppColors.TextSecondary
            )
            Spacer(Modifier.height(6.dp))
            Text(device.displayName, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(18.dp))

            Surface(
                shape = RoundedCornerShape(22.dp),
                color = AppColors.Card,
                border = BorderStroke(1.dp, AppColors.Border)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(mode.displayName, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stageText.ifBlank { mode.displayName },
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF7C3AED),
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(16.dp))
                    LaundryStageStrip(
                        stages = listOf("洗涤", "漂洗", "脱水"),
                        current = stageText
                    )
                    Spacer(Modifier.height(18.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        StatBlock("已运行", formatClock(elapsedSec))
                        StatBlock(
                            "预计剩余",
                            if (remainingSec > 0) formatClock(remainingSec) else "以设备为准"
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "预扣 ${MoneyFormat.withSymbol(device.withholdMoney)}",
                        fontSize = 13.sp,
                        color = AppColors.TextSecondary
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            Text(
                "洗烘启动后由设备自动运行，返回首页不会结束订单",
                fontSize = 12.sp,
                color = AppColors.TextSecondary,
                textAlign = TextAlign.Center
            )
        }

        IconButton(
            onClick = onMinimizeClick,
            modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp)
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回首页（不停止设备）",
                tint = AppColors.TextPrimary
            )
        }
    }
}

@Composable
private fun LaundryStageStrip(stages: List<String>, current: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        stages.forEach { stage ->
            val active = current.contains(stage)
            Surface(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp),
                color = if (active) Color(0xFF7C3AED).copy(alpha = 0.16f)
                else AppColors.Background.copy(alpha = 0.55f)
            ) {
                Text(
                    stage,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    textAlign = TextAlign.Center,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    color = if (active) Color(0xFF7C3AED) else AppColors.TextSecondary,
                    fontSize = 13.sp
                )
            }
        }
    }
}

@Composable
private fun StatBlock(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(label, fontSize = 12.sp, color = AppColors.TextSecondary)
    }
}

private fun formatClock(totalSec: Int): String {
    val safe = totalSec.coerceAtLeast(0)
    return "%02d:%02d".format(safe / 60, safe % 60)
}
