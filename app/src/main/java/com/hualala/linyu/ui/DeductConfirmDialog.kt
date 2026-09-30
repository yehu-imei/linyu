package com.hualala.linyu.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hualala.linyu.ui.theme.AppColors
import com.hualala.linyu.utils.MoneyFormat

/**
 * 代扣前的二次确认。
 *
 * ## 为什么要收成一个组件
 *
 * 两个入口各写过一份——钱包页账单卡片上的单笔，和开阀失败弹窗里的批量补扣——
 * 很快就开始漂移了：同一句免责文案一度在两边分别写成「余额不足时**可能**扣款失败」
 * 和「余额不足时**会**扣款失败」。
 *
 * 这是**动钱**的界面，两处说法不一致会直接影响用户对风险的判断，所以文案一律写死在这里，
 * 调用方只负责给金额和一行补充说明。
 *
 * @param amount 要扣的金额。**传 null 表示金额无法识别**（服务端给的是字符串，
 *               出现过解析不出来的情况）——这时确认按钮会禁用。
 *               对着一个「¥ 0.00」点头确认，比不让他扣还糟糕。
 * @param detail 补充说明：单笔放时间，批量放「共 N 笔」
 */
@Composable
fun DeductConfirmDialog(
    amount: Double?,
    detail: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val known = amount != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("确认扣款", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text("将从校园卡中扣除：", color = AppColors.TextSecondary, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Text(
                    if (known) MoneyFormat.withSymbol(amount) else "金额无法识别",
                    fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    color = if (known) AppColors.Danger else AppColors.TextSecondary
                )
                Spacer(Modifier.height(6.dp))
                Text(detail, color = AppColors.TextSecondary, fontSize = 12.sp)
                Spacer(Modifier.height(12.dp))
                Text(
                    if (known) "余额不足时会扣款失败"
                    else "服务端返回的金额格式不对，无法确认将扣多少钱。请到「钱包」页逐笔核对后再操作。",
                    color = if (known) AppColors.TextSecondary else AppColors.Warning,
                    fontSize = 11.sp
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = known,
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Warning)
            ) { Text("确认扣款") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
