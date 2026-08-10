package com.oppovisual.app.ui.face

import com.oppovisual.core.HeadPose
import com.oppovisual.core.Point3
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

data class FaceHeadgearAnchor(
    val centerX: Float = 0.5f,
    val centerY: Float = 0.5f,
    val faceWidth: Float = 0f,
    val faceHeight: Float = 0f,
    val eyeCenterX: Float = centerX,
    val eyeCenterY: Float = centerY,
    val eyeVectorX: Float = 0f,
    val eyeVectorY: Float = 0f,
    val rotationDegrees: Float = 0f,
    val alpha: Float = 0f,
) {
    val visible: Boolean get() = faceWidth > 0f && faceHeight > 0f && alpha > 0f
}

data class FaceHeadgearLayout(
    val centerX: Float,
    val centerY: Float,
    val faceWidthPx: Float,
    val faceHeightPx: Float,
    val eyeCenterX: Float,
    val eyeCenterY: Float,
    val eyeDistancePx: Float,
    val eyeRotationDegrees: Float = 0f,
    val mouthCenterX: Float = centerX,
    val mouthCenterY: Float = centerY + faceHeightPx * 0.22f,
)

fun mapHeadgearAnchorToViewport(
    anchor: FaceHeadgearAnchor,
    inputWidth: Int,
    inputHeight: Int,
    viewportWidth: Float,
    viewportHeight: Float,
): FaceHeadgearLayout {
    require(inputWidth > 0 && inputHeight > 0)
    val fit = minOf(viewportWidth / inputWidth, viewportHeight / inputHeight)
    val offsetX = (viewportWidth - inputWidth * fit) / 2f
    val offsetY = (viewportHeight - inputHeight * fit) / 2f
    return FaceHeadgearLayout(
        centerX = offsetX + anchor.centerX * inputWidth * fit,
        centerY = offsetY + anchor.centerY * inputHeight * fit,
        faceWidthPx = anchor.faceWidth * inputWidth * fit,
        faceHeightPx = anchor.faceHeight * inputHeight * fit,
        eyeCenterX = offsetX + anchor.eyeCenterX * inputWidth * fit,
        eyeCenterY = offsetY + anchor.eyeCenterY * inputHeight * fit,
        eyeDistancePx = hypot(
            anchor.eyeVectorX * inputWidth,
            anchor.eyeVectorY * inputHeight,
        ) * fit,
        eyeRotationDegrees = anchor.rotationDegrees,
    )
}

class FaceHeadgearAnchorEstimator(private val smoothing: Float = 0.50f) {
    private var anchor: FaceHeadgearAnchor? = null
    private var lastSeenMs = Long.MIN_VALUE
    private var frontalOffsetX: Float? = null
    private var frontalOffsetY: Float? = null

    fun update(
        landmarks: List<Point3>,
        headPose: HeadPose?,
        facePresent: Boolean,
        timestampMs: Long,
    ): FaceHeadgearAnchor {
        val raw = if (facePresent) calculateRaw(landmarks, headPose) else null
        if (raw != null) {
            lastSeenMs = timestampMs
            val previous = anchor
            anchor = if (previous == null || !previous.visible) raw else previous.lerp(raw, smoothing)
            return requireNotNull(anchor)
        }

        val previous = anchor ?: return FaceHeadgearAnchor()
        val missingMs = (timestampMs - lastSeenMs).coerceAtLeast(0L)
        val alpha = when {
            missingMs <= FADE_START_MS -> previous.alpha
            missingMs >= HIDE_AFTER_MS -> 0f
            else -> previous.alpha * (1f - (missingMs - FADE_START_MS).toFloat() / (HIDE_AFTER_MS - FADE_START_MS))
        }
        anchor = previous.copy(alpha = alpha.coerceIn(0f, 1f))
        return requireNotNull(anchor)
    }

    fun reset() {
        anchor = null
        lastSeenMs = Long.MIN_VALUE
        frontalOffsetX = null
        frontalOffsetY = null
    }

    private fun calculateRaw(landmarks: List<Point3>, headPose: HeadPose?): FaceHeadgearAnchor? {
        if (REQUIRED_INDICES.any { it !in landmarks.indices }) return null
        val fallbackLeftEye = landmarks[33]
        val fallbackRightEye = landmarks[263]
        val irisLeftEye = landmarks.getOrNull(468)
        val irisRightEye = landmarks.getOrNull(473)
        val useIris = irisLeftEye != null && irisRightEye != null &&
            hypot(irisRightEye.x - irisLeftEye.x, irisRightEye.y - irisLeftEye.y) > 0.05f
        // Keep the calibrated frontal path on iris centers. Eye corners remain a
        // fallback when iris landmarks are unavailable or degenerate.
        val leftEye = if (useIris) requireNotNull(irisLeftEye) else fallbackLeftEye
        val rightEye = if (useIris) requireNotNull(irisRightEye) else fallbackRightEye
        val oval = FACE_OVAL_INDICES.map(landmarks::get)
        val left = oval.minOf(Point3::x)
        val right = oval.maxOf(Point3::x)
        val top = oval.minOf(Point3::y)
        val bottom = oval.maxOf(Point3::y)
        val faceWidth = right - left
        val faceHeight = bottom - top
        if (faceWidth <= 0f || faceHeight <= 0f) return null

        val rigidCenterX = STABLE_ANCHOR_PAIRS
            .map { (leftIndex, rightIndex) -> (landmarks[leftIndex].x + landmarks[rightIndex].x) / 2f }
            .average()
            .toFloat()
        val rigidCenterY = STABLE_ANCHOR_PAIRS
            .map { (leftIndex, rightIndex) -> (landmarks[leftIndex].y + landmarks[rightIndex].y) / 2f }
            .average()
            .toFloat()
        val observedOffsetX = (left + right) / 2f - rigidCenterX
        val observedOffsetY = (top + bottom) / 2f - rigidCenterY
        val yawDegrees = headPose?.yawDegrees ?: 0f
        val pitchDegrees = headPose?.pitchDegrees ?: 0f
        if (frontalOffsetX == null || frontalOffsetY == null) {
            frontalOffsetX = observedOffsetX
            frontalOffsetY = observedOffsetY
        } else if (abs(yawDegrees) <= FRONTAL_YAW_LIMIT && abs(pitchDegrees) <= FRONTAL_PITCH_LIMIT) {
            frontalOffsetX = requireNotNull(frontalOffsetX) +
                (observedOffsetX - requireNotNull(frontalOffsetX)) * FRONTAL_OFFSET_SMOOTHING
            frontalOffsetY = requireNotNull(frontalOffsetY) +
                (observedOffsetY - requireNotNull(frontalOffsetY)) * FRONTAL_OFFSET_SMOOTHING
        }

        val yaw = abs(yawDegrees)
        val alpha = when {
            yaw <= YAW_FADE_START -> 1f
            yaw >= YAW_HIDE -> 0f
            else -> 1f - (yaw - YAW_FADE_START) / (YAW_HIDE - YAW_FADE_START)
        }
        return FaceHeadgearAnchor(
            centerX = rigidCenterX + requireNotNull(frontalOffsetX),
            centerY = rigidCenterY + requireNotNull(frontalOffsetY),
            faceWidth = faceWidth,
            faceHeight = faceHeight,
            eyeCenterX = (leftEye.x + rightEye.x) * 0.5f,
            eyeCenterY = (leftEye.y + rightEye.y) * 0.5f,
            eyeVectorX = rightEye.x - leftEye.x,
            eyeVectorY = rightEye.y - leftEye.y,
            rotationDegrees = Math.toDegrees(
                atan2((rightEye.y - leftEye.y).toDouble(), (rightEye.x - leftEye.x).toDouble()),
            ).toFloat(),
            alpha = alpha.coerceIn(0f, 1f),
        )
    }

    private fun FaceHeadgearAnchor.lerp(other: FaceHeadgearAnchor, t: Float): FaceHeadgearAnchor {
        val movement = hypot(other.centerX - centerX, other.centerY - centerY)
        val referenceSize = maxOf(faceWidth, faceHeight, 0.001f)
        val positionT = if (movement > referenceSize * 0.06f) 0.72f else t
        val sizeT = minOf(t, 0.46f)
        val rotationT = minOf(t, 0.38f)
        return FaceHeadgearAnchor(
            centerX = centerX + (other.centerX - centerX) * positionT,
            centerY = centerY + (other.centerY - centerY) * positionT,
            faceWidth = faceWidth + (other.faceWidth - faceWidth) * sizeT,
            faceHeight = faceHeight + (other.faceHeight - faceHeight) * sizeT,
            eyeCenterX = eyeCenterX + (other.eyeCenterX - eyeCenterX) * positionT,
            eyeCenterY = eyeCenterY + (other.eyeCenterY - eyeCenterY) * positionT,
            eyeVectorX = eyeVectorX + (other.eyeVectorX - eyeVectorX) * sizeT,
            eyeVectorY = eyeVectorY + (other.eyeVectorY - eyeVectorY) * sizeT,
            rotationDegrees = rotationDegrees + (other.rotationDegrees - rotationDegrees) * rotationT,
            alpha = other.alpha,
        )
    }

    private companion object {
        val FACE_OVAL_INDICES = intArrayOf(
            10, 338, 297, 332, 284, 251, 389, 356, 454,
            323, 361, 288, 397, 365, 379, 378, 400, 377,
            152, 148, 176, 149, 150, 136, 172, 58, 132,
            93, 234, 127, 162, 21, 54, 103, 67, 109,
        )
        val STABLE_ANCHOR_PAIRS = arrayOf(
            127 to 356,
            234 to 454,
            93 to 323,
        )
        val REQUIRED_INDICES = FACE_OVAL_INDICES + intArrayOf(33, 133, 362, 263) +
            STABLE_ANCHOR_PAIRS.flatMap { listOf(it.first, it.second) }
        const val FRONTAL_YAW_LIMIT = 10f
        const val FRONTAL_PITCH_LIMIT = 10f
        const val FRONTAL_OFFSET_SMOOTHING = 0.08f
        const val YAW_FADE_START = 35f
        const val YAW_HIDE = 50f
        const val FADE_START_MS = 150L
        const val HIDE_AFTER_MS = 350L
    }
}
