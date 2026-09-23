package com.hualala.linyu.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hualala.linyu.data.DailySpend
import com.hualala.linyu.data.SpendingAnalytics
import com.hualala.linyu.data.TrendRange
import com.hualala.linyu.model.BillItem
import com.hualala.linyu.ui.theme.AppColors
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color

enum class TrendChartType(val label: String) {
    BAR("柱形图"),
    LINE("折线图")
}

@Composable
fun SpendingTrendCard(
    bills: List<BillItem>,
    loading: Boolean,
    modifier: Modifier = Modifier
) {
    var range by remember { mutableStateOf(TrendRange.LAST_7_DAYS) }
    var chartType by remember { mutableStateOf(TrendChartType.LINE) }
    var collapsed by remember { mutableStateOf(true) }
    var selectedIndex by remember(range) { mutableIntStateOf(-1) }
    val today = remember { LocalDate.now() }
    val summary = remember(bills, range, today) {
        SpendingAnalytics.summarize(bills, range, today)
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = AppColors.Card),
        border = BorderStroke(0.8.dp, AppColors.Border),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("消费趋势", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary)
                Spacer(Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { collapsed = !collapsed }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (collapsed) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                        contentDescription = if (collapsed) "展开消费趋势" else "折叠消费趋势",
                        tint = AppColors.TextSecondary
                    )
                }
            }

            if (!collapsed) {
                Spacer(Modifier.height(18.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TrendMetric("总消费", "¥ %.2f".format(summary.total), Modifier.weight(1f))
                    TrendMetric("消费次数", "${summary.count} 次", Modifier.weight(1f))
                    TrendMetric("单次平均", "¥ %.2f".format(summary.average), Modifier.weight(1f))
                }

                Spacer(Modifier.height(18.dp))
                if (loading) {
                    Box(Modifier.fillMaxWidth().height(190.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp), color = AppColors.Accent, strokeWidth = 2.dp)
                    }
                } else {
                    val selected = summary.points.getOrNull(selectedIndex)
                    Row(
                        Modifier.fillMaxWidth().height(32.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            selected?.let {
                                "${it.date.format(SHORT_DATE)}  ¥%.2f · ${it.count}次".format(it.amount)
                            } ?: "每日明细",
                            color = if (selected == null) AppColors.TextSecondary else AppColors.TextPrimary,
                            fontSize = 12.sp,
                            fontWeight = if (selected == null) FontWeight.Normal else FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        TrendRangeControl(range, Modifier.weight(1f)) { range = it }
                        Spacer(Modifier.width(4.dp))
                        TrendChartTypeControl(chartType, Modifier.weight(1f)) { chartType = it }
                    }
                    TrendChart(summary.points, chartType, selectedIndex) { selectedIndex = it }
                    TrendAxisLabels(summary.points)
                }
            }
        }
    }
}

@Composable
private fun TrendRangeControl(
    selected: TrendRange,
    modifier: Modifier = Modifier,
    onSelected: (TrendRange) -> Unit
) {
    Row(
        modifier = modifier.height(30.dp)
    ) {
        TrendRange.entries.forEach { range ->
            val active = range == selected
            Box(
                modifier = Modifier
                    .height(30.dp)
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (active) AppColors.Accent.copy(alpha = 0.14f) else Color.Transparent)
                    .clickable(
                        interactionSource = remember(range) { MutableInteractionSource() },
                        indication = null,
                        onClick = { onSelected(range) }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (range == TrendRange.LAST_7_DAYS) "7天" else "本月",
                    color = if (active) AppColors.Accent else AppColors.TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = if (active) FontWeight.Medium else FontWeight.Normal
                )
            }
        }
    }
}

@Composable
private fun TrendChartTypeControl(
    selected: TrendChartType,
    modifier: Modifier = Modifier,
    onSelected: (TrendChartType) -> Unit
) {
    Row(modifier.height(30.dp)) {
        TrendChartType.entries.forEach { type ->
            val active = type == selected
            Box(
                modifier = Modifier
                    .height(30.dp)
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (active) AppColors.Accent.copy(alpha = 0.14f) else Color.Transparent)
                    .clickable(
                        interactionSource = remember(type) { MutableInteractionSource() },
                        indication = null,
                        onClick = { onSelected(type) }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (type == TrendChartType.BAR) "柱形" else "折线",
                    color = if (active) AppColors.Accent else AppColors.TextSecondary,
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
private fun TrendMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.Start) {
        Text(label, fontSize = 11.sp, color = AppColors.TextSecondary)
        Spacer(Modifier.height(3.dp))
        Text(value, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary)
    }
}

@Composable
private fun TrendChart(
    points: List<DailySpend>,
    chartType: TrendChartType,
    selectedIndex: Int,
    onSelected: (Int) -> Unit
) {
    val accent = AppColors.Accent
    val pointFill = AppColors.Card
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(146.dp)
            .pointerInput(points) {
                detectTapGestures { tap ->
                    if (points.isNotEmpty()) {
                        val index = if (points.size == 1) 0 else
                            (tap.x / size.width * (points.size - 1)).roundToInt()
                                .coerceIn(points.indices)
                        onSelected(index)
                    }
                }
            }
    ) {
        if (points.isEmpty()) return@Canvas
        val top = 10.dp.toPx()
        val bottom = size.height - 10.dp.toPx()
        val usableHeight = bottom - top
        val maxAmount = points.maxOfOrNull(DailySpend::amount)?.coerceAtLeast(1.0) ?: 1.0
        val barWidth = (size.width / points.size) * 0.58f
        val horizontalInset = if (chartType == TrendChartType.BAR) {
            (barWidth / 2f).coerceAtLeast(5.dp.toPx())
        } else {
            5.dp.toPx()
        }
        fun position(index: Int): Offset {
            val ratio = (points[index].amount / maxAmount).toFloat()
            return Offset(
                TrendDisplayPolicy.plotX(index, points.size, size.width, horizontalInset),
                bottom - usableHeight * ratio
            )
        }

        val line = Path().apply {
            points.indices.forEach { index ->
                val p = position(index)
                if (index == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
            }
        }
        val area = Path().apply {
            moveTo(0f, bottom)
            points.indices.forEach { index ->
                val p = position(index)
                lineTo(p.x, p.y)
            }
            lineTo(size.width, bottom)
            close()
        }
        if (chartType == TrendChartType.BAR) {
            points.indices.forEach { index ->
                val p = position(index)
                drawRoundRect(
                    color = accent.copy(alpha = 0.72f),
                    topLeft = Offset(p.x - barWidth / 2f, p.y),
                    size = Size(barWidth, bottom - p.y),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx(), 4.dp.toPx())
                )
            }
        }
        if (chartType == TrendChartType.LINE) {
            drawPath(area, accent.copy(alpha = 0.10f))
            drawPath(line, accent, style = Stroke(width = 2.5.dp.toPx()))
        }
        val visiblePoints = TrendDisplayPolicy.visiblePointIndices(points.size)
        visiblePoints.forEach { index ->
            val p = position(index)
            val selected = index == selectedIndex
            drawCircle(accent, radius = if (selected) 5.dp.toPx() else 3.dp.toPx(), center = p)
            drawCircle(pointFill, radius = if (selected) 2.dp.toPx() else 1.dp.toPx(), center = p)
        }
    }
}

@Composable
private fun TrendAxisLabels(points: List<DailySpend>) {
    if (points.isEmpty()) return
    val indices = TrendDisplayPolicy.axisIndices(points.size)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        indices.forEach { index ->
            Text(points[index].date.format(SHORT_DATE), fontSize = 10.sp, color = AppColors.TextSecondary)
        }
    }
}

private val SHORT_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("M/d")
