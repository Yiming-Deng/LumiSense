package com.oppovisual.core

import java.util.ArrayDeque

data class StaticCandidate(
    val label: GestureId?,
    val confidence: Float,
)

data class StableGesture(
    val label: GestureId,
    val confidence: Float,
    val isNewEvent: Boolean,
)

class StaticGestureStabilizer(
    private val windowSize: Int = 5,
    private val requiredMatches: Int = 4,
    private val minimumConfidence: Float = 0.70f,
) {
    private val window = ArrayDeque<StaticCandidate>(windowSize)
    private var activeLabel: GestureId? = null

    init {
        require(windowSize > 0)
        require(requiredMatches in 1..windowSize)
        require(minimumConfidence in 0f..1f)
    }

    fun update(label: GestureId?, confidence: Float): StableGesture? {
        require(label == null || !label.isDynamic) { "Static stabilizer only accepts static gestures" }
        if (window.size == windowSize) window.removeFirst()
        window.addLast(StaticCandidate(label, confidence.coerceIn(0f, 1f)))

        val best = window
            .filter { it.label != null && it.confidence >= minimumConfidence }
            .groupBy { it.label!! }
            .maxByOrNull { it.value.size }

        if (best == null || best.value.size < requiredMatches) {
            if (window.all { it.label == null || it.confidence < minimumConfidence }) {
                activeLabel = null
            }
            return null
        }

        val averageConfidence = best.value.map { it.confidence }.average().toFloat()
        val isNewEvent = activeLabel != best.key
        activeLabel = best.key
        return StableGesture(best.key, averageConfidence, isNewEvent)
    }

    fun reset() {
        window.clear()
        activeLabel = null
    }
}

