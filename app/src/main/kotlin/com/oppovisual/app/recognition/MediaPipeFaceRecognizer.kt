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
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import com.oppovisual.core.ExpressionClassifier
import com.oppovisual.core.ExpressionDecision
import com.oppovisual.core.ExpressionId
import com.oppovisual.core.BilateralBlinkCycleRecognizer
import com.oppovisual.core.HeadMotionId
import com.oppovisual.core.HeadMotionRecognizer
import com.oppovisual.core.HeadPose
import com.oppovisual.core.HeadPoseEstimator
import com.oppovisual.core.PRODUCT_EXPRESSIONS
import com.oppovisual.core.Point3
import com.oppovisual.core.UnilateralEyeClosureRecognizer
import java.util.concurrent.ConcurrentHashMap

class MediaPipeFaceRecognizer(
    context: Context,
    delegate: Delegate = Delegate.CPU,
    private val onResult: (FaceRecognition) -> Unit,
    private val onError: (String) -> Unit,
) : FrameRecognizer {
    override val descriptor = RecognizerDescriptor(
        domain = RecognitionDomain.FACE,
        backendId = "mediapipe-face-v3",
        modelVersion = "mediapipe-face-landmarker-float16-v1",
        parameterVersion = "face-policy-v10-stable-head-calibration",
        supportedGestures = emptySet(),
        supportedExpressions = PRODUCT_EXPRESSIONS,
        supportedHeadMotions = HeadMotionId.entries.toSet(),
        componentVersions = mapOf(
            "face-landmarker" to "float16-v1",
            "expression-policy" to "v9-posture-compensated",
            "bilateral-blink-cycle" to "ux-v2",
            "unilateral-eye-closure" to "v2-posture-compensated",
            "head-motion-policy" to "v2-stable-adaptive",
        ),
        modelSha256 = mapOf(MODEL_ASSET to MODEL_SHA256),
        maxHands = 0,
        providesLandmarks = true,
    )

    private val expressionClassifier = ExpressionClassifier()
    private val blinkCycleRecognizer = BilateralBlinkCycleRecognizer()
    private val eyeClosureRecognizer = UnilateralEyeClosureRecognizer()
    private val headMotionRecognizer = HeadMotionRecognizer()
    private val pendingLuma = ConcurrentHashMap<Long, Float>()
    private val pendingModelStartedAtNanos = ConcurrentHashMap<Long, Long>()
    private val recognizer: FaceLandmarker
    private var eyeDisplayEvent: ExpressionId? = null
    private var eyeDisplayConfidence = 0f
    private var eyeDisplayUntilMs = 0L
    private var lastPose: HeadPose? = null
    private var lastPoseTimestampMs: Long? = null
    private var lastFaceSeenTimestampMs: Long? = null
    private var headMotionResetForAbsence = false

    init {
        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(
                BaseOptions.builder()
                    .setModelAssetPath(MODEL_ASSET)
                    .setDelegate(delegate)
                    .build(),
            )
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumFaces(1)
            .setOutputFaceBlendshapes(true)
            .setOutputFacialTransformationMatrixes(true)
            .setResultListener(::handleResult)
            .setErrorListener { onError(it.message ?: "面部识别器发生未知错误") }
            .build()
        recognizer = FaceLandmarker.createFromOptions(context.applicationContext, options)
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
        submitBitmap(
            bitmap = bitmap,
            rotationDegrees = rotation,
            mirrorHorizontally = true,
            timestampMs = timestampMs,
            recycleSource = true,
        )
    }

    /** Runs the production Face Landmarker pipeline with a deterministic bitmap source. */
    fun recognize(
        bitmap: Bitmap,
        rotationDegrees: Int = 0,
        mirrorHorizontally: Boolean = false,
    ) {
        submitBitmap(
            bitmap = bitmap,
            rotationDegrees = rotationDegrees,
            mirrorHorizontally = mirrorHorizontally,
            timestampMs = SystemClock.uptimeMillis(),
            recycleSource = false,
        )
    }

    /** Benchmark-only deterministic replay entry; production camera frames use uptime. */
    fun recognizeForReplay(
        bitmap: Bitmap,
        timestampMs: Long,
        rotationDegrees: Int = 0,
        mirrorHorizontally: Boolean = false,
    ) {
        submitBitmap(
            bitmap = bitmap,
            rotationDegrees = rotationDegrees,
            mirrorHorizontally = mirrorHorizontally,
            timestampMs = timestampMs,
            recycleSource = false,
        )
    }

    override fun close() {
        recognizer.close()
        expressionClassifier.reset()
        blinkCycleRecognizer.reset()
        eyeClosureRecognizer.reset()
        headMotionRecognizer.reset()
        pendingLuma.clear()
        pendingModelStartedAtNanos.clear()
        eyeDisplayEvent = null
        eyeDisplayConfidence = 0f
        eyeDisplayUntilMs = 0L
        lastPose = null
        lastPoseTimestampMs = null
        lastFaceSeenTimestampMs = null
        headMotionResetForAbsence = false
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
        val image = BitmapImageBuilder(transformed).build()
        pendingLuma[timestampMs] = estimateLuma(transformed)
        pendingModelStartedAtNanos[timestampMs] = SystemClock.elapsedRealtimeNanos()
        recognizer.detectAsync(image, timestampMs)
    }

    private fun handleResult(result: FaceLandmarkerResult, input: MPImage) {
        try {
            val now = SystemClock.uptimeMillis()
            val timestampMs = result.timestampMs()
            val modelLatencyMs = pendingModelStartedAtNanos.remove(timestampMs)
                ?.let { startedAt ->
                    ((SystemClock.elapsedRealtimeNanos() - startedAt) / 1_000_000L).coerceAtLeast(0L)
                }
                ?: (now - timestampMs).coerceAtLeast(0)
            val landmarks = result.faceLandmarks().firstOrNull()?.map { Point3(it.x(), it.y(), it.z()) }.orEmpty()
            val categories = result.faceBlendshapes().orElse(emptyList()).firstOrNull().orEmpty()
            val blendshapes = categories
                .map { BlendshapeScore(it.categoryName(), it.score()) }
                .sortedByDescending { it.score }
            val facePresent = landmarks.isNotEmpty()
            if (facePresent) {
                lastFaceSeenTimestampMs = timestampMs
                headMotionResetForAbsence = false
            }

            val headMotion = result.facialTransformationMatrixes()
                .orElse(emptyList())
                .firstOrNull()
                ?.takeIf { facePresent }
                ?.let { matrix ->
                    val pose = HeadPoseEstimator.fromTransformationMatrix(matrix, mirrorYaw = true)
                    headMotionRecognizer.update(timestampMs, pose)
                }
            val pose = headMotion?.pose
            val postureChanged = pose?.let { current ->
                val previous = lastPose
                val previousTimestamp = lastPoseTimestampMs
                lastPose = current
                lastPoseTimestampMs = timestampMs
                if (previous == null || previousTimestamp == null) {
                    false
                } else {
                    val dt = (timestampMs - previousTimestamp).coerceAtLeast(1L)
                    val yawVelocity = kotlin.math.abs(current.yawDegrees - previous.yawDegrees) * 1000f / dt
                    val pitchVelocity = kotlin.math.abs(current.pitchDegrees - previous.pitchDegrees) * 1000f / dt
                    val rollVelocity = kotlin.math.abs(current.rollDegrees - previous.rollDegrees) * 1000f / dt
                    yawVelocity >= 55f || pitchVelocity >= 55f || rollVelocity >= 75f
                }
            } ?: run {
                lastPose = null
                lastPoseTimestampMs = null
                false
            }

            val blendshapeMap = blendshapes.associate { it.name to it.score }
            val expression = if (facePresent) {
                val staticDecision = expressionClassifier.update(timestampMs, blendshapeMap, postureChanged)
                val blinkEvent = blinkCycleRecognizer.update(
                    timestampMs = timestampMs,
                    leftBlink = blendshapeMap["eyeBlinkLeft"] ?: 0f,
                    rightBlink = blendshapeMap["eyeBlinkRight"] ?: 0f,
                )
                val eyeClosure = eyeClosureRecognizer.update(
                    timestampMs = timestampMs,
                    leftBlink = blendshapeMap["eyeBlinkLeft"] ?: 0f,
                    rightBlink = blendshapeMap["eyeBlinkRight"] ?: 0f,
                    postureChanged = postureChanged,
                )
                if (blinkEvent) {
                    expressionClassifier.reset()
                    staticDecision.asBilateralBlinkEvent(blinkCycleRecognizer.lastEventConfidence).also {
                        latchEyeDisplay(it.expression, it.confidence, timestampMs)
                    }
                } else if (eyeClosure != null) {
                    staticDecision.asEyeState(
                        eyeClosure,
                        eyeClosureRecognizer.confidence,
                        eyeClosureRecognizer.isNewState,
                    )
                } else if (
                    staticDecision.expression == ExpressionId.NONE &&
                    timestampMs < eyeDisplayUntilMs &&
                    eyeDisplayEvent != null
                ) {
                    staticDecision.asEyeDisplay(requireNotNull(eyeDisplayEvent), eyeDisplayConfidence)
                } else {
                    staticDecision
                }
            } else {
                expressionClassifier.reset()
                blinkCycleRecognizer.update(timestampMs, 0f, 0f, facePresent = false)
                eyeClosureRecognizer.update(timestampMs, 0f, 0f, facePresent = false)
                eyeDisplayEvent = null
                eyeDisplayUntilMs = 0L
                emptyExpressionDecision()
            }
            if (!facePresent && !headMotionResetForAbsence) {
                val lastSeen = lastFaceSeenTimestampMs
                if (lastSeen == null || timestampMs - lastSeen >= HEAD_POSE_RESET_AFTER_MISSING_MS) {
                    headMotionRecognizer.reset()
                    headMotionResetForAbsence = true
                }
            }

            onResult(
                FaceRecognition(
                    timestampMs = timestampMs,
                    facePresent = facePresent,
                    expression = expression,
                    headMotion = headMotion,
                    blendshapes = blendshapes,
                    landmarks = landmarks,
                    inputWidth = input.width,
                    inputHeight = input.height,
                    processingLatencyMs = (now - timestampMs).coerceAtLeast(0),
                    modelLatencyMs = modelLatencyMs,
                    averageLuma = pendingLuma.remove(timestampMs) ?: 255f,
                ),
            )
            pendingLuma.keys.removeAll { it < timestampMs - 2_000 }
            pendingModelStartedAtNanos.keys.removeAll { it < timestampMs - 2_000 }
        } finally {
            input.close()
        }
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

    private fun ExpressionDecision.asBilateralBlinkEvent(confidence: Float): ExpressionDecision {
        return asEyeEvent(ExpressionId.BOTH_EYES_BLINK, confidence)
    }

    private fun ExpressionDecision.asEyeEvent(
        event: ExpressionId,
        confidence: Float,
    ): ExpressionDecision {
        return copy(
            expression = event,
            confidence = confidence,
            isNewEvent = true,
            scores = scores + (event to confidence),
        )
    }

    private fun ExpressionDecision.asEyeDisplay(
        event: ExpressionId,
        confidence: Float,
    ): ExpressionDecision = copy(
        expression = event,
        confidence = confidence,
        isNewEvent = false,
        scores = scores + (event to confidence),
    )

    private fun ExpressionDecision.asEyeState(
        state: ExpressionId,
        confidence: Float,
        isNewState: Boolean,
    ): ExpressionDecision = copy(
        expression = state,
        confidence = confidence,
        isNewEvent = isNewState,
        scores = scores + (state to confidence),
    )

    private fun latchEyeDisplay(event: ExpressionId, confidence: Float, timestampMs: Long) {
        eyeDisplayEvent = event
        eyeDisplayConfidence = confidence
        eyeDisplayUntilMs = timestampMs + EYE_EVENT_DISPLAY_MS
    }

    private companion object {
        const val MODEL_ASSET = "face_landmarker.task"
        const val MODEL_SHA256 = "64184e229b263107bc2b804c6625db1341ff2bb731874b0bcc2fe6544e0bc9ff"
        const val EYE_EVENT_DISPLAY_MS = 500L
        const val HEAD_POSE_RESET_AFTER_MISSING_MS = 1_500L
    }
}
