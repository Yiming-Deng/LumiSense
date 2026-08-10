package com.oppovisual.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HeadMotionRecognizerTest {
    private val config = HeadMotionConfig(
        calibrationSamples = 2,
        turnHoldMs = 300,
        eventCooldownMs = 0,
        neutralAdaptation = 0f,
    )

    @Test
    fun calibratesNeutralPoseBeforeReportingDirection() {
        val recognizer = HeadMotionRecognizer(config)
        assertFalse(recognizer.update(0, pose(yaw = 5f)).calibrated)
        val centered = recognizer.update(20, pose(yaw = 5f))
        assertTrue(centered.calibrated)
        assertEquals(HeadDirection.CENTER, centered.direction)
        assertEquals(0f, centered.pose.yawDegrees, 0.001f)
    }

    @Test
    fun `calibration ignores turned and moving startup poses`() {
        val recognizer = HeadMotionRecognizer(
            config.copy(
                calibrationSamples = 3,
                calibrationYawLimitDegrees = 18f,
                calibrationMaxSpanDegrees = 4f,
            ),
        )

        val turned = recognizer.update(0, pose(yaw = 28f))
        assertFalse(turned.calibrated)
        assertEquals(28f, turned.pose.yawDegrees, 0.001f)
        assertFalse(recognizer.update(20, pose(yaw = 12f)).calibrated)
        assertFalse(recognizer.update(40, pose(yaw = 6f)).calibrated)

        assertFalse(recognizer.update(60, pose(yaw = 1f)).calibrated)
        assertFalse(recognizer.update(80, pose(yaw = 0f)).calibrated)
        val calibrated = recognizer.update(100, pose(yaw = 0.5f))
        assertTrue(calibrated.calibrated)
        assertEquals(0f, calibrated.pose.yawDegrees, 0.51f)
    }

    @Test
    fun `neutral adaptation does not absorb a deliberate partial turn`() {
        val recognizer = HeadMotionRecognizer(config.copy(neutralAdaptation = 0.5f))
        recognizer.update(0, pose())
        recognizer.update(20, pose())

        repeat(20) { index -> recognizer.update(40L + index * 20L, pose(yaw = 10f)) }
        val returned = recognizer.update(500, pose())

        assertEquals(0f, returned.pose.yawDegrees, 0.001f)
    }

    @Test
    fun emitsTurnOnlyOnceAfterHold() {
        val recognizer = calibrated()
        assertEquals(HeadDirection.LEFT, recognizer.update(100, pose(yaw = -20f)).direction)
        assertNull(recognizer.update(300, pose(yaw = -20f)).event)
        assertEquals(HeadMotionId.TURN_LEFT, recognizer.update(400, pose(yaw = -20f)).event)
        assertNull(recognizer.update(800, pose(yaw = -20f)).event)
    }

    @Test
    fun returnsToCenterAfterTurningAndClearsDirection() {
        val recognizer = calibrated()
        recognizer.update(100, pose(yaw = -20f))
        assertEquals(HeadDirection.LEFT, recognizer.update(400, pose(yaw = -20f)).direction)
        assertEquals(HeadDirection.CENTER, recognizer.update(500, pose(yaw = 0f)).direction)
        assertEquals(HeadDirection.RIGHT, recognizer.update(600, pose(yaw = 20f)).direction)
        assertEquals(HeadDirection.CENTER, recognizer.update(700, pose(yaw = 0f)).direction)
    }

    @Test
    fun shakeTakesPriorityOverTurnAndRequiresReturnToCenter() {
        val recognizer = calibrated()
        assertNull(recognizer.update(100, pose(yaw = -20f)).event)
        assertNull(recognizer.update(250, pose(yaw = 20f)).event)
        val returned = recognizer.update(350, pose(yaw = 0f))
        assertEquals(HeadMotionId.SHAKE, returned.event)
    }

    @Test
    fun nodRequiresExcursionAndReturn() {
        val recognizer = calibrated()
        assertNull(recognizer.update(100, pose(pitch = 16f)).event)
        assertNull(recognizer.update(200, pose(pitch = 10f)).event)
        assertEquals(HeadMotionId.NOD, recognizer.update(300, pose(pitch = 2f)).event)
    }

    @Test
    fun recognizesEveryHeadMotionAcrossFiveEquivalentSequences() {
        repeat(5) {
            assertEquals(
                HeadMotionId.TURN_LEFT,
                calibrated().run {
                    update(100, pose(yaw = -20f))
                    update(400, pose(yaw = -20f)).event
                },
            )
            assertEquals(
                HeadMotionId.TURN_RIGHT,
                calibrated().run {
                    update(100, pose(yaw = 20f))
                    update(400, pose(yaw = 20f)).event
                },
            )
            assertEquals(
                HeadMotionId.NOD,
                calibrated().run {
                    update(100, pose(pitch = 16f))
                    update(300, pose()).event
                },
            )
            assertEquals(
                HeadMotionId.SHAKE,
                calibrated().run {
                    update(100, pose(yaw = -20f))
                    update(250, pose(yaw = 20f))
                    update(350, pose()).event
                },
            )
        }
    }

    @Test
    fun twoMinuteEquivalentNaturalMotionHasAtMostTwoFalseEvents() {
        val recognizer = calibrated()
        var falseEvents = 0
        for (timestampMs in 100L..120_100L step 50L) {
            val phase = ((timestampMs - 100L) / 50L).toInt() % 4
            val yaw = listOf(0f, 4f, -3f, 2f)[phase]
            val pitch = listOf(0f, -2f, 3f, -1f)[phase]
            if (recognizer.update(timestampMs, pose(yaw, pitch)).event != null) falseEvents++
        }
        assertTrue(falseEvents <= 2)
    }

    private fun calibrated() = HeadMotionRecognizer(config).also {
        it.update(0, pose())
        it.update(20, pose())
    }

    private fun pose(yaw: Float = 0f, pitch: Float = 0f) = HeadPose(yaw, pitch, 0f)
}
