package com.oppovisual.core

data class BilateralBlinkCycleConfig(
    val emaAlpha: Float = 1f,
    val openThreshold: Float = 0.38f,
    val closeThreshold: Float = 0.25f,
    val minimumOpenMs: Long = 0,
    val minimumClosedMs: Long = 25,
    val maximumClosedMs: Long = 600,
    val reopenMs: Long = 25,
    val cooldownMs: Long = 250,
) {
    init {
        require(emaAlpha in 0f..1f)
        require(openThreshold in 0f..1f)
        require(closeThreshold in 0f..1f)
        require(minimumOpenMs >= 0 && minimumClosedMs >= 0)
        require(maximumClosedMs >= minimumClosedMs)
        require(reopenMs >= 0 && cooldownMs >= 0)
    }
}

/** Emits once after an open -> bilateral close -> open eye cycle. */
class BilateralBlinkCycleRecognizer(
    private val config: BilateralBlinkCycleConfig = BilateralBlinkCycleConfig(),
) {
    private enum class State { WAIT_OPEN, ARMED, CLOSED }

    private var initialized = false
    private var left = 0f
    private var right = 0f
    private var state = State.WAIT_OPEN
    private var openSinceMs: Long? = null
    private var closedSinceMs: Long? = null
    private var reopenSinceMs: Long? = null
    private var cyclePeak = 0f
    private var cooldownUntilMs = 0L
    private var lastTimestampMs: Long? = null
    var lastEventConfidence: Float = 0f
        private set

    fun update(
        timestampMs: Long,
        leftBlink: Float,
        rightBlink: Float,
        facePresent: Boolean = true,
    ): Boolean {
        require(lastTimestampMs == null || timestampMs >= lastTimestampMs!!) {
            "Blink timestamps must be monotonic"
        }
        lastTimestampMs = timestampMs
        lastEventConfidence = 0f
        if (!facePresent) {
            resetTransient()
            return false
        }

        val observedLeft = leftBlink.coerceIn(0f, 1f)
        val observedRight = rightBlink.coerceIn(0f, 1f)
        if (initialized) {
            left = config.emaAlpha * observedLeft + (1f - config.emaAlpha) * left
            right = config.emaAlpha * observedRight + (1f - config.emaAlpha) * right
        } else {
            left = observedLeft
            right = observedRight
            initialized = true
        }

        val bilateral = minOf(left, right)
        val bothOpen = maxOf(left, right) <= config.openThreshold
        if (timestampMs < cooldownUntilMs) return false

        when (state) {
            State.WAIT_OPEN -> {
                if (!bothOpen) {
                    openSinceMs = null
                    return false
                }
                val started = openSinceMs ?: timestampMs.also { openSinceMs = it }
                if (timestampMs - started >= config.minimumOpenMs) state = State.ARMED
            }

            State.ARMED -> {
                if (bilateral >= config.closeThreshold) {
                    state = State.CLOSED
                    closedSinceMs = timestampMs
                    reopenSinceMs = null
                    cyclePeak = bilateral
                }
            }

            State.CLOSED -> {
                val closedStarted = requireNotNull(closedSinceMs)
                val closedDuration = timestampMs - closedStarted
                cyclePeak = maxOf(cyclePeak, bilateral)
                if (closedDuration > config.maximumClosedMs) {
                    resetTransient()
                    return false
                }
                if (!bothOpen) {
                    reopenSinceMs = null
                    return false
                }
                val reopened = reopenSinceMs ?: timestampMs.also { reopenSinceMs = it }
                if (closedDuration < config.minimumClosedMs || timestampMs - reopened < config.reopenMs) {
                    return false
                }
                cooldownUntilMs = timestampMs + config.cooldownMs
                lastEventConfidence = cyclePeak
                resetTransient()
                return true
            }
        }
        return false
    }

    fun reset() {
        resetTransient()
        cooldownUntilMs = 0
        lastTimestampMs = null
        lastEventConfidence = 0f
    }

    private fun resetTransient() {
        initialized = false
        left = 0f
        right = 0f
        state = State.WAIT_OPEN
        openSinceMs = null
        closedSinceMs = null
        reopenSinceMs = null
        cyclePeak = 0f
    }
}
