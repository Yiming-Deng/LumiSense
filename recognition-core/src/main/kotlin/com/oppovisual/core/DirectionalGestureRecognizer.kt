package com.oppovisual.core

import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class DirectionalGestureConfig(
    val emaAlpha: Float = 0.35f,
    val minimumDurationMs: Long = 250,
    val maximumDurationMs: Long = 1_200,
    val minimumDisplacementInPalmWidths: Float = 1.5f,
    val axisDominanceRatio: Float = 2.0f,
    val cooldownMs: Long = 700,
    val parameterVersion: String = "motion-v1",
)

class DirectionalGestureRecognizer(
    val config: DirectionalGestureConfig = DirectionalGestureConfig(),
) {
    private val samples = ArrayDeque<MotionSample>()
    private var filteredX: Float? = null
    private var filteredY: Float? = null
    private var cooldownUntilMs: Long = Long.MIN_VALUE

    init {
        require(config.emaAlpha in 0f..1f)
        require(config.minimumDurationMs > 0)
        require(config.maximumDurationMs > config.minimumDurationMs)
        require(config.minimumDisplacementInPalmWidths > 0f)
        require(config.axisDominanceRatio > 1f)
        require(config.cooldownMs >= 0)
    }

    fun update(raw: MotionSample): MotionDecision? {
        if (raw.palmWidth <= 0f) return null

        val x = filter(filteredX, raw.palmCenterX).also { filteredX = it }
        val y = filter(filteredY, raw.palmCenterY).also { filteredY = it }
        val sample = raw.copy(palmCenterX = x, palmCenterY = y)

        if (raw.timestampMs < cooldownUntilMs) return null
        samples.addLast(sample)
        trimOldSamples(raw.timestampMs)
        val start = samples.firstOrNull() ?: return null
        val duration = sample.timestampMs - start.timestampMs
        if (duration < config.minimumDurationMs) return null

        val referenceWidth = max(start.palmWidth, 0.001f)
        val dx = (sample.palmCenterX - start.palmCenterX) / referenceWidth
        val dy = (sample.palmCenterY - start.palmCenterY) / referenceWidth
        val absX = abs(dx)
        val absY = abs(dy)
        val primary = max(absX, absY)
        val secondary = max(min(absX, absY), 0.001f)

        if (primary < config.minimumDisplacementInPalmWidths) return null
        if (primary / secondary < config.axisDominanceRatio) return null

        val gesture = if (absX > absY) {
            if (dx > 0f) GestureId.SWIPE_RIGHT else GestureId.SWIPE_LEFT
        } else {
            if (dy > 0f) GestureId.SWIPE_DOWN else GestureId.SWIPE_UP
        }
        val displacementScore = (primary / (config.minimumDisplacementInPalmWidths * 2f)).coerceIn(0f, 1f)
        val directionScore = ((primary / secondary) / (config.axisDominanceRatio * 2f)).coerceIn(0f, 1f)
        val confidence = (0.6f * displacementScore + 0.4f * directionScore).coerceIn(0f, 1f)

        cooldownUntilMs = raw.timestampMs + config.cooldownMs
        samples.clear()
        filteredX = null
        filteredY = null
        return MotionDecision(gesture, confidence, duration, primary)
    }

    fun reset() {
        samples.clear()
        filteredX = null
        filteredY = null
        cooldownUntilMs = Long.MIN_VALUE
    }

    private fun filter(previous: Float?, current: Float): Float =
        previous?.let { config.emaAlpha * current + (1f - config.emaAlpha) * it } ?: current

    private fun trimOldSamples(nowMs: Long) {
        while (samples.size > 1 && nowMs - samples.first().timestampMs > config.maximumDurationMs) {
            samples.removeFirst()
        }
    }
}

