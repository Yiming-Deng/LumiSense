package com.oppovisual.app.recognition

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult
import com.oppovisual.core.DirectionalGestureRecognizer
import com.oppovisual.core.GestureId
import com.oppovisual.core.HandTrackAssigner
import com.oppovisual.core.HandTrackObservation
import com.oppovisual.core.MotionSample
import com.oppovisual.core.Point3
import com.oppovisual.core.StaticGestureStabilizer
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.hypot

class MediaPipeGestureRecognizer(
    context: Context,
    delegate: Delegate = Delegate.CPU,
    private val onResult: (FrameRecognition) -> Unit,
    private val onError: (String) -> Unit,
) : FrameRecognizer {
    override val descriptor = RecognizerDescriptor(
        backendId = "mediapipe-v1",
        modelVersion = "mediapipe-gesture-1",
        parameterVersion = "motion-v1",
        supportedGestures = MEDIAPIPE_GESTURES + DIRECTIONAL_GESTURES,
        componentVersions = mapOf(
            "static" to "mediapipe-gesture-1",
            "dynamic" to "directional-rule-v1",
        ),
    )
    private val tracks = mutableMapOf<Int, HandTrack>()
    private val trackAssigner = HandTrackAssigner()
    private val pendingLuma = ConcurrentHashMap<Long, Float>()
    private val recognizer: GestureRecognizer

    init {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(MODEL_ASSET)
            .setDelegate(delegate)
            .build()
        val options = GestureRecognizer.GestureRecognizerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumHands(MAX_HANDS)
            .setMinHandDetectionConfidence(0.5f)
            .setMinHandPresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setResultListener(::handleResult)
            .setErrorListener { onError(it.message ?: "手势识别器发生未知错误") }
            .build()
        recognizer = GestureRecognizer.createFromOptions(context.applicationContext, options)
    }

    override fun recognize(imageProxy: ImageProxy) {
        val timestampMs = SystemClock.uptimeMillis()
        val width = imageProxy.width
        val height = imageProxy.height
        val rotation = imageProxy.imageInfo.rotationDegrees
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val buffer = imageProxy.planes[0].buffer
            buffer.rewind()
            bitmap.copyPixelsFromBuffer(buffer)
        } finally {
            imageProxy.close()
        }

        submitBitmap(bitmap, rotation, mirrorHorizontally = true, timestampMs, recycleSource = true)
    }

    /** Runs the same MediaPipe pipeline with a deterministic bitmap source for offline replay. */
    fun recognize(
        bitmap: Bitmap,
        rotationDegrees: Int = 0,
        mirrorHorizontally: Boolean = false,
    ) {
        submitBitmap(
            bitmap,
            rotationDegrees,
            mirrorHorizontally,
            SystemClock.uptimeMillis(),
            recycleSource = false,
        )
    }

    private fun submitBitmap(
        bitmap: Bitmap,
        rotationDegrees: Int,
        mirrorHorizontally: Boolean,
        timestampMs: Long,
        recycleSource: Boolean,
    ) {
        val width = bitmap.width
        val height = bitmap.height
        val matrix = Matrix().apply {
            postRotate(rotationDegrees.toFloat())
            if (mirrorHorizontally) postScale(-1f, 1f, width.toFloat(), height.toFloat())
        }
        val transformed = if (rotationDegrees == 0 && !mirrorHorizontally) {
            bitmap
        } else {
            Bitmap.createBitmap(bitmap, 0, 0, width, height, matrix, true)
        }
        if (recycleSource && transformed !== bitmap) bitmap.recycle()
        val mpImage = BitmapImageBuilder(transformed).build()
        pendingLuma[timestampMs] = estimateLuma(transformed)
        recognizer.recognizeAsync(mpImage, timestampMs)
    }

    override fun close() {
        recognizer.close()
        tracks.clear()
        trackAssigner.reset()
        pendingLuma.clear()
    }

    private fun handleResult(result: GestureRecognizerResult, input: MPImage) {
        try {
            val now = SystemClock.uptimeMillis()
            val timestamp = result.timestampMs()
            val observations = result.landmarks().mapIndexedNotNull { index, rawLandmarks ->
                val landmarks = rawLandmarks.map { Point3(it.x(), it.y(), it.z()) }
                if (landmarks.size != LANDMARK_COUNT) return@mapIndexedNotNull null
                val topCategory = result.gestures().getOrNull(index)?.maxByOrNull { it.score() }
                val handedness = result.handedness().getOrNull(index)
                    ?.maxByOrNull { it.score() }
                    ?.categoryName()
                HandObservation(
                    landmarks = landmarks,
                    handedness = handedness,
                    staticLabel = topCategory?.categoryName()?.let(STATIC_LABELS::get),
                    staticConfidence = topCategory?.score() ?: 0f,
                )
            }
            val trackAssignments = trackAssigner.assign(
                timestamp,
                observations.map { observation ->
                    val center = palmCenter(observation.landmarks)
                    HandTrackObservation(center.first, center.second, observation.handedness)
                },
            )
            tracks.keys.retainAll(trackAssigner.activeTrackIds())
            val recognizedHands = trackAssignments.map { assignment ->
                val observation = observations[assignment.observationIndex]
                val track = tracks.getOrPut(assignment.trackId) { HandTrack(assignment.trackId) }
                val staticConfidence = if (observation.staticLabel == null) 0f else observation.staticConfidence
                val stable = track.staticStabilizer.update(observation.staticLabel, staticConfidence)
                val motion = motionSample(timestamp, observation.landmarks)?.let(track.directionalRecognizer::update)
                RecognizedHand(
                    trackId = track.id,
                    handedness = observation.handedness,
                    displayGesture = motion?.gesture ?: stable?.label ?: observation.staticLabel,
                    displayConfidence = motion?.confidence ?: stable?.confidence ?: staticConfidence,
                    confirmedEvent = motion?.gesture ?: stable?.takeIf { it.isNewEvent }?.label,
                    eventConfidence = motion?.confidence ?: stable?.confidence ?: 0f,
                    landmarks = observation.landmarks,
                )
            }

            onResult(
                FrameRecognition(
                    timestampMs = timestamp,
                    hands = recognizedHands,
                    inputWidth = input.width,
                    inputHeight = input.height,
                    processingLatencyMs = (now - timestamp).coerceAtLeast(0),
                    averageLuma = pendingLuma.remove(timestamp) ?: 255f,
                ),
            )
            pendingLuma.keys.removeAll { it < timestamp - 2_000 }
        } finally {
            input.close()
        }
    }

    private fun motionSample(timestampMs: Long, landmarks: List<Point3>): MotionSample? {
        if (landmarks.size != LANDMARK_COUNT) return null
        val palmIndices = intArrayOf(0, 5, 9, 13, 17)
        val centerX = palmIndices.map { landmarks[it].x }.average().toFloat()
        val centerY = palmIndices.map { landmarks[it].y }.average().toFloat()
        val indexMcp = landmarks[5]
        val pinkyMcp = landmarks[17]
        val palmWidth = hypot(indexMcp.x - pinkyMcp.x, indexMcp.y - pinkyMcp.y)
        return MotionSample(timestampMs, centerX, centerY, palmWidth)
    }

    private fun palmCenter(landmarks: List<Point3>): Pair<Float, Float> {
        val palmIndices = intArrayOf(0, 5, 9, 13, 17)
        return palmIndices.map { landmarks[it].x }.average().toFloat() to
            palmIndices.map { landmarks[it].y }.average().toFloat()
    }

    private fun estimateLuma(bitmap: Bitmap): Float {
        var total = 0L
        var count = 0
        val stepX = (bitmap.width / 24).coerceAtLeast(1)
        val stepY = (bitmap.height / 24).coerceAtLeast(1)
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val color = bitmap.getPixel(x, y)
                total += ((color shr 16) and 0xff) * 3L + ((color shr 8) and 0xff) * 6L + (color and 0xff)
                count += 10
                x += stepX
            }
            y += stepY
        }
        return if (count == 0) 255f else total.toFloat() / count
    }

    private companion object {
        const val MODEL_ASSET = "gesture_recognizer.task"
        const val LANDMARK_COUNT = 21
        const val MAX_HANDS = 2
        val STATIC_LABELS = mapOf(
            "Closed_Fist" to GestureId.CLOSED_FIST,
            "Open_Palm" to GestureId.OPEN_PALM,
            "Pointing_Up" to GestureId.POINTING_UP,
            "Thumb_Up" to GestureId.THUMB_UP,
            "Thumb_Down" to GestureId.THUMB_DOWN,
            "Victory" to GestureId.VICTORY,
        )
        val MEDIAPIPE_GESTURES = STATIC_LABELS.values.toSet()
        val DIRECTIONAL_GESTURES = setOf(
            GestureId.SWIPE_UP,
            GestureId.SWIPE_DOWN,
            GestureId.SWIPE_LEFT,
            GestureId.SWIPE_RIGHT,
        )
    }

    private data class HandObservation(
        val landmarks: List<Point3>,
        val handedness: String?,
        val staticLabel: GestureId?,
        val staticConfidence: Float,
    )

    private data class HandTrack(
        val id: Int,
        val staticStabilizer: StaticGestureStabilizer = StaticGestureStabilizer(),
        val directionalRecognizer: DirectionalGestureRecognizer = DirectionalGestureRecognizer(),
    )
}
