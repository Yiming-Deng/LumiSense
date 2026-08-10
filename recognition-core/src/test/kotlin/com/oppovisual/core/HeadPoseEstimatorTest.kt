package com.oppovisual.core

import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HeadPoseEstimatorTest {
    @Test
    fun recoversEulerAnglesFromScaledRowMajorMatrix() {
        val matrix = matrix(pitch = 12f, yaw = -24f, roll = 7f, scale = 1.7f)
        val pose = HeadPoseEstimator.fromTransformationMatrix(matrix)

        assertEquals(12f, pose.pitchDegrees, 0.01f)
        assertEquals(-24f, pose.yawDegrees, 0.01f)
        assertEquals(7f, pose.rollDegrees, 0.01f)
    }

    @Test
    fun canCorrectMirroredYawSemantics() {
        val pose = HeadPoseEstimator.fromTransformationMatrix(matrix(yaw = 20f), mirrorYaw = true)
        assertEquals(-20f, pose.yawDegrees, 0.01f)
    }

    @Test
    fun rejectsMalformedMatrix() {
        assertFailsWith<IllegalArgumentException> {
            HeadPoseEstimator.fromTransformationMatrix(FloatArray(9))
        }
    }

    private fun matrix(
        pitch: Float = 0f,
        yaw: Float = 0f,
        roll: Float = 0f,
        scale: Float = 1f,
    ): FloatArray {
        val x = Math.toRadians(pitch.toDouble())
        val y = Math.toRadians(yaw.toDouble())
        val z = Math.toRadians(roll.toDouble())
        val cx = cos(x).toFloat()
        val sx = sin(x).toFloat()
        val cy = cos(y).toFloat()
        val sy = sin(y).toFloat()
        val cz = cos(z).toFloat()
        val sz = sin(z).toFloat()
        return floatArrayOf(
            (cz * cy) * scale,
            (cz * sy * sx - sz * cx) * scale,
            (cz * sy * cx + sz * sx) * scale,
            0f,
            (sz * cy) * scale,
            (sz * sy * sx + cz * cx) * scale,
            (sz * sy * cx - cz * sx) * scale,
            0f,
            (-sy) * scale,
            (cy * sx) * scale,
            (cy * cx) * scale,
            0f,
            0f, 0f, 0f, 1f,
        )
    }
}
