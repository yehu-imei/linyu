package com.hualala.linyu.ui

import kotlin.math.ceil

internal object TrendDisplayPolicy {
    fun pointIndices(size: Int, maxPoints: Int = 14): Set<Int> {
        if (size <= 0) return emptySet()
        if (size <= maxPoints) return (0 until size).toSet()
        val stride = ceil(size.toDouble() / maxPoints).toInt()
        return buildSet {
            add(0)
            for (index in 0 until size step stride) add(index)
            add(size - 1)
        }
    }

    fun pointIndices(size: Int, values: List<Double>, maxPoints: Int = 14): Set<Int> {
        val base = pointIndices(size, maxPoints)
        val peak = values.indices.maxByOrNull { values[it] } ?: return base
        return base + peak
    }

    fun visiblePointIndices(
        size: Int,
        values: List<Double>,
        selectedIndex: Int,
        maxPoints: Int = 14
    ): Set<Int> = buildSet {
        addAll(pointIndices(size, values, maxPoints))
        if (selectedIndex in 0 until size) add(selectedIndex)
    }

    fun axisIndices(size: Int, maxLabels: Int = 5): List<Int> {
        if (size <= 0) return emptyList()
        if (size <= maxLabels) return listOf(0, size / 2, size - 1).distinct()
        val step = (size - 1).toDouble() / (maxLabels - 1)
        return (0 until maxLabels).map { (it * step).toInt() }.distinct()
    }
}
