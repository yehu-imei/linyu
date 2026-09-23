package com.hualala.linyu.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
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

@Composable
fun SpendingTrendCard(
    bills: List<BillItem>,
    loading: Boolean,
    modifier: Modifier = Modifier
) {
    var range by remember { mutableStateOf(TrendRange.LAST_7_DAYS) }
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("消费趋势", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary)
                    Text("按自然日汇总", fontSize = 11.sp, color = AppColors.TextSecondary)
                }
                TrendRangeControl(range) { range = it }
            }

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
                Box(Modifier.fillMaxWidth().height(28.dp), contentAlignment = Alignment.CenterStart) {
                    Text(
                        selected?.let {
                            "${it.date.format(FULL_DATE)}  ¥ %.2f  ·  ${it.count} 次".format(it.amount)
                        } ?: "轻触折线查看每日明细",
                        color = if (selected == null) AppColors.TextSecondary else AppColors.TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = if (selected == null) FontWeight.Normal else FontWeight.Medium
                    )
                }
                TrendChart(
                    points = summary.points,
                    selectedIndex = selectedIndex,
                    onSelected = { selectedIndex = it }
                )
                TrendAxisLabels(summary.points)
            }
        }
    }
}

@Composable
private fun TrendRangeControl(selected: TrendRange, onSelected: (TrendRange) -> Unit) {
    Row(
        modifier = Modifier
            .background(AppColors.Border, RoundedCornerShape(9.dp))
            .padding(1.dp)
            .height(34.dp)
    ) {
        TrendRange.entries.forEach { range ->
            val active = range == selected
            Surface(
                color = if (active) AppColors.Accent else AppColors.Card,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .height(32.dp)
                    .width(62.dp)
                    .clickable { onSelected(range) }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        if (range == TrendRange.LAST_7_DAYS) "近 7 天" else "本月",
                        color = if (active) androidx.compose.ui.graphics.Color.White else AppColors.TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = if (active) FontWeight.Medium else FontWeight.Normal
                    )
                }
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
        val xStep = if (points.size == 1) 0f else size.width / (points.size - 1)
        fun position(index: Int): Offset {
            val ratio = (points[index].amount / maxAmount).toFloat()
            return Offset(index * xStep, bottom - usableHeight * ratio)
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
        drawPath(area, accent.copy(alpha = 0.10f))
        drawPath(line, accent, style = Stroke(width = 2.5.dp.toPx()))
        val visiblePoints = TrendDisplayPolicy.pointIndices(points.size)
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
private val FULL_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("M月d日")
