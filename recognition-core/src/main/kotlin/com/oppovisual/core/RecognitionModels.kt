package com.oppovisual.core

data class Point3(
    val x: Float,
    val y: Float,
    val z: Float = 0f,
)

data class PerceptionResult(
    val label: GestureId,
    val confidence: Float,
    val timestampMs: Long,
    val processingLatencyMs: Long,
    val landmarks: List<Point3> = emptyList(),
    val modelVersion: String,
    val parameterVersion: String,
)

data class RecognitionBatch(
    val timestampMs: Long,
    val results: List<PerceptionResult>,
    val handPresent: Boolean,
)

data class MotionSample(
    val timestampMs: Long,
    val palmCenterX: Float,
    val palmCenterY: Float,
    val palmWidth: Float,
)

data class MotionDecision(
    val gesture: GestureId,
    val confidence: Float,
    val durationMs: Long,
    val normalizedDisplacement: Float,
)

