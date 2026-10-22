package com.hualala.linyu.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hualala.linyu.data.ServiceProject
import com.hualala.linyu.ui.theme.AppColors

@Composable
fun ProjectSwitchCard(
    currentProjectName: String,
    switching: Boolean,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = AppColors.Card),
        border = BorderStroke(0.8.dp, AppColors.Border),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("当前项目", fontSize = 12.sp, color = AppColors.TextSecondary)
                Text(
                    currentProjectName.ifBlank { "未命名项目" },
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppColors.TextPrimary
                )
            }
            if (switching) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = AppColors.Accent
                )
            } else {
                Text("切换", color = AppColors.Accent, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
fun ProjectPickerDialog(
    projects: List<ServiceProject>,
    currentProjectId: String,
    loading: Boolean,
    switching: Boolean,
    onRetry: () -> Unit,
    onSelect: (ServiceProject) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!switching) onDismiss() },
        title = {
            Column {
                Text("切换项目", fontWeight = FontWeight.Bold)
                Text(
                    "钱包、账单和设备会随项目整体切换",
                    fontSize = 12.sp,
                    color = AppColors.TextSecondary
                )
            }
        },
        text = {
            when {
                loading -> Row(
                    Modifier.fillMaxWidth().padding(vertical = 28.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                        color = AppColors.Accent
                    )
                    Spacer(Modifier.size(12.dp))
                    Text("正在读取项目列表…", color = AppColors.TextSecondary)
                }

                projects.isEmpty() -> Column(
                    Modifier.fillMaxWidth().padding(vertical = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("暂无可用项目", color = AppColors.TextSecondary)
                    TextButton(onClick = onRetry) { Text("重新加载") }
                }

                else -> LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(projects, key = { it.projectId }) { project ->
                        val selected = project.projectId == currentProjectId
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !switching && !selected) {
                                    onSelect(project)
                                },
                            shape = RoundedCornerShape(13.dp),
                            color = if (selected) AppColors.Accent.copy(alpha = 0.10f)
                            else MaterialTheme.colorScheme.surface,
                            border = BorderStroke(
                                1.dp,
                                if (selected) AppColors.Accent else AppColors.Border
                            )
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = selected,
                                    enabled = !switching,
                                    onClick = {
                                        if (!selected) onSelect(project)
                                    }
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        project.displayName,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                        color = AppColors.TextPrimary
                                    )
                                    Text(
                                        "项目 ID ${project.projectId}",
                                        fontSize = 11.sp,
                                        color = AppColors.TextSecondary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !switching) {
                Text("关闭", color = AppColors.TextSecondary)
            }
        }
    )
}
