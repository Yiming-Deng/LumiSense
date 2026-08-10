package com.oppovisual.app.ui.face

import com.oppovisual.core.ExpressionId
import com.oppovisual.core.HeadMotionId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class FaceChallengeControllerTest {
    @Test
    fun `sequence contains every supported expression and head motion once`() {
        val controller = FaceChallengeController(Random(7))
        controller.start(0, 0)

        val sequence = controller.state.sequence
        assertEquals(5, sequence.size)
        val expected = setOf(
            ChallengeTarget.Expression(ExpressionId.SMILE),
            ChallengeTarget.Expression(ExpressionId.MOUTH_OPEN),
            ChallengeTarget.Expression(ExpressionId.MOUTH_PUCKER),
            ChallengeTarget.Expression(ExpressionId.BROW_RAISE),
            ChallengeTarget.Expression(ExpressionId.LEFT_WINK),
            ChallengeTarget.Expression(ExpressionId.RIGHT_WINK),
        ) + HeadMotionId.entries.map(ChallengeTarget::HeadMotion)
        assertTrue(sequence.toSet().all { it in expected })
        assertEquals(5, sequence.toSet().size)
        assertFalse(sequence.contains(ChallengeTarget.Expression(ExpressionId.BOTH_EYES_BLINK)))
        assertFalse(sequence.zipWithNext().any { (a, b) -> a == b })
    }

    @Test
    fun `preheld or wrong expression cannot complete prompt`() {
        val controller = FaceChallengeController(Random(3))
        controller.start(0, 5)
        controller.tick(3_000, 5)
        val target = requireNotNull(controller.state.target)

        controller.onChallengeEvent(target, 5, 5, 3_100)
        assertEquals(ChallengePhase.PROMPT, controller.state.phase)
        val wrong = controller.state.sequence.first { it != target }
        controller.onChallengeEvent(wrong, 6, 6, 3_150)
        assertEquals(ChallengePhase.PROMPT, controller.state.phase)
    }

    @Test
    fun `matching new event scores speed points and combo`() {
        val controller = FaceChallengeController(Random(4))
        controller.start(0, 0)
        controller.tick(3_000, 0)
        val target = requireNotNull(controller.state.target)

        controller.onChallengeEvent(target, 1, 1, 3_500)

        assertEquals(ChallengePhase.SUCCESS, controller.state.phase)
        assertTrue(controller.state.score in 100..150)
        assertEquals(1, controller.state.combo)
        assertEquals(1, controller.state.attempts.size)
    }

    @Test
    fun `target score succeeds even when another simultaneous expression is stronger`() {
        val (controller, target) = controllerWithScoreMatchedTarget()
        val other = ExpressionId.entries.first {
            it != target && it != ExpressionId.NONE && it != ExpressionId.BOTH_EYES_BLINK
        }

        controller.onTargetExpressionScores(emptyMap(), 3_000, 0)
        controller.onTargetExpressionScores(mapOf(target to 0.90f, other to 1.0f), 3_050, 1)
        controller.onTargetExpressionScores(mapOf(target to 0.90f, other to 1.0f), 3_400, 1)

        assertEquals(ChallengePhase.SUCCESS, controller.state.phase)
        assertTrue(controller.state.attempts.single().success)
    }

    @Test
    fun `score matching rejects a target held before the prompt until it is released`() {
        val (controller, target) = controllerWithScoreMatchedTarget()

        controller.onTargetExpressionScores(mapOf(target to 0.90f), 3_000, 0)
        controller.onTargetExpressionScores(mapOf(target to 0.90f), 3_400, 0)
        assertEquals(ChallengePhase.PROMPT, controller.state.phase)

        controller.onTargetExpressionScores(emptyMap(), 3_450, 0)
        controller.onTargetExpressionScores(mapOf(target to 0.90f), 3_500, 0)
        controller.onTargetExpressionScores(mapOf(target to 0.90f), 3_850, 0)
        assertEquals(ChallengePhase.SUCCESS, controller.state.phase)
    }

    @Test
    fun `held wink state can complete a wink prompt without a new edge event`() {
        val controller = FaceChallengeController(Random(12))
        var now = 0L
        var sequence = 0L
        controller.start(now, sequence)
        now = 3_000L
        controller.tick(now, sequence)

        while (controller.state.target !is ChallengeTarget.Expression ||
            (controller.state.target as ChallengeTarget.Expression).expression !in
            setOf(ExpressionId.LEFT_WINK, ExpressionId.RIGHT_WINK)
        ) {
            sequence++
            controller.onChallengeEvent(
                target = requireNotNull(controller.state.target),
                expressionEventSequence = sequence,
                headMotionEventSequence = sequence,
                nowMs = now + 100,
            )
            now += 550L
            controller.tick(now, sequence, sequence)
        }

        val wink = (controller.state.target as ChallengeTarget.Expression).expression
        controller.onHeldExpression(wink, now + 100L)

        assertEquals(ChallengePhase.SUCCESS, controller.state.phase)
        assertTrue(controller.state.attempts.last().success)
    }

    @Test
    fun `head motion uses its own event sequence`() {
        val controller = FaceChallengeController(Random(4))
        var expressionSequence = 7L
        var headSequence = 11L
        var nowMs = 3_000L
        controller.start(0, expressionSequence, headSequence)
        controller.tick(nowMs, expressionSequence, headSequence)
        while (controller.state.target !is ChallengeTarget.HeadMotion) {
            expressionSequence++
            controller.onChallengeEvent(
                requireNotNull(controller.state.target),
                expressionSequence,
                headSequence,
                nowMs + 50,
            )
            nowMs += 500
            controller.tick(nowMs, expressionSequence, headSequence)
        }
        val headTarget = requireNotNull(controller.state.target) as ChallengeTarget.HeadMotion

        controller.onHeadMotionEvent(
            motion = headTarget.motion,
            headMotionEventSequence = headSequence,
            nowMs = nowMs + 50,
            expressionEventSequence = 99,
        )
        assertEquals(ChallengePhase.PROMPT, controller.state.phase)
        controller.onHeadMotionEvent(
            motion = headTarget.motion,
            headMotionEventSequence = ++headSequence,
            nowMs = nowMs + 100,
            expressionEventSequence = 99,
        )
        assertEquals(ChallengePhase.SUCCESS, controller.state.phase)
    }

    @Test
    fun `timeout records miss and resets combo`() {
        val controller = FaceChallengeController(Random(5))
        controller.start(0, 0)
        controller.tick(3_000, 0)
        controller.tick(5_500, 0)

        assertEquals(ChallengePhase.TIMEOUT, controller.state.phase)
        assertFalse(controller.state.attempts.single().success)
        assertEquals(0, controller.state.combo)
    }

    @Test
    fun `pause freezes prompt and resume preserves remaining time`() {
        val controller = FaceChallengeController(Random(6))
        controller.start(0, 0)
        controller.tick(3_000, 0)
        controller.pause(3_500)
        val remaining = controller.state.remainingMs

        controller.tick(20_000, 10)
        assertEquals(ChallengePhase.PAUSED, controller.state.phase)
        assertEquals(remaining, controller.state.remainingMs)
        controller.resume(20_000)
        controller.tick(20_000 + remaining - 1, 10)
        assertEquals(ChallengePhase.PROMPT, controller.state.phase)
    }

    @Test
    fun `five successful rounds end in result and preserve best score`() {
        val controller = FaceChallengeController(Random(8))
        var now = 0L
        var eventSequence = 0L
        controller.start(now, eventSequence)
        now = 3_000
        controller.tick(now, eventSequence)
        repeat(5) {
            eventSequence++
            controller.onChallengeEvent(
                requireNotNull(controller.state.target),
                eventSequence,
                eventSequence,
                now + 100,
            )
            now += 550
            controller.tick(now, eventSequence)
        }

        assertEquals(ChallengePhase.RESULT, controller.state.phase)
        assertEquals(5, controller.state.attempts.size)
        assertEquals(controller.state.score, controller.state.bestScore)
    }

    @Test
    fun `speed score spans full one hundred to one hundred fifty range`() {
        val instant = FaceChallengeController(Random(9))
        instant.start(0, 0)
        instant.tick(3_000, 0)
        instant.onChallengeEvent(requireNotNull(instant.state.target), 1, 1, 3_000)
        assertEquals(150, instant.state.attempts.single().points)

        val late = FaceChallengeController(Random(9))
        late.start(0, 0)
        late.tick(3_000, 0)
        late.onChallengeEvent(requireNotNull(late.state.target), 1, 1, 5_499)
        assertEquals(100, late.state.attempts.single().points)
    }

    @Test
    fun `quit resets game and retry starts a fresh countdown`() {
        val controller = FaceChallengeController(Random(10))
        controller.start(0, 0)
        controller.tick(3_000, 0)
        controller.onChallengeEvent(requireNotNull(controller.state.target), 1, 1, 3_100)
        controller.quit()

        assertEquals(ChallengePhase.READY, controller.state.phase)
        assertTrue(controller.state.sequence.isEmpty())
        controller.retry(4_000, 1)
        assertEquals(ChallengePhase.COUNTDOWN, controller.state.phase)
        assertEquals(5, controller.state.sequence.size)
    }

    private fun controllerWithScoreMatchedTarget(): Pair<FaceChallengeController, ExpressionId> {
        repeat(100) { seed ->
            val controller = FaceChallengeController(Random(seed))
            controller.start(0, 0)
            controller.tick(3_000, 0)
            val expression = (controller.state.target as? ChallengeTarget.Expression)?.expression
            if (expression != null && expression !in setOf(ExpressionId.LEFT_WINK, ExpressionId.RIGHT_WINK)) {
                return controller to expression
            }
        }
        error("No deterministic score-matched expression target found")
    }
}
