package com.oppovisual.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExpressionClassifierTest {
    private val config = ExpressionClassifierConfig(
        emaAlpha = 1f,
        holdMs = 100,
        releaseMs = 100,
        cooldownMs = 100,
        useAdaptiveNeutralBaseline = false,
        holdMsOverrides = emptyMap(),
    )

    @Test
    fun defaultConfigRecognizesModerateMouthOpeningAfterShortHold() {
        val classifier = ExpressionClassifier(
            ExpressionClassifierConfig(useAdaptiveNeutralBaseline = false),
        )
        val moderateOpen = mapOf("jawOpen" to 0.50f)

        assertEquals(ExpressionId.NONE, classifier.update(0, moderateOpen).expression)
        val confirmed = classifier.update(120, moderateOpen)
        assertEquals(ExpressionId.MOUTH_OPEN, confirmed.expression)
        assertTrue(confirmed.isNewEvent)
    }

    @Test
    fun confirmsStrongExpressionAfterHoldAndEmitsOnlyOnce() {
        val classifier = ExpressionClassifier(config)
        assertEquals(ExpressionId.NONE, classifier.update(0, mapOf("jawOpen" to 0.9f)).expression)

        val confirmed = classifier.update(100, mapOf("jawOpen" to 0.9f))
        assertEquals(ExpressionId.MOUTH_OPEN, confirmed.expression)
        assertTrue(confirmed.isNewEvent)

        val stable = classifier.update(150, mapOf("jawOpen" to 0.8f))
        assertEquals(ExpressionId.MOUTH_OPEN, stable.expression)
        assertFalse(stable.isNewEvent)
    }

    @Test
    fun rejectsAmbiguousTopScores() {
        val classifier = ExpressionClassifier(config)
        val values = mapOf(
            "mouthSmileLeft" to 0.70f,
            "mouthSmileRight" to 0.70f,
            "jawOpen" to 0.66f,
        )
        assertEquals(ExpressionId.NONE, classifier.update(0, values).expression)
        assertEquals(ExpressionId.NONE, classifier.update(200, values).expression)
    }

    @Test
    fun appliesReleaseHysteresisAndCooldown() {
        val classifier = ExpressionClassifier(config)
        classifier.update(0, mapOf("mouthPucker" to 0.9f))
        classifier.update(100, mapOf("mouthPucker" to 0.9f))

        assertEquals(ExpressionId.MOUTH_PUCKER, classifier.update(150, emptyMap()).expression)
        assertEquals(ExpressionId.NONE, classifier.update(250, emptyMap()).expression)
        assertEquals(ExpressionId.NONE, classifier.update(300, mapOf("mouthPucker" to 0.9f)).expression)
    }

    @Test
    fun averagesBilateralSmileChannels() {
        val classifier = ExpressionClassifier(config)
        val decision = classifier.update(
            0,
            mapOf("mouthSmileLeft" to 1f, "mouthSmileRight" to 0.6f),
        )
        assertEquals(0.8f, decision.scores.getValue(ExpressionId.SMILE), 0.0001f)
    }

    @Test
    fun recognizesEveryProductExpressionAcrossFiveEquivalentSequences() {
        val signals = mapOf(
            ExpressionId.SMILE to mapOf("mouthSmileLeft" to 0.9f, "mouthSmileRight" to 0.9f),
            ExpressionId.MOUTH_OPEN to mapOf("jawOpen" to 0.9f),
            ExpressionId.BROW_RAISE to mapOf(
                "browInnerUp" to 0.8f,
                "browOuterUpLeft" to 0.8f,
                "browOuterUpRight" to 0.8f,
            ),
            ExpressionId.MOUTH_PUCKER to mapOf("mouthPucker" to 0.9f),
        )

        signals.forEach { (expected, blendshapes) ->
            repeat(5) {
                val classifier = ExpressionClassifier(config)
                assertEquals(ExpressionId.NONE, classifier.update(0, blendshapes).expression)
                val result = classifier.update(100, blendshapes)
                assertEquals(expected, result.expression)
                assertTrue(result.isNewEvent)
            }
        }
    }

    @Test
    fun leavesBilateralBlinkForTheDedicatedCycleRecognizer() {
        val classifier = ExpressionClassifier(config)
        val bothEyesClosed = mapOf(
            "eyeBlinkLeft" to 0.95f,
            "eyeBlinkRight" to 0.95f,
        )

        assertEquals(ExpressionId.NONE, classifier.update(0, bothEyesClosed).expression)
        val result = classifier.update(100, bothEyesClosed)
        assertEquals(ExpressionId.NONE, result.expression)
        assertFalse(result.isNewEvent)
    }

    @Test
    fun requiresBothEyesForBilateralBlink() {
        val classifier = ExpressionClassifier(config)
        val oneEyeBlink = mapOf(
            "eyeBlinkLeft" to 0.95f,
            "eyeBlinkRight" to 0.02f,
        )

        val decision = classifier.update(0, oneEyeBlink)
        assertEquals(0.02f, decision.scores.getValue(ExpressionId.BOTH_EYES_BLINK), 0.0001f)
        assertEquals(ExpressionId.NONE, classifier.update(100, oneEyeBlink).expression)
    }

    @Test
    fun twoMinuteEquivalentNaturalSequenceStaysRejected() {
        val classifier = ExpressionClassifier(config)
        var falseEvents = 0
        for (timestampMs in 0L..120_000L step 50L) {
            val phase = (timestampMs / 50L).toInt() % 4
            val result = classifier.update(
                timestampMs,
                mapOf(
                    "mouthSmileLeft" to if (phase == 0) 0.20f else 0.08f,
                    "mouthSmileRight" to if (phase == 0) 0.18f else 0.08f,
                    "jawOpen" to if (phase == 1) 0.25f else 0.06f,
                    "browInnerUp" to if (phase == 2) 0.15f else 0.05f,
                    "browOuterUpLeft" to 0.08f,
                    "browOuterUpRight" to 0.08f,
                    "mouthPucker" to 0.05f,
                    "eyeBlinkLeft" to if (phase == 3) 0.9f else 0.02f,
                    "eyeBlinkRight" to if (phase == 3) 0.9f else 0.02f,
                ),
            )
            if (result.isNewEvent) falseEvents++
        }
        assertTrue(falseEvents <= 2)
    }

    @Test
    fun personalBrowBaselineCanRiseAndReturnToNone() {
        val classifier = ExpressionClassifier(
            ExpressionClassifierConfig(
                emaAlpha = 1f,
                holdMs = 50,
                releaseMs = 50,
                cooldownMs = 0,
                useAdaptiveNeutralBaseline = true,
                neutralBaselineWarmupMs = 100,
            ),
        )
        val neutral = mapOf(
            "browInnerUp" to 0.50f,
            "browOuterUpLeft" to 0.50f,
            "browOuterUpRight" to 0.50f,
        )
        val raised = mapOf(
            "browInnerUp" to 0.85f,
            "browOuterUpLeft" to 0.85f,
            "browOuterUpRight" to 0.85f,
        )

        classifier.update(0, neutral)
        classifier.update(100, neutral)
        classifier.update(150, raised)
        assertEquals(ExpressionId.BROW_RAISE, classifier.update(200, raised).expression)
        assertEquals(ExpressionId.BROW_RAISE, classifier.update(225, neutral).expression)
        assertEquals(ExpressionId.NONE, classifier.update(275, neutral).expression)
    }

    @Test
    fun jawOpeningAndSmileSuppressPuckerFalsePositive() {
        val classifier = ExpressionClassifier(config)
        val conflicting = mapOf(
            "mouthPucker" to 0.90f,
            "jawOpen" to 0.90f,
            "mouthSmileLeft" to 0.80f,
            "mouthSmileRight" to 0.80f,
        )

        classifier.update(0, conflicting)
        val result = classifier.update(250, conflicting)
        assertFalse(result.expression == ExpressionId.MOUTH_PUCKER)
    }

    @Test
    fun leavesAsymmetricEyeClosureForTheDedicatedCycleRecognizer() {
        val classifier = ExpressionClassifier(config)
        val leftClosed = mapOf(
            "eyeBlinkLeft" to 0.90f,
            "eyeBlinkRight" to 0.05f,
        )

        assertEquals(ExpressionId.NONE, classifier.update(0, leftClosed).expression)
        assertEquals(ExpressionId.NONE, classifier.update(100, leftClosed).expression)
    }

    @Test
    fun postureChangeRebuildsBrowBaselineAndAllowsARealRaiseAfterSettling() {
        val classifier = ExpressionClassifier(
            ExpressionClassifierConfig(
                emaAlpha = 1f,
                holdMs = 50,
                releaseMs = 50,
                cooldownMs = 0,
                useAdaptiveNeutralBaseline = true,
                neutralBaselineWarmupMs = 0,
            ),
        )
        val turnedNeutral = mapOf(
            "browInnerUp" to 0.35f,
            "browOuterUpLeft" to 0.35f,
            "browOuterUpRight" to 0.35f,
        )
        val raised = mapOf(
            "browInnerUp" to 0.80f,
            "browOuterUpLeft" to 0.80f,
            "browOuterUpRight" to 0.80f,
        )

        assertEquals(ExpressionId.NONE, classifier.update(0, turnedNeutral, postureChanged = true).expression)
        assertEquals(ExpressionId.NONE, classifier.update(50, turnedNeutral).expression)
        assertEquals(ExpressionId.NONE, classifier.update(100, raised).expression)
        assertEquals(ExpressionId.BROW_RAISE, classifier.update(150, raised).expression)
    }
}
