package com.oppovisual.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BilateralBlinkCycleRecognizerTest {
    private fun recognizer() = BilateralBlinkCycleRecognizer(
        BilateralBlinkCycleConfig(
            emaAlpha = 1f,
            openThreshold = 0.2f,
            closeThreshold = 0.2f,
            maximumClosedMs = 400,
            reopenMs = 33,
            cooldownMs = 250,
        ),
    )

    @Test
    fun emitsOnlyAfterBilateralCloseAndReopen() {
        val recognizer = recognizer()
        assertFalse(recognizer.update(0, 0.05f, 0.05f))
        assertFalse(recognizer.update(33, 0.9f, 0.9f))
        assertFalse(recognizer.update(100, 0.05f, 0.05f))
        assertTrue(recognizer.update(133, 0.05f, 0.05f))
    }

    @Test
    fun rejectsHeldClosureAndSingleEyeWink() {
        val held = recognizer()
        assertFalse(held.update(0, 0.05f, 0.05f))
        assertFalse(held.update(33, 0.9f, 0.9f))
        assertFalse(held.update(500, 0.9f, 0.9f))
        assertFalse(held.update(533, 0.05f, 0.05f))

        val wink = recognizer()
        assertFalse(wink.update(0, 0.05f, 0.05f))
        assertFalse(wink.update(33, 0.9f, 0.05f))
        assertFalse(wink.update(100, 0.05f, 0.05f))
        assertFalse(wink.update(133, 0.05f, 0.05f))
    }

    @Test
    fun faceLossCancelsAnIncompleteCycleAndCooldownPreventsDuplicates() {
        val recognizer = recognizer()
        recognizer.update(0, 0.05f, 0.05f)
        recognizer.update(33, 0.9f, 0.9f)
        assertFalse(recognizer.update(66, 0f, 0f, facePresent = false))
        assertFalse(recognizer.update(100, 0.05f, 0.05f))
        assertFalse(recognizer.update(133, 0.05f, 0.05f))

        recognizer.update(200, 0.9f, 0.9f)
        recognizer.update(233, 0.05f, 0.05f)
        assertTrue(recognizer.update(266, 0.05f, 0.05f))
        assertFalse(recognizer.update(300, 0.9f, 0.9f))
        assertFalse(recognizer.update(333, 0.05f, 0.05f))
        assertFalse(recognizer.update(366, 0.05f, 0.05f))
    }
}
