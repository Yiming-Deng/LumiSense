package com.oppovisual.app.recognition

internal data class R8FrameTransform(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val rotationDegrees: Int,
    val mirrorHorizontally: Boolean,
    val outputWidth: Int,
    val outputHeight: Int,
    val matrixValues: FloatArray,
) {
    fun mapPoint(x: Float, y: Float): Pair<Float, Float> {
        val mappedX = matrixValues[0] * x + matrixValues[1] * y + matrixValues[2]
        val mappedY = matrixValues[3] * x + matrixValues[4] * y + matrixValues[5]
        return mappedX to mappedY
    }
}

internal object R8FrameTransforms {
    fun cameraFrame(
        sourceWidth: Int,
        sourceHeight: Int,
        rotationDegrees: Int,
        mirrorHorizontally: Boolean,
    ): R8FrameTransform {
        require(sourceWidth > 0 && sourceHeight > 0)
        val rotation = ((rotationDegrees % 360) + 360) % 360
        require(rotation % 90 == 0) { "rotation must be a multiple of 90 degrees" }

        val outputWidth = if (rotation == 90 || rotation == 270) sourceHeight else sourceWidth
        val outputHeight = if (rotation == 90 || rotation == 270) sourceWidth else sourceHeight
        val base = when (rotation) {
            0 -> floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
            90 -> floatArrayOf(0f, -1f, sourceHeight.toFloat(), 1f, 0f, 0f, 0f, 0f, 1f)
            180 -> floatArrayOf(-1f, 0f, sourceWidth.toFloat(), 0f, -1f, sourceHeight.toFloat(), 0f, 0f, 1f)
            else -> floatArrayOf(0f, 1f, 0f, -1f, 0f, sourceWidth.toFloat(), 0f, 0f, 1f)
        }
        if (mirrorHorizontally) {
            base[0] = -base[0]
            base[1] = -base[1]
            base[2] = outputWidth - base[2]
        }
        return R8FrameTransform(
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            rotationDegrees = rotation,
            mirrorHorizontally = mirrorHorizontally,
            outputWidth = outputWidth,
            outputHeight = outputHeight,
            matrixValues = base,
        )
    }
}
