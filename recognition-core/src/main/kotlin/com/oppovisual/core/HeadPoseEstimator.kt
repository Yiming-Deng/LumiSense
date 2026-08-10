package com.oppovisual.core

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sqrt

object HeadPoseEstimator {
    /** Parses MediaPipe's row-major 4x4 facial transformation matrix. */
    fun fromTransformationMatrix(matrix: FloatArray, mirrorYaw: Boolean = false): HeadPose {
        require(matrix.size == 16) { "A facial transformation matrix must contain 16 values" }
        require(matrix.all(Float::isFinite)) { "A facial transformation matrix must be finite" }

        val xScale = norm(matrix[0], matrix[4], matrix[8])
        val yScale = norm(matrix[1], matrix[5], matrix[9])
        val zScale = norm(matrix[2], matrix[6], matrix[10])
        require(xScale > EPSILON && yScale > EPSILON && zScale > EPSILON) {
            "A facial transformation matrix must contain a valid rotation"
        }

        val r00 = matrix[0] / xScale
        val r10 = matrix[4] / xScale
        val r20 = matrix[8] / xScale
        val r11 = matrix[5] / yScale
        val r21 = matrix[9] / yScale
        val r22 = matrix[10] / zScale

        val pitch = atan2(r21, r22).toDegrees()
        val rawYaw = asin((-r20).coerceIn(-1f, 1f)).toDegrees()
        val roll = atan2(r10, r00).toDegrees()
        return HeadPose(
            yawDegrees = if (mirrorYaw) -rawYaw else rawYaw,
            pitchDegrees = pitch,
            rollDegrees = roll,
        )
    }

    private fun norm(x: Float, y: Float, z: Float) = sqrt(x * x + y * y + z * z)
    private fun Float.toDegrees() = Math.toDegrees(toDouble()).toFloat()
    private const val EPSILON = 1e-6f
}
