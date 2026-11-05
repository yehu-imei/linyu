package com.hualala.linyu.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hualala.linyu.model.DeviceInfo
import com.hualala.linyu.model.WasherMode
import com.hualala.linyu.ui.theme.AppColors
import com.hualala.linyu.utils.MoneyFormat

@Composable
fun WasherModeDialog(
    device: DeviceInfo,
    modes: List<WasherMode>,
    loading: Boolean,
    busyText: String = "正在读取可用模式…",
    onStart: (WasherMode) -> Unit,
    onDismiss: () -> Unit
) {
    var selected by remember(device.snCode, modes.size) {
        mutableStateOf(modes.firstOrNull())
    }

    LaunchedEffect(modes) {
        if (selected == null) selected = modes.firstOrNull()
    }

    AlertDialog(
        onDismissRequest = { if (!loading) onDismiss() },
        title = {
            Column {
                Text(
                    "选择洗衣模式",
                    fontWeight = FontWeight.Bold
                )
                Text(
                    device.displayName,
                    color = AppColors.TextSecondary,
                    fontSize = 13.sp
                )
            }
        },
        text = {
            if (loading) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 28.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(end = 12.dp),
                        strokeWidth = 2.dp,
                        color = AppColors.Accent
                    )
                    Text(busyText, color = AppColors.TextSecondary)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(
                        modes,
                        key = { index, mode ->
                            "${mode.typeId}-${mode.id}-$index"
                        }
                    ) { _, mode ->
                        val checked = selected?.typeId == mode.typeId
                        Surface(
                            modifier = Modifier.fillMaxWidth().clickable { selected = mode },
                            shape = RoundedCornerShape(14.dp),
                            color = if (checked) AppColors.Accent.copy(alpha = 0.10f)
                            else MaterialTheme.colorScheme.surface,
                            border = BorderStroke(
                                1.dp,
                                if (checked) AppColors.Accent else AppColors.Border
                            )
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = checked, onClick = { selected = mode })
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        mode.displayName,
                                        fontWeight = FontWeight.SemiBold,
                                        color = AppColors.TextPrimary
                                    )
                                    val duration = mode.durationMinutes
                                    Text(
                                        buildString {
                                            append(MoneyFormat.withSymbol(mode.priceYuan))
                                            if (duration > 0) append(" · 约 $duration 分钟")
                                            mode.descname?.takeIf { it.isNotBlank() }?.let {
                                                append(" · $it")
                                            }
                                        },
                                        fontSize = 12.sp,
                                        color = AppColors.TextSecondary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { selected?.let(onStart) },
                enabled = !loading && selected != null
            ) {
                Text("启动")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !loading) {
                Text("取消", color = AppColors.TextSecondary)
            }
        }
    )
}
