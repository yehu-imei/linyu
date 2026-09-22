package com.hualala.linyu.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hualala.linyu.data.BalanceEstimator
import com.hualala.linyu.model.BillDTO
import com.hualala.linyu.model.BillItem
import com.hualala.linyu.model.DeviceInfo
import com.hualala.linyu.ui.theme.AppColors
import com.hualala.linyu.utils.PrefsHelper
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterialApi::class)
@Composable
fun WalletScreen(viewModel: MainViewModel) {
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        viewModel.loadBills()
        // 一卡通真实余额只在这里和主页各拉一次（「我的」页面也有）——
        // 漏了这个页面的话，先开钱包页会一直按「估算」渲染
        viewModel.loadCampusBalance()
    }
    var detailBill by remember { mutableStateOf<BillDTO?>(null) }
    // 待确认的代扣：(consumeDate, 金额)。这是动钱的操作，不弹确认框直接扣太莽了
    var pendingDeduct by remember { mutableStateOf<Pair<String, String>?>(null) }

    // 进页面和下拉刷新时都拉一次未支付列表——残留账单是服务端状态，只有它说了算
    LaunchedEffect(Unit) { viewModel.loadUnpaidBills() }
    var showEditDialog by remember { mutableStateOf(false) }
    var editValue by remember { mutableStateOf("") }

    var pullRefreshing by remember { mutableStateOf(false) }
    val pullState = rememberPullRefreshState(
        refreshing = pullRefreshing,
        onRefresh = {
            pullRefreshing = true
            viewModel.pullRefresh()
            scope.launch {
                kotlinx.coroutines.delay(1000)
                pullRefreshing = false
            }
        }
    )

    // 余额：接口拿得到就用真实的，拿不到才回退「初始余额 − 之后产生的消费」。
    // 算法和主页、桌面小组件共用一份。
    //
    // 账单没到位时给 null，避免先把初始余额亮出来再跳变（见 MainViewModel.billsLoaded）——
    // 但**真实余额不受这个限制**：它是现成的数字，不是减出来的，不用等账单。
    val hasReal = viewModel.campusBalance != null
    val displayBalance = remember(
        viewModel.billList, viewModel.billsLoaded, viewModel.campusBalance,
        PrefsHelper.manualBalance, PrefsHelper.manualBalanceTime
    ) {
        if (hasReal || viewModel.billsLoaded) BalanceEstimator.estimate(viewModel.billList) else null
    }

    Box(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .pullRefresh(pullState)
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {
            Text("一卡通余额", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary)
            Spacer(Modifier.height(16.dp))

            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = AppColors.Card),
                border = BorderStroke(0.8.dp, AppColors.Border), elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)) {
                Column(Modifier.padding(24.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("账户余额 (元)", color = AppColors.TextSecondary, fontSize = 14.sp)
                        // 拿到真实余额后「手动填写」就藏起来：估算值会被真实值覆盖，
                        // 摆在那儿只会让人以为填了能改数字。
                        //
                        // ⚠️ 用 Text + clickable 而不是 TextButton：TextButton 自带
                        // 48dp 最小高度，会把这一行顶高、下面的余额数字跟着往下掉。
                        if (!hasReal) {
                            Text(
                                "手动填写", color = AppColors.Accent, fontSize = 13.sp,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable {
                                        // 预填**当前显示的余额**，不是上次填的那个初始值。
                                        // 预扣过几次之后再进来，填上次的旧数字会让人以为没生效，
                                        // 而且从这个数字改起本来也更顺手。
                                        editValue = displayBalance?.let { "%.2f".format(it) }
                                            ?: PrefsHelper.manualBalance.ifEmpty { "0" }
                                        showEditDialog = true
                                    }
                                    .padding(horizontal = 6.dp, vertical = 4.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(displayBalance?.let { "¥ %.2f".format(it) } ?: "¥ —",
                        fontSize = 36.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (hasReal) "来自校园卡账户的实时余额"
                        else "仅通过初始金额和账单进行估算，并非真实余额",
                        color = AppColors.TextSecondary, fontSize = 11.sp
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            SpendingTrendCard(
                bills = viewModel.billHistory,
                loading = viewModel.isLoadingBills && !viewModel.billsLoaded
            )

            Spacer(Modifier.height(24.dp))

            Text("账单", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary)
            Text("最近20次消费记录", fontSize = 12.sp, color = AppColors.TextSecondary)
            Spacer(Modifier.height(12.dp))

            if (viewModel.isLoadingBills) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp), color = AppColors.Accent, strokeWidth = 2.dp)
                }
            } else if (viewModel.billList.isEmpty()) {
                Text("暂无账单数据", color = AppColors.TextSecondary, modifier = Modifier.padding(top = 8.dp))
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // ⚠️ 在循环外取一次。unpaidConsumeDates 是计算属性，
                    // 写在 forEach 里的话每渲染一行就重建一次整个 Set
                    val unpaidDates = viewModel.unpaidConsumeDates
                    viewModel.billList.forEach { bill ->
                        val dto = bill.consumeBillDTO
                        BillCard(
                            bill = bill,
                            unpaid = dto.consumeDate in unpaidDates,
                            deducting = viewModel.deductingConsumeDate == dto.consumeDate,
                            onDeduct = { pendingDeduct = dto.consumeDate to dto.consumeMoney },
                            onClick = { detailBill = dto }
                        )
                    }
                }
            }

            Spacer(Modifier.height(100.dp)) // 底部留出悬浮导航栏空间
        }

        PullRefreshIndicator(
            refreshing = pullRefreshing,
            state = pullState,
            modifier = Modifier.align(Alignment.TopCenter),
            backgroundColor = AppColors.SolidSurface, // 不透明，避免半透明叠加导致内外不一致
            contentColor = AppColors.Accent
        )
    }

    // 手动余额编辑弹窗
    if (showEditDialog) {
        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            title = { Text("手动填写余额", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("请输入一卡通当前余额", color = AppColors.TextSecondary, fontSize = 14.sp)
                    Spacer(Modifier.height(4.dp))
                    Text("此操作仅用于本地估算，不会修改一卡通真实余额",
                        color = AppColors.TextSecondary, fontSize = 11.sp)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = editValue,
                        onValueChange = { v ->
                            // 最多9位整数 + 2位小数
                            if (v.isEmpty() || v.matches(Regex("\\d{0,9}(\\.\\d{0,2})?"))) editValue = v
                        },
                        label = { Text("余额 (元)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    PrefsHelper.manualBalance = editValue
                    PrefsHelper.manualBalanceTime = System.currentTimeMillis()
                    showEditDialog = false
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showEditDialog = false }) { Text("取消") }
            }
        )
    }

    // 请求代扣确认弹窗。
    // ⚠️ 这是**从一卡通里真扣钱**，必须先让用户看清楚金额再动手 ——
    // 按钮在列表里很小一颗，误触的代价是真金白银。
    pendingDeduct?.let { (date, money) ->
        DeductConfirmDialog(
            amount = money.toDoubleOrNull(),
            detail = date,
            onConfirm = {
                pendingDeduct = null
                viewModel.requestDeduct(date) { r ->
                    viewModel.toastMessage = when (r) {
                        is MainViewModel.DeductResult.Ok -> "代扣成功"
                        is MainViewModel.DeductResult.Failed -> r.reason ?: "代扣失败"
                        // 结果未知**不能说失败**：请求可能已经发出去了，
                        // 说失败就是在鼓励用户再点一次 → 扣两次
                        is MainViewModel.DeductResult.Unknown -> "结果未知，请下拉刷新确认后再操作"
                    }
                }
            },
            onDismiss = { pendingDeduct = null }
        )
    }

    // 账单详情弹窗
    if (detailBill != null) {
        val dto = detailBill!!
        AlertDialog(
            onDismissRequest = { detailBill = null },
            title = { Text("账单详情", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    val name = dto.description.substringAfter(":")
                    val dname = if (name.isNotEmpty()) DeviceInfo.formatDeviceName(name) else dto.description
                    WDetailRow("设备名", dname)
                    WDetailRow("设备类型", dto.deviceTypeLabel)
                    WDetailRow("消费金额", "-¥ ${dto.consumeMoney}")
                    WDetailRow("消费时间", dto.consumeDate.take(19))
                    WDetailRow("订单号", dto.orderId)
                }
            },
            confirmButton = { Button(onClick = { detailBill = null }) { Text("确定") } }
        )
    }
}

@Composable
private fun BillCard(
    bill: BillItem,
    unpaid: Boolean,
    deducting: Boolean,
    onDeduct: () -> Unit,
    onClick: () -> Unit
) {
    val dto = bill.consumeBillDTO
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = AppColors.Card),
        border = BorderStroke(0.8.dp, AppColors.Border), elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("${dto.deviceEmoji} ${dto.displayDesc}", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = AppColors.TextPrimary)
                Spacer(Modifier.height(2.dp))
                val tc = when {
                    dto.isDrinkingWater -> Color(0xFF10B981)          // 饮水机：绿色
                    dto.deviceTypeLabel.contains("洗手台") -> Color(0xFFFF9800)
                    else -> AppColors.Accent
                }
                Text(dto.deviceTypeLabel, fontSize = 12.sp, color = tc)
                Spacer(Modifier.height(2.dp))
                Text(dto.consumeDate.take(16), fontSize = 12.sp, color = AppColors.TextSecondary)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("-¥ ${dto.consumeMoney}", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AppColors.Danger)
                // 只有服务端说这条**没扣成功**时才出现按钮。判断依据是
                // /order/weixinScorePay/unPay/queryBill 的返回，不去猜账单里的字段含义。
                if (unpaid) {
                    Spacer(Modifier.height(6.dp))
                    Button(
                        onClick = onDeduct,
                        enabled = !deducting,
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.height(30.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AppColors.Warning,
                            disabledContainerColor = AppColors.TextSecondary.copy(alpha = 0.4f)
                        )
                    ) {
                        // 扣款中把文案也换掉：只靠变灰不够，用户会以为卡住了
                        Text(if (deducting) "扣款中…" else "请求代扣",
                            fontSize = 12.sp, color = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
private fun WDetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = AppColors.TextSecondary, fontSize = 14.sp)
        Text(value, fontWeight = FontWeight.Medium, color = AppColors.TextPrimary, fontSize = 14.sp)
    }
}
