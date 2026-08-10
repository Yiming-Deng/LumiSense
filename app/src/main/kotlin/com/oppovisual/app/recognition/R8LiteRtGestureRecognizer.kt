package com.oppovisual.app.recognition

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import com.oppovisual.app.BuildConfig
import com.oppovisual.r8litert.R8LiteRtRuntime
import com.oppovisual.r8litert.R8BitmapPreprocessor
import com.oppovisual.core.GestureId
import com.oppovisual.core.HandTrackAssigner
import com.oppovisual.core.HandTrackObservation
import com.oppovisual.core.Point3
import com.oppovisual.core.ProductInteractionDecoder
import java.util.concurrent.atomic.AtomicBoolean

class R8LiteRtGestureRecognizer(
    context: Context,
    private val onResult: (FrameRecognition) -> Unit,
    private val onError: (String) -> Unit,
    private val runtimeConfig: R8RuntimeConfig = R8RuntimeConfig(),
) : FrameRecognizer {
    override val descriptor = RecognizerDescriptor(
        backendId = "r8-litert-v2",
        modelVersion = runtimeConfig.modelVersion,
        parameterVersion = "product-fsm-ux-fast-ready",
        supportedGestures = GestureId.entries
            .filterNot { it in REMOVED_INTERACTION_EVENTS || it == GestureId.HOLY }
            .toSet(),
        componentVersions = mapOf(
            "detector-pose-classifier" to runtimeConfig.modelVersion,
            "dynamic" to "P2C-A1-v2-ProductFSM",
            "runtime" to when (runtimeConfig.accelerator) {
                "cpu" -> "LiteRT-2.1.6-CPU-${runtimeConfig.cpuThreads}t"
                "gpu" -> "LiteRT-2.1.6-GPU"
                "gpu_cpu" -> "LiteRT-2.1.6-GPU+CPU-${runtimeConfig.cpuThreads}t"
                "npu" -> "LiteRT-2.1.6-NPU"
                else -> "LiteRT-2.1.6-NPU+GPU+CPU-${runtimeConfig.cpuThreads}t"
            },
        ),
        modelSha256 = mapOf(runtimeConfig.modelAsset to runtimeConfig.modelSha256),
        maxHands = 2,
        providesBoundingBoxes = true,
        providesLandmarks = true,
        providesPairPredictions = true,
    )

    private val closed = AtomicBoolean(false)
    private val runtime: R8LiteRtRuntime
    private val inputFloatValues = if (runtimeConfig.inputTensorType == "float32") {
        FloatArray(3 * runtimeConfig.inputSize * runtimeConfig.inputSize)
    } else {
        null
    }
    private val inputInt8Values = if (runtimeConfig.inputTensorType == "int8") {
        ByteArray(3 * runtimeConfig.inputSize * runtimeConfig.inputSize)
    } else {
        null
    }
    private val inputInt8Lookup = if (runtimeConfig.inputTensorType == "int8") {
        R8LiteRtRuntime.buildUnitByteToInt8Lookup(
            runtimeConfig.inputScale,
            runtimeConfig.inputZeroPoint,
        )
    } else {
        null
    }
    private val pixels = if (runtimeConfig.inputTensorType == "float32") {
        IntArray(runtimeConfig.inputSize * runtimeConfig.inputSize)
    } else {
        null
    }
    private val letterboxed = Bitmap.createBitmap(
        runtimeConfig.inputSize,
        runtimeConfig.inputSize,
        Bitmap.Config.ARGB_8888,
    )
    private val letterboxCanvas = Canvas(letterboxed)
    private val letterboxDestination = RectF()
    private val productPipeline = R8ProductPipeline()

    init {
        runtime = R8LiteRtRuntime(
            context,
            runtimeConfig.modelAsset,
            runtimeConfig.inputSize,
            runtimeConfig.cpuThreads,
            runtimeConfig.accelerator,
            runtimeConfig.inputTensorType,
            runtimeConfig.inputScale,
            runtimeConfig.inputZeroPoint,
            runtimeConfig.outputTensorType,
            runtimeConfig.outputScale,
            runtimeConfig.outputZeroPoint,
            runtimeConfig.gpuPrecision,
            runtimeConfig.npuPerformanceMode,
            runtimeConfig.npuProfiling,
            runtimeConfig.npuLogLevel,
            runtimeConfig.npuOptimizationLevel,
        )
    }

    internal val resolvedAccelerator: String
        get() = runtime.acceleratorName

    internal val npuRegistered: Boolean
        get() = runtime.isNpuRegistered

    override fun recognize(imageProxy: ImageProxy) {
        if (closed.get()) {
            imageProxy.close()
            return
        }
        val timestampMs = SystemClock.uptimeMillis()
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        val source = Bitmap.createBitmap(imageProxy.width, imageProxy.height, Bitmap.Config.ARGB_8888)
        try {
            imageProxy.planes[0].buffer.apply { rewind() }.let(source::copyPixelsFromBuffer)
        } finally {
            imageProxy.close()
        }
        val transformed = transform(source, rotationDegrees, mirrorHorizontally = true)
        if (transformed !== source) source.recycle()
        val cameraPreparationMs = (SystemClock.uptimeMillis() - timestampMs).coerceAtLeast(0)
        runCatching { recognize(transformed, timestampMs) }
            .onFailure { onError("R8 LiteRT inference failed: ${it.message ?: it.javaClass.simpleName}") }
        if (BuildConfig.R8_DIAGNOSTIC_TIMING_LOG) {
            Log.i(
                TIMING_LOG_TAG,
                "camera_prepare_ms=$cameraPreparationMs pipeline_total_ms=" +
                    (SystemClock.uptimeMillis() - timestampMs).coerceAtLeast(0),
            )
        }
        transformed.recycle()
    }

    internal fun recognize(bitmap: Bitmap, timestampMs: Long = SystemClock.uptimeMillis()) {
        check(!closed.get()) { "recognizer is closed" }
        val startedMs = SystemClock.uptimeMillis()
        val transform = R8OutputParser.letterbox(bitmap.width, bitmap.height, runtimeConfig.inputSize)
        prepareInput(bitmap, transform)
        val inferenceStartMs = SystemClock.uptimeMillis()
        val output = inputInt8Values?.let(runtime::runInt8)
            ?: runtime.run(requireNotNull(inputFloatValues))
        val inferenceEndMs = SystemClock.uptimeMillis()
        val detections = R8OutputParser.parse(output, transform)
        val postprocessingEndMs = SystemClock.uptimeMillis()
        val processed = productPipeline.update(timestampMs, detections)
        val finishedMs = SystemClock.uptimeMillis()
        if (BuildConfig.R8_DIAGNOSTIC_TIMING_LOG) {
            Log.i(
                TIMING_LOG_TAG,
                "model_total_ms=${finishedMs - startedMs} " +
                    "preprocess_ms=${inferenceStartMs - startedMs} " +
                    "detector_ms=${inferenceEndMs - inferenceStartMs} " +
                    "postprocess_ms=${postprocessingEndMs - inferenceEndMs} " +
                    "policy_ms=${finishedMs - postprocessingEndMs}",
            )
        }
        onResult(
            FrameRecognition(
                timestampMs,
                processed.hands,
                bitmap.width,
                bitmap.height,
                (finishedMs - startedMs).coerceAtLeast(0),
                estimateLuma(bitmap),
                processed.pairPrediction,
                processed.events,
                ComponentLatency(
                    preprocessingMs = (inferenceStartMs - startedMs).coerceAtLeast(0),
                    detectorMs = (inferenceEndMs - inferenceStartMs).coerceAtLeast(0),
                    postprocessingMs = (postprocessingEndMs - inferenceEndMs).coerceAtLeast(0),
                    trackingAndPolicyMs = (finishedMs - postprocessingEndMs).coerceAtLeast(0),
                    totalMs = (finishedMs - startedMs).coerceAtLeast(0),
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
        runtime.close()
        letterboxed.recycle()
    }

    private fun prepareInput(bitmap: Bitmap, transform: R8Letterbox) {
        val resizedWidth = (bitmap.width * transform.scale).toInt().coerceAtLeast(1)
        val resizedHeight = (bitmap.height * transform.scale).toInt().coerceAtLeast(1)
        letterboxCanvas.drawColor(Color.rgb(114, 114, 114))
        letterboxDestination.set(
            transform.padX,
            transform.padY,
            transform.padX + resizedWidth,
            transform.padY + resizedHeight,
        )
        letterboxCanvas.drawBitmap(bitmap, null, letterboxDestination, PAINT)

        val quantized = inputInt8Values
        val lookup = inputInt8Lookup
        if (quantized != null && lookup != null) {
            R8BitmapPreprocessor.packInt8(letterboxed, runtimeConfig.inputSize, quantized, lookup)
            return
        }

        letterboxed.getPixels(
            requireNotNull(pixels),
            0,
            runtimeConfig.inputSize,
            0,
            0,
            runtimeConfig.inputSize,
            runtimeConfig.inputSize,
        )
        val unpacked = requireNotNull(pixels)
        val planeSize = unpacked.size
        val floats = requireNotNull(inputFloatValues)
        unpacked.forEachIndexed { index, color ->
            floats[index] = Color.red(color) / 255f
            floats[planeSize + index] = Color.green(color) / 255f
            floats[2 * planeSize + index] = Color.blue(color) / 255f
        }
    }

    private fun transform(bitmap: Bitmap, rotationDegrees: Int, mirrorHorizontally: Boolean): Bitmap {
        if (rotationDegrees == 0 && !mirrorHorizontally) return bitmap
        val frameTransform = R8FrameTransforms.cameraFrame(
            bitmap.width,
            bitmap.height,
            rotationDegrees,
            mirrorHorizontally,
        )
        val matrix = Matrix().apply { setValues(frameTransform.matrixValues) }
        return Bitmap.createBitmap(
            frameTransform.outputWidth,
            frameTransform.outputHeight,
            Bitmap.Config.ARGB_8888,
        ).also { transformed ->
            Canvas(transformed).drawBitmap(bitmap, matrix, PAINT)
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
                total += Color.red(color) * 3L + Color.green(color) * 6L + Color.blue(color)
                count += 10
                x += stepX
            }
            y += stepY
        }
        return if (count == 0) 255f else total.toFloat() / count
    }

    private companion object {
        const val TIMING_LOG_TAG = "OppoVisualR8Timing"
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

data class R8RuntimeConfig(
    val modelAsset: String = "gesture_pose_final_fullqat_w8a16_640.tflite",
    val modelSha256: String = "0".repeat(64),
    val modelVersion: String = "gesture-pose-final-fullqat-w8a16-640",
    val inputSize: Int = R8OutputParser.DEFAULT_INPUT_SIZE,
    val cpuThreads: Int = 6,
    val accelerator: String = "cpu",
    val inputTensorType: String = "float32",
    val inputScale: Float = 0f,
    val inputZeroPoint: Int = 0,
    val outputTensorType: String = "float32",
    val outputScale: Float = 0f,
    val outputZeroPoint: Int = 0,
    val gpuPrecision: String = "default",
    val npuPerformanceMode: String = "default",
    val npuProfiling: String = "off",
    val npuLogLevel: String = "off",
    val npuOptimizationLevel: String = "htp_optimize_for_inference",
) {
    init {
        require(modelAsset.isNotBlank())
        require(modelSha256.matches(Regex("[0-9a-f]{64}")))
        require(inputSize > 0 && inputSize % 32 == 0)
        require(cpuThreads > 0)
        require(accelerator in setOf("cpu", "gpu", "gpu_cpu", "npu", "npu_cpu", "npu_gpu_cpu")) {
            "accelerator must be cpu, gpu, gpu_cpu, npu, npu_cpu, or npu_gpu_cpu"
        }
        require(inputTensorType in setOf("float32", "int8"))
        require(outputTensorType in setOf("float32", "int8"))
        require(inputTensorType == "float32" || inputScale > 0f)
        require(outputTensorType == "float32" || outputScale > 0f)
        require(inputZeroPoint in -128..127)
        require(outputZeroPoint in -128..127)
        require(gpuPrecision in setOf("default", "fp16", "fp32"))
        require(
            npuPerformanceMode in setOf(
                "default",
                "sustained",
                "sustained_high_performance",
                "burst",
                "powersave",
                "high_performance",
                "power_saver",
                "low_power_saver",
                "high_power_saver",
                "low_balanced",
                "balanced",
                "extreme_power_saver",
            ),
        )
        require(npuProfiling in setOf("off", "basic", "detailed", "linting", "optrace"))
        require(npuLogLevel in setOf("off", "error", "warn", "info", "verbose", "debug"))
        require(
            npuOptimizationLevel in setOf(
                "htp_optimize_for_inference",
                "htp_optimize_for_prepare",
                "htp_optimize_for_inference_o3",
            ),
        )
    }
}

object R8LiteRtFrameRecognizerFactory : FrameRecognizerFactory {
    internal val productionRuntimeConfig = R8RuntimeConfig(
        modelAsset = BuildConfig.R8_MODEL_ASSET,
        modelSha256 = BuildConfig.R8_MODEL_SHA256,
        modelVersion = BuildConfig.R8_MODEL_VERSION,
        accelerator = BuildConfig.R8_ACCELERATOR,
        npuPerformanceMode = BuildConfig.R8_NPU_PERFORMANCE_MODE,
        npuOptimizationLevel = BuildConfig.R8_NPU_OPTIMIZATION_LEVEL,
    )

    override fun create(
        context: Context,
        onResult: (RecognitionFrame) -> Unit,
        onError: (String) -> Unit,
    ): FrameRecognizer = R8LiteRtGestureRecognizer(
        context,
        onResult = { onResult(RecognitionFrame.Gesture(it)) },
        onError = onError,
        runtimeConfig = productionRuntimeConfig,
    )
}
