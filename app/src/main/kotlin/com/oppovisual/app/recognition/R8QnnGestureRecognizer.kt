package com.oppovisual.app.recognition

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import com.oppovisual.app.BuildConfig
import com.oppovisual.core.GestureId
import com.oppovisual.r8qnn.R8QnnRuntime
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

class R8QnnGestureRecognizer(
    private val context: Context,
    private val onResult: (FrameRecognition) -> Unit,
    private val onError: (String) -> Unit,
    private val runtimeConfig: R8QnnRuntimeConfig = R8QnnRuntimeConfig(),
) : FrameRecognizer {
    private val qnnDescriptor = RecognizerDescriptor(
        backendId = "r8-qnn-htp-v2",
        modelVersion = runtimeConfig.modelVersion,
        parameterVersion = "product-fsm-ux-fast-ready",
        supportedGestures = GestureId.entries
            .filterNot { it in REMOVED_INTERACTION_EVENTS || it == GestureId.HOLY }
            .toSet(),
        componentVersions = mapOf(
            "detector-pose-classifier" to runtimeConfig.modelVersion,
            "dynamic" to "P2C-A1-v2-ProductFSM",
            "runtime" to "QAIRT-2.47-QNN-online-compose-HTP",
        ),
        modelSha256 = mapOf(runtimeConfig.modelLibrary to runtimeConfig.modelSha256),
        maxHands = 2,
        providesBoundingBoxes = true,
        providesLandmarks = true,
        providesPairPredictions = true,
    )

    override val descriptor: RecognizerDescriptor
        get() = synchronized(this) { fallbackRecognizer?.descriptor ?: qnnDescriptor }

    private val closed = AtomicBoolean(false)
    private var runtime: R8QnnRuntime? = null
    private var fallbackRecognizer: R8LiteRtGestureRecognizer? = null
    private val runtimeFailover = RuntimeFailoverController()
    private val letterboxed = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
    private val letterboxCanvas = Canvas(letterboxed)
    private val letterboxDestination = RectF()
    private val output = FloatArray(R8QnnRuntime.COMPACT_OUTPUT_FLOAT_COUNT)
    private var cameraSource: Bitmap? = null
    private var cameraTransformed: Bitmap? = null
    private val cameraTransformCanvas = Canvas()
    private val cameraTransformMatrix = Matrix()
    private val productPipeline = R8ProductPipeline()

    override fun recognize(imageProxy: ImageProxy) {
        if (closed.get()) {
            imageProxy.close()
            return
        }
        val timestampMs = SystemClock.uptimeMillis()
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        val source = sourceBitmap(imageProxy.width, imageProxy.height)
        try {
            imageProxy.planes[0].buffer.apply { rewind() }.let(source::copyPixelsFromBuffer)
        } finally {
            imageProxy.close()
        }
        val transformed = transformCamera(source, rotationDegrees, mirrorHorizontally = true)
        val cameraPreparationMs = (SystemClock.uptimeMillis() - timestampMs).coerceAtLeast(0)
        runCatching {
            runtimeFailover.run(
                primary = { recognize(transformed, timestampMs) },
                fallback = { getFallbackRecognizer().recognize(transformed, timestampMs) },
                onSwitch = {
                    Log.w(
                        TAG,
                        "QNN unavailable; switching to LiteRT fallback: " +
                            (it.message ?: it.javaClass.simpleName),
                    )
                    closeQnnRuntime()
                },
            )
        }.onFailure {
            onError("手势识别初始化失败：${it.message ?: it.javaClass.simpleName}")
        }
        if (BuildConfig.R8_DIAGNOSTIC_TIMING_LOG) {
            Log.i(
                TIMING_LOG_TAG,
                "camera_prepare_ms=$cameraPreparationMs pipeline_total_ms=" +
                    (SystemClock.uptimeMillis() - timestampMs).coerceAtLeast(0),
            )
        }
    }

    internal fun recognize(bitmap: Bitmap, timestampMs: Long = SystemClock.uptimeMillis()) {
        check(!closed.get()) { "recognizer is closed" }
        val started = SystemClock.elapsedRealtimeNanos()
        val transform = R8OutputParser.letterbox(bitmap.width, bitmap.height, INPUT_SIZE)
        prepareInput(bitmap, transform)
        val preprocessFinished = SystemClock.elapsedRealtimeNanos()
        val activeRuntime = getRuntime()
        activeRuntime.runCompact(letterboxed, output)
        val inferenceFinished = SystemClock.elapsedRealtimeNanos()
        val detections = R8OutputParser.parse(output, transform)
        val postprocessingFinished = SystemClock.elapsedRealtimeNanos()
        val processed = productPipeline.update(timestampMs, detections)
        val finished = SystemClock.elapsedRealtimeNanos()

        val preprocessingMs = nanosToMillis(preprocessFinished - started)
        val detectorMs = nanosToMillis(inferenceFinished - preprocessFinished)
        val postprocessingMs = nanosToMillis(postprocessingFinished - inferenceFinished)
        val policyMs = nanosToMillis(finished - postprocessingFinished)
        val totalMs = nanosToMillis(finished - started)
        if (BuildConfig.R8_DIAGNOSTIC_TIMING_LOG) {
            Log.i(
                TIMING_LOG_TAG,
                "model_total_ms=$totalMs preprocess_ms=$preprocessingMs " +
                    "detector_ms=$detectorMs htp_execute_ms=" +
                    "${activeRuntime.lastInferenceNanos / 1_000_000.0} " +
                    "postprocess_ms=$postprocessingMs policy_ms=$policyMs",
            )
        }
        onResult(
            FrameRecognition(
                timestampMs,
                processed.hands,
                bitmap.width,
                bitmap.height,
                totalMs,
                estimateLuma(bitmap),
                processed.pairPrediction,
                processed.events,
                ComponentLatency(
                    preprocessingMs = preprocessingMs,
                    detectorMs = detectorMs,
                    postprocessingMs = postprocessingMs,
                    trackingAndPolicyMs = policyMs,
                    totalMs = totalMs,
                ),
                processed.status,
                processed.activeScaleParameter,
                processed.scaleStatus,
            ),
        )
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        productPipeline.reset()
        synchronized(this) {
            runtime?.close()
            runtime = null
            fallbackRecognizer?.close()
            fallbackRecognizer = null
        }
        cameraTransformCanvas.setBitmap(null)
        cameraTransformed?.recycle()
        cameraTransformed = null
        cameraSource?.recycle()
        cameraSource = null
        letterboxed.recycle()
    }

    @Synchronized
    private fun getRuntime(): R8QnnRuntime {
        check(!closed.get()) { "recognizer is closed" }
        runtime?.let { return it }
        return R8QnnRuntime(
            context,
            runtimeConfig.modelLibrary,
            runtimeConfig.performanceMode,
        ).also { runtime = it }
    }

    @Synchronized
    private fun getFallbackRecognizer(): R8LiteRtGestureRecognizer {
        check(!closed.get()) { "recognizer is closed" }
        fallbackRecognizer?.let { return it }
        return R8LiteRtGestureRecognizer(
            context = context,
            onResult = onResult,
            onError = onError,
            runtimeConfig = R8LiteRtFrameRecognizerFactory.productionRuntimeConfig,
        ).also { fallbackRecognizer = it }
    }

    @Synchronized
    private fun closeQnnRuntime() {
        runtime?.close()
        runtime = null
    }

    private fun prepareInput(bitmap: Bitmap, transform: R8Letterbox) {
        val resizedWidth = (bitmap.width * transform.scale).roundToInt().coerceAtLeast(1)
        val resizedHeight = (bitmap.height * transform.scale).roundToInt().coerceAtLeast(1)
        letterboxCanvas.drawColor(Color.rgb(114, 114, 114))
        letterboxDestination.set(
            transform.padX,
            transform.padY,
            transform.padX + resizedWidth,
            transform.padY + resizedHeight,
        )
        letterboxCanvas.drawBitmap(bitmap, null, letterboxDestination, PAINT)
    }

    private fun sourceBitmap(width: Int, height: Int): Bitmap {
        cameraSource?.takeIf { it.width == width && it.height == height && !it.isRecycled }?.let {
            return it
        }
        cameraSource?.recycle()
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            cameraSource = it
        }
    }

    private fun transformCamera(
        bitmap: Bitmap,
        rotationDegrees: Int,
        mirrorHorizontally: Boolean,
    ): Bitmap {
        if (rotationDegrees == 0 && !mirrorHorizontally) return bitmap
        val frameTransform = R8FrameTransforms.cameraFrame(
            bitmap.width,
            bitmap.height,
            rotationDegrees,
            mirrorHorizontally,
        )
        val transformed = cameraTransformed
            ?.takeIf {
                it.width == frameTransform.outputWidth &&
                    it.height == frameTransform.outputHeight &&
                    !it.isRecycled
            }
            ?: run {
                cameraTransformed?.recycle()
                Bitmap.createBitmap(
                    frameTransform.outputWidth,
                    frameTransform.outputHeight,
                    Bitmap.Config.ARGB_8888,
                ).also { cameraTransformed = it }
            }
        cameraTransformCanvas.setBitmap(transformed)
        cameraTransformCanvas.drawColor(Color.BLACK)
        cameraTransformMatrix.setValues(frameTransform.matrixValues)
        cameraTransformCanvas.drawBitmap(bitmap, cameraTransformMatrix, PAINT)
        return transformed
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
                total += Color.red(color) * 3L + Color.green(color) * 6L + Color.blue(color)
                count += 10
                x += stepX
            }
            y += stepY
        }
        return if (count == 0) 255f else total.toFloat() / count
    }

    private fun nanosToMillis(nanos: Long): Long =
        ((nanos.coerceAtLeast(0) + 999_999L) / 1_000_000L)

    private companion object {
        const val INPUT_SIZE = 640
        const val TAG = "GestureRuntime"
        const val TIMING_LOG_TAG = "OppoVisualR8QnnTiming"
        val PAINT = Paint(Paint.FILTER_BITMAP_FLAG)
        val REMOVED_INTERACTION_EVENTS = setOf(
            GestureId.CLICK_ONE,
            GestureId.CLICK_TWO,
            GestureId.OPEN_TWICE,
            GestureId.DOUBLE_CLICK_ONE,
            GestureId.DOUBLE_CLICK_TWO,
        )
    }
}

data class R8QnnRuntimeConfig(
    val modelLibrary: String = "libgesture_pose_final_qat_w8a16.so",
    val modelSha256: String = "97724208662d099b1ca26c881d4d154e43222788b62b7ec5c54794c12b4886b4",
    val modelVersion: String = "gesture-pose-final-fullqat1024-w8a16-qnn",
    val performanceMode: String = BuildConfig.R8_NPU_PERFORMANCE_MODE,
) {
    init {
        require(modelLibrary.matches(Regex("lib[A-Za-z0-9_.-]+\\.so")))
        require(modelSha256.matches(Regex("[0-9a-f]{64}")))
        require(performanceMode in setOf("default", "burst", "sustained", "powersave"))
    }
}

object R8QnnFrameRecognizerFactory : FrameRecognizerFactory {
    override fun create(
        context: Context,
        onResult: (RecognitionFrame) -> Unit,
        onError: (String) -> Unit,
    ): FrameRecognizer = R8QnnGestureRecognizer(
        context,
        onResult = { onResult(RecognitionFrame.Gesture(it)) },
        onError = onError,
    )
}

object ProductionGestureRecognizerFactory : FrameRecognizerFactory {
    override fun create(
        context: Context,
        onResult: (RecognitionFrame) -> Unit,
        onError: (String) -> Unit,
    ): FrameRecognizer = when (BuildConfig.GESTURE_RUNTIME) {
        "litert" -> R8LiteRtFrameRecognizerFactory.create(context, onResult, onError)
        "qnn_htp" -> if (QnnDeviceCompatibility.isQnnCandidate()) {
            R8QnnFrameRecognizerFactory.create(context, onResult, onError)
        } else {
            Log.i(
                "GestureRuntime",
                "Device is not a Qualcomm QNN candidate; trying portable LiteRT NPU",
            )
            createPortableNpuFirst(context, onResult, onError)
        }
        else -> error("Unsupported gesture runtime: ${BuildConfig.GESTURE_RUNTIME}")
    }

    private fun createPortableNpuFirst(
        context: Context,
        onResult: (RecognitionFrame) -> Unit,
        onError: (String) -> Unit,
    ): FrameRecognizer {
        val config = R8LiteRtFrameRecognizerFactory.productionRuntimeConfig
        val result = { frame: FrameRecognition -> onResult(RecognitionFrame.Gesture(frame)) }

        // A portable NPU provider is optional on Android. Try each backend with
        // an explicit configuration so a missing provider cannot cause the same
        // NPU initialization failure to be retried indefinitely.
        try {
            return R8LiteRtGestureRecognizer(
                context = context,
                onResult = result,
                onError = onError,
                runtimeConfig = config.copy(accelerator = "npu_gpu_cpu"),
            )
        } catch (failure: Exception) {
            Log.w(TAG, "LiteRT NPU unavailable; trying GPU/CPU", failure)
        } catch (failure: LinkageError) {
            Log.w(TAG, "LiteRT NPU libraries unavailable; trying GPU/CPU", failure)
        }

        try {
            return R8LiteRtGestureRecognizer(
                context = context,
                onResult = result,
                onError = onError,
                runtimeConfig = config.copy(accelerator = "gpu_cpu"),
            )
        } catch (failure: Exception) {
            Log.w(TAG, "LiteRT GPU unavailable; using CPU", failure)
        } catch (failure: LinkageError) {
            Log.w(TAG, "LiteRT GPU libraries unavailable; using CPU", failure)
        }

        return R8LiteRtGestureRecognizer(
            context = context,
            onResult = result,
            onError = onError,
            runtimeConfig = config.copy(accelerator = "cpu"),
        )
    }

    private const val TAG = "GestureRuntime"
}

internal object QnnDeviceCompatibility {
    fun isQnnCandidate(): Boolean = isQnnCandidate(
        sdkInt = Build.VERSION.SDK_INT,
        socManufacturer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MANUFACTURER
        } else {
            Build.MANUFACTURER
        },
        socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else null,
    )

    fun isQnnCandidate(sdkInt: Int, socManufacturer: String?, socModel: String?): Boolean {
        if (sdkInt < Build.VERSION_CODES.S) return false
        val vendor = socManufacturer?.trim()?.uppercase().orEmpty()
        val model = socModel?.trim()?.uppercase().orEmpty()
        return vendor.contains("QUALCOMM") || vendor == "QTI" || model.startsWith("SM")
    }
}
