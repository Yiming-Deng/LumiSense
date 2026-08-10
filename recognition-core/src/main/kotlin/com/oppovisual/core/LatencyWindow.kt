package com.oppovisual.core

import java.util.ArrayDeque
import kotlin.math.ceil

data class LatencySummary(
    val sampleCount: Int,
    val p50Ms: Long,
    val p95Ms: Long,
    val p99Ms: Long,
    val maximumMs: Long,
)

class LatencyWindow(private val capacity: Int = 300) {
    private val values = ArrayDeque<Long>(capacity)

    init {
        require(capacity > 0)
    }

    fun add(latencyMs: Long) {
        require(latencyMs >= 0)
        if (values.size == capacity) values.removeFirst()
        values.addLast(latencyMs)
    }

    fun summary(): LatencySummary? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        return LatencySummary(
            sampleCount = sorted.size,
            p50Ms = percentile(sorted, 0.50),
            p95Ms = percentile(sorted, 0.95),
            p99Ms = percentile(sorted, 0.99),
            maximumMs = sorted.last(),
        )
    }

    fun clear() = values.clear()

    private fun percentile(sorted: List<Long>, percentile: Double): Long {
        val index = (ceil(percentile * sorted.size).toInt() - 1).coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }
}
