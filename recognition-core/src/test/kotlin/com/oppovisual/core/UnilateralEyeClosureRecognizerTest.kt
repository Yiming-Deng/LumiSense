package com.oppovisual.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UnilateralEyeClosureRecognizerTest {
    @Test
    fun acceptsLighterSingleEyeClosureAtShortHold() {
        val recognizer = UnilateralEyeClosureRecognizer()
        assertNull(recognizer.update(0, 0.31f, 0.20f))
        assertEquals(ExpressionId.RIGHT_WINK, recognizer.update(50, 0.31f, 0.20f))
    }

    @Test
    fun stillRejectsNearBilateralClosureAtRelaxedThreshold() {
        val recognizer = UnilateralEyeClosureRecognizer()
        assertNull(recognizer.update(0, 0.34f, 0.33f))
        assertNull(recognizer.update(80, 0.34f, 0.33f))
    }

    @Test
    fun acceptsModerateSingleEyeClosure() {
        val recognizer = UnilateralEyeClosureRecognizer()
        assertNull(recognizer.update(0, 0.42f, 0.10f))
        assertEquals(ExpressionId.RIGHT_WINK, recognizer.update(61, 0.42f, 0.10f))
    }

    @Test
    fun entersAndHoldsLeftEyeClosedState() {
        val recognizer = UnilateralEyeClosureRecognizer()
        assertNull(recognizer.update(0, 0.05f, 0.05f))
        assertNull(recognizer.update(33, 0.75f, 0.10f))
        assertEquals(ExpressionId.RIGHT_WINK, recognizer.update(99, 0.80f, 0.12f))
        assertTrue(recognizer.isNewState)
        assertEquals(ExpressionId.RIGHT_WINK, recognizer.update(132, 0.78f, 0.11f))
        assertFalse(recognizer.isNewState)
    }

    @Test
    fun bilateralClosureCancelsSingleEyeStateImmediately() {
        val recognizer = UnilateralEyeClosureRecognizer()
        recognizer.update(0, 0.05f, 0.05f)
        recognizer.update(33, 0.75f, 0.10f)
        assertEquals(ExpressionId.RIGHT_WINK, recognizer.update(99, 0.80f, 0.12f))
        assertNull(recognizer.update(132, 0.80f, 0.75f))
    }

    @Test
    fun reopeningReleasesState() {
        val recognizer = UnilateralEyeClosureRecognizer()
        recognizer.update(0, 0.05f, 0.05f)
        recognizer.update(33, 0.75f, 0.10f)
        recognizer.update(99, 0.80f, 0.12f)
        assertEquals(ExpressionId.RIGHT_WINK, recognizer.update(120, 0.10f, 0.10f))
        assertNull(recognizer.update(190, 0.10f, 0.10f))
    }

    @Test
    fun postureChangeCancelsCandidateButStableTurnStillAllowsClosure() {
        val recognizer = UnilateralEyeClosureRecognizer()
        assertNull(recognizer.update(0, 0.75f, 0.08f))
        assertNull(recognizer.update(40, 0.78f, 0.08f, postureChanged = true))
        assertNull(recognizer.update(80, 0.80f, 0.08f))
        assertEquals(ExpressionId.RIGHT_WINK, recognizer.update(145, 0.82f, 0.08f))
    }
}
