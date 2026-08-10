package com.oppovisual.app.recognition

import com.oppovisual.core.ExpressionDecision
import com.oppovisual.core.ExpressionId
import com.oppovisual.core.HeadMotionUpdate
import com.oppovisual.core.Point3

enum class RecognitionDomain(val displayName: String) {
    GESTURE("手势"),
    FACE("面部"),
}

data class BlendshapeScore(
    val name: String,
    val score: Float,
)

data class FaceRecognition(
    val timestampMs: Long,
    val facePresent: Boolean,
    val expression: ExpressionDecision,
    val headMotion: HeadMotionUpdate?,
    val blendshapes: List<BlendshapeScore>,
    val landmarks: List<Point3>,
    val inputWidth: Int,
    val inputHeight: Int,
    val processingLatencyMs: Long,
    val modelLatencyMs: Long = processingLatencyMs,
    val averageLuma: Float,
)

sealed interface RecognitionFrame {
    val domain: RecognitionDomain
    val timestampMs: Long
    val processingLatencyMs: Long
    val modelLatencyMs: Long
    val averageLuma: Float

    data class Gesture(val recognition: FrameRecognition) : RecognitionFrame {
        override val domain = RecognitionDomain.GESTURE
        override val timestampMs get() = recognition.timestampMs
        override val processingLatencyMs get() = recognition.processingLatencyMs
        override val modelLatencyMs get() = recognition.componentLatency.detectorMs
            .takeIf { it > 0L }
            ?: recognition.processingLatencyMs
        override val averageLuma get() = recognition.averageLuma
    }

    data class Face(val recognition: FaceRecognition) : RecognitionFrame {
        override val domain = RecognitionDomain.FACE
        override val timestampMs get() = recognition.timestampMs
        override val processingLatencyMs get() = recognition.processingLatencyMs
        override val modelLatencyMs get() = recognition.modelLatencyMs
        override val averageLuma get() = recognition.averageLuma
    }
}

internal fun emptyExpressionDecision() = ExpressionDecision(
    expression = ExpressionId.NONE,
    confidence = 0f,
    isNewEvent = false,
    scores = emptyMap(),
)
