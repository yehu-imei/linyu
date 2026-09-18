package com.hualala.linyu.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hualala.linyu.model.DeviceInfo
import com.hualala.linyu.ui.theme.AppColors

@Composable
fun DeviceDetailDialog(
    device: DeviceInfo?,
    isActive: Boolean = false,
    isOwner: Boolean = true,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    if (device == null) return

    val emoji = device.typeEmoji
    val type = when {
        device.isDrinkingWater -> if (device.isHotWater) "饮水（热水）" else "饮水（冷水）"
        device.typeName == "洗手台" -> "洗漱"
        else -> "沐浴"
    }
    val location = device.displayName
    val startText = if (device.isDrinkingWater) "开始接水" else "开始使用"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // ⚠️ 这里用 emoji，**不要**换成 DeviceGlyph。
                    //
                    // DeviceGlyph 的图标 tint 写死是白色，因为它默认压在设备类型色的
                    // 圆底上（首页那几张卡片都有底色）。而这个标题行**没有底色**——
                    // 白图标直接压在对话框的浅色背景上，等于看不见。
                    Text(emoji, fontSize = 22.sp)
                    Spacer(Modifier.width(6.dp))
                    Text(type, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
                Text(location, fontSize = 15.sp, color = Color.Gray, fontWeight = FontWeight.Medium)
            }
        },
        text = {
            Column {
                DetailRow("类型", device.typeName)
                DetailRow("SN 码", device.snCode)
                DetailRow("MAC 地址", device.macAddress)
                DetailRow("预扣金额", "¥ ${device.withholdMoney}")
                DetailRow("状态", if (isActive) "使用中" else "空闲")
            }
        },
        confirmButton = {
            val canUse = !isActive || isOwner
            val txt = if (isActive && !isOwner) "他人使用中" else if (isActive) "恢复使用" else startText
            Button(onClick = { if (canUse) { onConfirm(); onDismiss() } },
                enabled = canUse,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (canUse) AppColors.Accent else AppColors.TextSecondary
                )) {
                Text(txt)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
