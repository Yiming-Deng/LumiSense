package com.oppovisual.core

data class ExpressionClassifierConfig(
    val emaAlpha: Float = 0.35f,
    val eyeEmaAlpha: Float = 0.75f,
    val enterThresholds: Map<ExpressionId, Float> = mapOf(
        ExpressionId.SMILE to 0.55f,
        ExpressionId.MOUTH_OPEN to 0.45f,
        ExpressionId.BROW_RAISE to 0.26f,
        ExpressionId.MOUTH_PUCKER to 0.78f,
        ExpressionId.LEFT_WINK to 0.40f,
        ExpressionId.RIGHT_WINK to 0.40f,
        ExpressionId.BOTH_EYES_BLINK to 0.60f,
    ),
    val releaseThreshold: Float = 0.30f,
    val releaseThresholdOverrides: Map<ExpressionId, Float> = mapOf(
        ExpressionId.BROW_RAISE to 0.22f,
        ExpressionId.MOUTH_PUCKER to 0.45f,
        ExpressionId.LEFT_WINK to 0.15f,
        ExpressionId.RIGHT_WINK to 0.15f,
    ),
    val minimumMargin: Float = 0.08f,
    val holdMs: Long = 180,
    val holdMsOverrides: Map<ExpressionId, Long> = mapOf(
        ExpressionId.MOUTH_OPEN to 120,
        ExpressionId.MOUTH_PUCKER to 260,
        ExpressionId.LEFT_WINK to 50,
        ExpressionId.RIGHT_WINK to 50,
    ),
    val releaseMs: Long = 160,
    val cooldownMs: Long = 250,
    val useAdaptiveNeutralBaseline: Boolean = true,
    val neutralBaselineWarmupMs: Long = 350,
) {
    init {
        require(emaAlpha in 0f..1f)
        require(eyeEmaAlpha in 0f..1f)
        require(enterThresholds.keys == PRODUCT_EXPRESSIONS)
        require(enterThresholds.values.all { it in 0f..1f })
        require(releaseThreshold in 0f..1f)
        require(releaseThresholdOverrides.keys.all { it in PRODUCT_EXPRESSIONS })
        require(releaseThresholdOverrides.values.all { it in 0f..1f })
        require(minimumMargin in 0f..1f)
        require(holdMsOverrides.keys.all { it in PRODUCT_EXPRESSIONS })
        require(holdMsOverrides.values.all { it >= 0 })
        require(holdMs >= 0 && releaseMs >= 0 && cooldownMs >= 0 && neutralBaselineWarmupMs >= 0)
    }
}

class ExpressionClassifier(
    private val config: ExpressionClassifierConfig = ExpressionClassifierConfig(),
) {
    private val smoothed = PRODUCT_EXPRESSIONS.associateWith { 0f }.toMutableMap()
    private var initialized = false
    private var lastTimestampMs: Long? = null
    private var candidate: ExpressionId? = null
    private var candidateSinceMs = 0L
    private var active = ExpressionId.NONE
    private var releaseSinceMs: Long? = null
    private var cooldownUntilMs = 0L
    private var neutralBaselineInitialized = false
    private var neutralBaselineStartedMs: Long? = null
    private val neutralBaselines = mutableMapOf(
        ExpressionId.BROW_RAISE to 0f,
        ExpressionId.MOUTH_PUCKER to 0f,
    )

    fun update(
        timestampMs: Long,
        blendshapes: Map<String, Float>,
        postureChanged: Boolean = false,
    ): ExpressionDecision {
        require(lastTimestampMs == null || timestampMs >= lastTimestampMs!!) {
            "Expression timestamps must be monotonic"
        }
        lastTimestampMs = timestampMs

        val smile = mean(blendshapes["mouthSmileLeft"], blendshapes["mouthSmileRight"])
        val jawOpen = score(blendshapes["jawOpen"])
        val rawBrowRaise = mean(
            blendshapes["browInnerUp"],
            blendshapes["browOuterUpLeft"],
            blendshapes["browOuterUpRight"],
        )
        val rawMouthPucker = score(blendshapes["mouthPucker"])

        if (postureChanged) {
            candidate = null
            active = ExpressionId.NONE
            releaseSinceMs = null
            smoothed[ExpressionId.BROW_RAISE] = 0f
            smoothed[ExpressionId.LEFT_WINK] = 0f
            smoothed[ExpressionId.RIGHT_WINK] = 0f
            if (config.useAdaptiveNeutralBaseline) {
                neutralBaselines[ExpressionId.BROW_RAISE] = rawBrowRaise
            }
        }

        if (config.useAdaptiveNeutralBaseline && !neutralBaselineInitialized) {
            neutralBaselines[ExpressionId.BROW_RAISE] = rawBrowRaise
            neutralBaselineInitialized = true
            neutralBaselineStartedMs = timestampMs
        }
        val warmingNeutralBaseline = config.useAdaptiveNeutralBaseline &&
            timestampMs - (neutralBaselineStartedMs ?: timestampMs) < config.neutralBaselineWarmupMs
        if (config.useAdaptiveNeutralBaseline && active == ExpressionId.NONE && candidate == null) {
            adaptNeutralBaseline(
                ExpressionId.BROW_RAISE,
                rawBrowRaise,
                alpha = if (warmingNeutralBaseline) 0.20f else 0.02f,
            )
        }

        val browChannels = listOf(
            score(blendshapes["browInnerUp"]),
            score(blendshapes["browOuterUpLeft"]),
            score(blendshapes["browOuterUpRight"]),
        )
        val browRise = normalizedRise(
            rawBrowRaise,
            neutralBaselines.getValue(ExpressionId.BROW_RAISE),
        )
        val raisedBrowChannels = browChannels.count { it >= 0.12f }
        val bilateralBrowRise = if (raisedBrowChannels >= 2) browRise else 0f
        val observed = mapOf(
            ExpressionId.SMILE to smile,
            ExpressionId.MOUTH_OPEN to jawOpen,
            ExpressionId.BROW_RAISE to if (warmingNeutralBaseline) 0f else bilateralBrowRise,
            ExpressionId.MOUTH_PUCKER to (
                rawMouthPucker * (1f - 0.65f * jawOpen) * (1f - 0.45f * smile)
            ).coerceIn(0f, 1f),
            ExpressionId.LEFT_WINK to asymmetricBlink(
                blendshapes["eyeBlinkRight"],
                blendshapes["eyeBlinkLeft"],
            ),
            ExpressionId.RIGHT_WINK to asymmetricBlink(
                blendshapes["eyeBlinkLeft"],
                blendshapes["eyeBlinkRight"],
            ),
            ExpressionId.BOTH_EYES_BLINK to bilateralBlink(
                blendshapes["eyeBlinkLeft"],
                blendshapes["eyeBlinkRight"],
            ),
        )
        PRODUCT_EXPRESSIONS.forEach { expression ->
            val value = observed.getValue(expression)
            val alpha = if (expression == ExpressionId.LEFT_WINK || expression == ExpressionId.RIGHT_WINK) {
                config.eyeEmaAlpha
            } else {
                config.emaAlpha
            }
            smoothed[expression] = if (initialized) {
                alpha * value + (1f - alpha) * smoothed.getValue(expression)
            } else {
                value
            }
        }
        initialized = true

        if (active != ExpressionId.NONE) {
            val activeScore = smoothed.getValue(active)
            val releaseThreshold = config.releaseThresholdOverrides[active] ?: config.releaseThreshold
            if (activeScore >= releaseThreshold) {
                releaseSinceMs = null
                return decision(active, activeScore)
            }
            val releaseStarted = releaseSinceMs ?: timestampMs.also { releaseSinceMs = it }
            if (timestampMs - releaseStarted < config.releaseMs) return decision(active, activeScore)
            active = ExpressionId.NONE
            candidate = null
            releaseSinceMs = null
            cooldownUntilMs = timestampMs + config.cooldownMs
        }

        if (timestampMs < cooldownUntilMs) return decision(ExpressionId.NONE, 0f)

        val ranked = STATIC_EXPRESSIONS
            .map { expression -> expression to smoothed.getValue(expression) }
            .sortedByDescending { it.second }
        val best = ranked.first()
        val margin = best.second - ranked.getOrNull(1)?.second.orZero()
        val accepted = best.second >= config.enterThresholds.getValue(best.first) && margin >= config.minimumMargin
        if (!accepted) {
            candidate = null
            return decision(ExpressionId.NONE, best.second)
        }

        if (candidate != best.first) {
            candidate = best.first
            candidateSinceMs = timestampMs
        }
        val requiredHoldMs = config.holdMsOverrides[best.first] ?: config.holdMs
        if (timestampMs - candidateSinceMs < requiredHoldMs) return decision(ExpressionId.NONE, best.second)

        active = best.first
        candidate = null
        return decision(active, best.second, isNewEvent = true)
    }

    fun reset() {
        smoothed.keys.forEach { smoothed[it] = 0f }
        initialized = false
        lastTimestampMs = null
        candidate = null
        active = ExpressionId.NONE
        releaseSinceMs = null
        cooldownUntilMs = 0L
        neutralBaselineInitialized = false
        neutralBaselineStartedMs = null
        neutralBaselines.keys.forEach { neutralBaselines[it] = 0f }
    }

    private fun decision(
        expression: ExpressionId,
        confidence: Float,
        isNewEvent: Boolean = false,
    ) = ExpressionDecision(
        expression = expression,
        confidence = confidence.coerceIn(0f, 1f),
        isNewEvent = isNewEvent,
        scores = smoothed.toMap(),
    )

    private fun score(value: Float?) = (value ?: 0f).coerceIn(0f, 1f)

    private fun mean(vararg values: Float?): Float =
        values.map(::score).average().toFloat()

    private fun asymmetricBlink(closedEye: Float?, openEye: Float?): Float {
        if (closedEye == null || openEye == null) return 0f
        val closed = score(closedEye)
        val open = score(openEye)
        if (closed < 0.45f || open > 0.18f) return 0f
        return closed * (1f - open)
    }

    private fun bilateralBlink(leftBlink: Float?, rightBlink: Float?): Float =
        if (leftBlink == null || rightBlink == null) 0f
        else minOf(score(leftBlink), score(rightBlink))

    private fun normalizedRise(value: Float, baseline: Float): Float =
        ((value - baseline) / (1f - baseline).coerceAtLeast(0.10f)).coerceIn(0f, 1f)

    private fun updateNeutralBaseline(expression: ExpressionId, value: Float, alpha: Float) {
        val previous = neutralBaselines.getValue(expression)
        neutralBaselines[expression] = alpha * value + (1f - alpha) * previous
    }

    private fun adaptNeutralBaseline(expression: ExpressionId, value: Float, alpha: Float) =
        updateNeutralBaseline(expression, value, alpha)

    private fun Float?.orZero() = this ?: 0f
}

val PRODUCT_EXPRESSIONS = setOf(
    ExpressionId.SMILE,
    ExpressionId.MOUTH_OPEN,
    ExpressionId.BROW_RAISE,
    ExpressionId.MOUTH_PUCKER,
    ExpressionId.LEFT_WINK,
    ExpressionId.RIGHT_WINK,
    ExpressionId.BOTH_EYES_BLINK,
)

val STATIC_EXPRESSIONS = PRODUCT_EXPRESSIONS - setOf(
    ExpressionId.LEFT_WINK,
    ExpressionId.RIGHT_WINK,
    ExpressionId.BOTH_EYES_BLINK,
)
