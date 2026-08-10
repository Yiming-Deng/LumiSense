package com.oppovisual.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DirectionalGestureRecognizerTest {
    private val config = DirectionalGestureConfig(
        emaAlpha = 1f,
        minimumDurationMs = 200,
        maximumDurationMs = 1_000,
        minimumDisplacementInPalmWidths = 1.5f,
        axisDominanceRatio = 2f,
        cooldownMs = 500,
    )

    @Test
    fun recognizesEachDirection() {
        val cases = listOf(
            Triple(0.4f, 0f, GestureId.SWIPE_RIGHT),
            Triple(-0.4f, 0f, GestureId.SWIPE_LEFT),
            Triple(0f, 0.4f, GestureId.SWIPE_DOWN),
            Triple(0f, -0.4f, GestureId.SWIPE_UP),
        )
        cases.forEach { (dx, dy, expected) ->
            val recognizer = DirectionalGestureRecognizer(config)
            assertNull(recognizer.update(sample(0, 0.5f, 0.5f)))
            val result = recognizer.update(sample(300, 0.5f + dx, 0.5f + dy))
            assertEquals(expected, result?.gesture)
            assertTrue((result?.normalizedDisplacement ?: 0f) >= 1.5f)
        }
    }

    @Test
    fun rejectsDiagonalAndShortMovement() {
        val diagonal = DirectionalGestureRecognizer(config)
        diagonal.update(sample(0, 0.5f, 0.5f))
        assertNull(diagonal.update(sample(300, 0.9f, 0.9f)))

        val short = DirectionalGestureRecognizer(config)
        short.update(sample(0, 0.5f, 0.5f))
        assertNull(short.update(sample(300, 0.6f, 0.5f)))
    }

    @Test
    fun cooldownPreventsDuplicateEvents() {
        val recognizer = DirectionalGestureRecognizer(config)
        recognizer.update(sample(0, 0.5f, 0.5f))
        assertEquals(GestureId.SWIPE_RIGHT, recognizer.update(sample(300, 0.9f, 0.5f))?.gesture)
        assertNull(recognizer.update(sample(400, 0.5f, 0.5f)))
        assertNull(recognizer.update(sample(700, 0.9f, 0.5f)))
    }

    private fun sample(timeMs: Long, x: Float, y: Float) = MotionSample(
        timestampMs = timeMs,
        palmCenterX = x,
        palmCenterY = y,
        palmWidth = 0.2f,
    )
}

