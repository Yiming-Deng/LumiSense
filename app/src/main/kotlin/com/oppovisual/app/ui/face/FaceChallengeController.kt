package com.oppovisual.app.ui.face

import com.oppovisual.core.ExpressionId
import com.oppovisual.core.ExpressionClassifierConfig
import com.oppovisual.core.HeadMotionId
import kotlin.random.Random

class FaceChallengeController(
    private val random: Random = Random.Default,
    private val expressionConfig: ExpressionClassifierConfig = ExpressionClassifierConfig(),
    private val countdownMs: Long = 3_000,
    private val promptMs: Long = 2_500,
    private val successFeedbackMs: Long = 450,
    private val timeoutFeedbackMs: Long = 350,
) {
    var state: ChallengeUiState = ChallengeUiState()
        private set

    private var phaseEndsAtMs = 0L
    private var promptStartedAtMs = 0L
    private var promptStartExpressionSequence = 0L
    private var promptStartHeadMotionSequence = 0L
    private var pausedRemainingMs = 0L
    private var targetScoreArmed = false
    private var targetScoreSinceMs: Long? = null

    fun setBestScore(bestScore: Int) {
        state = state.copy(bestScore = bestScore.coerceAtLeast(state.bestScore))
    }

    fun start(nowMs: Long, expressionEventSequence: Long, headMotionEventSequence: Long = 0L) {
        val sequence = createSequence()
        state = ChallengeUiState(
            phase = ChallengePhase.COUNTDOWN,
            sequence = sequence,
            remainingMs = countdownMs,
            bestScore = state.bestScore,
        )
        phaseEndsAtMs = nowMs + countdownMs
        promptStartExpressionSequence = expressionEventSequence
        promptStartHeadMotionSequence = headMotionEventSequence
        pausedRemainingMs = 0L
        resetTargetScoreGate()
    }

    fun retry(
        nowMs: Long,
        expressionEventSequence: Long,
        headMotionEventSequence: Long = 0L,
    ) = start(nowMs, expressionEventSequence, headMotionEventSequence)

    fun pause(nowMs: Long) {
        if (state.phase !in RUNNING_PHASES) return
        tick(nowMs, promptStartExpressionSequence, promptStartHeadMotionSequence)
        if (state.phase !in RUNNING_PHASES) return
        pausedRemainingMs = (phaseEndsAtMs - nowMs).coerceAtLeast(0L)
        state = state.copy(
            phase = ChallengePhase.PAUSED,
            remainingMs = pausedRemainingMs,
            pausedFrom = state.phase,
        )
    }

    fun resume(nowMs: Long) {
        val phase = state.pausedFrom ?: return
        if (state.phase != ChallengePhase.PAUSED) return
        phaseEndsAtMs = nowMs + pausedRemainingMs
        if (phase == ChallengePhase.PROMPT) {
            promptStartedAtMs = nowMs - (promptMs - pausedRemainingMs)
        }
        state = state.copy(phase = phase, pausedFrom = null, remainingMs = pausedRemainingMs)
    }

    fun quit() {
        state = ChallengeUiState(bestScore = state.bestScore)
        phaseEndsAtMs = 0L
        pausedRemainingMs = 0L
        resetTargetScoreGate()
    }

    fun tick(
        nowMs: Long,
        expressionEventSequence: Long,
        headMotionEventSequence: Long = 0L,
    ): ChallengeUiState {
        if (state.phase == ChallengePhase.PAUSED || state.phase in setOf(ChallengePhase.READY, ChallengePhase.RESULT)) {
            return state
        }
        while (state.phase in RUNNING_PHASES && nowMs >= phaseEndsAtMs) {
            when (state.phase) {
                ChallengePhase.COUNTDOWN -> enterPrompt(
                    phaseEndsAtMs,
                    expressionEventSequence,
                    headMotionEventSequence,
                )
                ChallengePhase.PROMPT -> enterTimeout(phaseEndsAtMs)
                ChallengePhase.SUCCESS, ChallengePhase.TIMEOUT -> enterNextRound(
                    phaseEndsAtMs,
                    expressionEventSequence,
                    headMotionEventSequence,
                )
                else -> Unit
            }
        }
        if (state.phase in RUNNING_PHASES) {
            state = state.copy(remainingMs = (phaseEndsAtMs - nowMs).coerceAtLeast(0L))
        }
        return state
    }

    fun onExpressionEvent(
        expression: ExpressionId,
        expressionEventSequence: Long,
        nowMs: Long,
        headMotionEventSequence: Long = promptStartHeadMotionSequence,
    ): ChallengeUiState {
        return onChallengeEvent(
            target = ChallengeTarget.Expression(expression),
            expressionEventSequence = expressionEventSequence,
            headMotionEventSequence = headMotionEventSequence,
            nowMs = nowMs,
        )
    }

    fun onHeadMotionEvent(
        motion: HeadMotionId,
        headMotionEventSequence: Long,
        nowMs: Long,
        expressionEventSequence: Long = promptStartExpressionSequence,
    ): ChallengeUiState {
        return onChallengeEvent(
            target = ChallengeTarget.HeadMotion(motion),
            expressionEventSequence = expressionEventSequence,
            headMotionEventSequence = headMotionEventSequence,
            nowMs = nowMs,
        )
    }

    /**
     * A wink is a stateful signal: once the eye is closed the recognizer keeps
     * reporting that state without emitting another edge event. Challenges
     * therefore accept the held state for wink targets as well as a new event.
     */
    fun onHeldExpression(
        expression: ExpressionId,
        nowMs: Long,
        headMotionEventSequence: Long = promptStartHeadMotionSequence,
    ): ChallengeUiState {
        if (expression != ExpressionId.LEFT_WINK && expression != ExpressionId.RIGHT_WINK) {
            return state
        }
        return onChallengeEvent(
            target = ChallengeTarget.Expression(expression),
            expressionEventSequence = promptStartExpressionSequence,
            headMotionEventSequence = headMotionEventSequence,
            nowMs = nowMs,
            requireNewEvent = false,
        )
    }

    /**
     * Challenge-only target matching. A target may complete the prompt even
     * when another simultaneous expression wins the global classifier, but a
     * target already held when the prompt starts must first be released.
     */
    fun onTargetExpressionScores(
        scores: Map<ExpressionId, Float>,
        nowMs: Long,
        expressionEventSequence: Long,
        headMotionEventSequence: Long = promptStartHeadMotionSequence,
    ): ChallengeUiState {
        tick(nowMs, expressionEventSequence, headMotionEventSequence)
        val target = state.target as? ChallengeTarget.Expression ?: run {
            resetTargetScoreGate()
            return state
        }
        if (state.phase != ChallengePhase.PROMPT || target.expression in HELD_EXPRESSION_TARGETS) {
            resetTargetScoreGate()
            return state
        }

        val score = scores[target.expression] ?: 0f
        val enterThreshold = expressionConfig.enterThresholds.getValue(target.expression)
        val releaseThreshold = expressionConfig.releaseThresholdOverrides[target.expression]
            ?: expressionConfig.releaseThreshold
        if (!targetScoreArmed) {
            if (score < releaseThreshold) targetScoreArmed = true
            return state
        }
        if (score < enterThreshold) {
            targetScoreSinceMs = null
            return state
        }

        val since = targetScoreSinceMs ?: nowMs.also { targetScoreSinceMs = it }
        val holdMs = expressionConfig.holdMsOverrides[target.expression] ?: expressionConfig.holdMs
        if (nowMs - since < holdMs) return state
        return onChallengeEvent(
            target = target,
            expressionEventSequence = expressionEventSequence,
            headMotionEventSequence = headMotionEventSequence,
            nowMs = nowMs,
            requireNewEvent = false,
        )
    }

    fun onChallengeEvent(
        target: ChallengeTarget,
        expressionEventSequence: Long,
        headMotionEventSequence: Long,
        nowMs: Long,
        requireNewEvent: Boolean = true,
    ): ChallengeUiState {
        tick(nowMs, expressionEventSequence, headMotionEventSequence)
        val eventIsNew = when (target) {
            is ChallengeTarget.Expression -> expressionEventSequence > promptStartExpressionSequence
            is ChallengeTarget.HeadMotion -> headMotionEventSequence > promptStartHeadMotionSequence
        }
        if (
            state.phase != ChallengePhase.PROMPT ||
            target != state.target ||
            (requireNewEvent && !eventIsNew)
        ) {
            return state
        }

        val responseMs = (nowMs - promptStartedAtMs).coerceIn(0L, promptMs)
        val speedPoints = (((promptMs - responseMs) * MAX_SPEED_POINTS) / promptMs).toInt()
        val points = BASE_POINTS + speedPoints
        val combo = state.combo + 1
        val attempt = ChallengeAttempt(
            target = target,
            success = true,
            responseMs = responseMs,
            points = points,
        )
        state = state.copy(
            phase = ChallengePhase.SUCCESS,
            remainingMs = successFeedbackMs,
            score = state.score + points,
            combo = combo,
            bestCombo = maxOf(state.bestCombo, combo),
            attempts = state.attempts + attempt,
        )
        phaseEndsAtMs = nowMs + successFeedbackMs
        return state
    }

    private fun enterPrompt(
        nowMs: Long,
        expressionEventSequence: Long,
        headMotionEventSequence: Long,
    ) {
        promptStartedAtMs = nowMs
        promptStartExpressionSequence = expressionEventSequence
        promptStartHeadMotionSequence = headMotionEventSequence
        phaseEndsAtMs = nowMs + promptMs
        state = state.copy(phase = ChallengePhase.PROMPT, remainingMs = promptMs)
        resetTargetScoreGate()
    }

    private fun enterTimeout(nowMs: Long) {
        val target = requireNotNull(state.target)
        state = state.copy(
            phase = ChallengePhase.TIMEOUT,
            remainingMs = timeoutFeedbackMs,
            combo = 0,
            attempts = state.attempts + ChallengeAttempt(target, false, null, 0),
        )
        phaseEndsAtMs = nowMs + timeoutFeedbackMs
    }

    private fun enterNextRound(
        nowMs: Long,
        expressionEventSequence: Long,
        headMotionEventSequence: Long,
    ) {
        val nextRound = state.roundIndex + 1
        if (nextRound >= state.sequence.size) {
            val bestScore = maxOf(state.bestScore, state.score)
            state = state.copy(
                phase = ChallengePhase.RESULT,
                roundIndex = state.sequence.size,
                remainingMs = 0,
                bestScore = bestScore,
            )
            phaseEndsAtMs = 0L
            return
        }
        state = state.copy(roundIndex = nextRound)
        enterPrompt(nowMs, expressionEventSequence, headMotionEventSequence)
    }

    private fun createSequence(): List<ChallengeTarget> = CHALLENGE_TARGETS.shuffled(random).take(5)

    private fun resetTargetScoreGate() {
        targetScoreArmed = false
        targetScoreSinceMs = null
    }

    private companion object {
        const val BASE_POINTS = 100
        const val MAX_SPEED_POINTS = 50
        val HELD_EXPRESSION_TARGETS = setOf(ExpressionId.LEFT_WINK, ExpressionId.RIGHT_WINK)
        val CHALLENGE_TARGETS = listOf(
            ExpressionId.SMILE,
            ExpressionId.MOUTH_OPEN,
            ExpressionId.MOUTH_PUCKER,
            ExpressionId.BROW_RAISE,
            ExpressionId.LEFT_WINK,
            ExpressionId.RIGHT_WINK,
        ).map(ChallengeTarget::Expression) + HeadMotionId.entries.map(
            ChallengeTarget::HeadMotion,
        )
        val RUNNING_PHASES = setOf(
            ChallengePhase.COUNTDOWN,
            ChallengePhase.PROMPT,
            ChallengePhase.SUCCESS,
            ChallengePhase.TIMEOUT,
        )
    }
}
