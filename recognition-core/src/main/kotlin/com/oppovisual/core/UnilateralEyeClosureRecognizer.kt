package com.oppovisual.core

data class UnilateralEyeClosureConfig(
    val closeThreshold: Float = 0.30f,
    val otherEyeOpenThreshold: Float = 0.28f,
    val releaseThreshold: Float = 0.22f,
    val holdMs: Long = 50,
    val releaseMs: Long = 60,
)

/** Reports a sustained one-eye-closed state; bilateral closure is always rejected. */
class UnilateralEyeClosureRecognizer(
    private val config: UnilateralEyeClosureConfig = UnilateralEyeClosureConfig(),
) {
    private var candidate: ExpressionId? = null
    private var candidateSinceMs = 0L
    private var active: ExpressionId? = null
    private var releaseSinceMs: Long? = null
    private var lastTimestampMs: Long? = null
    var confidence = 0f
        private set
    var isNewState = false
        private set

    fun update(
        timestampMs: Long,
        leftBlink: Float,
        rightBlink: Float,
        facePresent: Boolean = true,
        postureChanged: Boolean = false,
    ): ExpressionId? {
        require(lastTimestampMs == null || timestampMs >= lastTimestampMs!!) {
            "Eye closure timestamps must be monotonic"
        }
        lastTimestampMs = timestampMs
        isNewState = false
        if (!facePresent) {
            resetTransient()
            return null
        }

        if (postureChanged) {
            resetTransient()
            return null
        }

        val left = leftBlink.coerceIn(0f, 1f)
        val right = rightBlink.coerceIn(0f, 1f)
        val observed = when {
            // MediaPipe names the blendshape anatomically, while the mirrored
            // preview presents the opposite screen side to the user.
            left >= config.closeThreshold && right <= config.otherEyeOpenThreshold -> ExpressionId.RIGHT_WINK
            right >= config.closeThreshold && left <= config.otherEyeOpenThreshold -> ExpressionId.LEFT_WINK
            else -> null
        }

        active?.let { current ->
            val activeScore = if (current == ExpressionId.RIGHT_WINK) left else right
            val otherScore = if (current == ExpressionId.RIGHT_WINK) right else left
            val remainsClosed = observed == current &&
                activeScore >= config.releaseThreshold &&
                otherScore <= config.closeThreshold
            if (remainsClosed) {
                releaseSinceMs = null
                confidence = activeScore
                return current
            }
            val started = releaseSinceMs ?: timestampMs.also { releaseSinceMs = it }
            if (timestampMs - started < config.releaseMs && otherScore < config.closeThreshold) {
                confidence = activeScore
                return current
            }
            active = null
            releaseSinceMs = null
            candidate = null
            confidence = 0f
        }

        if (observed == null) {
            candidate = null
            confidence = 0f
            return null
        }
        if (candidate != observed) {
            candidate = observed
            candidateSinceMs = timestampMs
        }
        confidence = if (observed == ExpressionId.RIGHT_WINK) left else right
        if (timestampMs - candidateSinceMs < config.holdMs) return null

        active = observed
        candidate = null
        isNewState = true
        return observed
    }

    fun reset() {
        resetTransient()
        lastTimestampMs = null
    }

    private fun resetTransient() {
        candidate = null
        candidateSinceMs = 0L
        active = null
        releaseSinceMs = null
        confidence = 0f
        isNewState = false
    }
}
