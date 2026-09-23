package com.hualala.linyu.ui

internal object TrendDisplayPolicy {
    fun visiblePointIndices(size: Int): Set<Int> = (0 until size.coerceAtLeast(0)).toSet()

    fun plotX(index: Int, size: Int, width: Float, horizontalInset: Float): Float {
        if (size <= 1) return width / 2f
        val inset = horizontalInset.coerceIn(0f, width / 2f)
        return inset + index.toFloat() / (size - 1) * (width - inset * 2f)
    }

    fun axisIndices(size: Int, maxLabels: Int = 5): List<Int> {
        if (size <= 0) return emptyList()
        if (size <= maxLabels) return listOf(0, size / 2, size - 1).distinct()
        val step = (size - 1).toDouble() / (maxLabels - 1)
        return (0 until maxLabels).map { (it * step).toInt() }.distinct()
    }
}
