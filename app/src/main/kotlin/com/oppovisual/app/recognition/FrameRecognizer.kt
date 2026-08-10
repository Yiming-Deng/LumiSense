package com.oppovisual.app.recognition

import android.content.Context
import androidx.camera.core.ImageProxy
import com.oppovisual.core.ExpressionId
import com.oppovisual.core.GestureId
import com.oppovisual.core.HeadMotionId
import java.io.Closeable

data class RecognizerDescriptor(
    val domain: RecognitionDomain = RecognitionDomain.GESTURE,
    val backendId: String,
    val modelVersion: String,
    val parameterVersion: String,
    val supportedGestures: Set<GestureId>,
    val supportedExpressions: Set<ExpressionId> = emptySet(),
    val supportedHeadMotions: Set<HeadMotionId> = emptySet(),
    val componentVersions: Map<String, String> = emptyMap(),
    val modelSha256: Map<String, String> = emptyMap(),
    val maxHands: Int = 2,
    val providesBoundingBoxes: Boolean = false,
    val providesLandmarks: Boolean = true,
    val providesPairPredictions: Boolean = false,
)

interface FrameRecognizer : Closeable {
    val descriptor: RecognizerDescriptor

    fun recognize(imageProxy: ImageProxy)
}

fun interface FrameRecognizerFactory {
    fun create(
        context: Context,
        onResult: (RecognitionFrame) -> Unit,
        onError: (String) -> Unit,
    ): FrameRecognizer
}

object MediaPipeFrameRecognizerFactory : FrameRecognizerFactory {
    override fun create(
        context: Context,
        onResult: (RecognitionFrame) -> Unit,
        onError: (String) -> Unit,
    ): FrameRecognizer = MediaPipeGestureRecognizer(
        context,
        onResult = { onResult(RecognitionFrame.Gesture(it)) },
        onError = onError,
    )
}

object MediaPipeFaceRecognizerFactory : FrameRecognizerFactory {
    override fun create(
        context: Context,
        onResult: (RecognitionFrame) -> Unit,
        onError: (String) -> Unit,
    ): FrameRecognizer = MediaPipeFaceRecognizer(
        context,
        onResult = { onResult(RecognitionFrame.Face(it)) },
        onError = onError,
    )
}

enum class RecognitionBackend {
    MEDIAPIPE_V1,
    UNIFIED_V2,
}

/** Keeps backend selection explicit while V2 model assets are produced independently. */
class SwitchableFrameRecognizerFactory(
    private val backend: RecognitionBackend,
    private val v2Factory: FrameRecognizerFactory? = null,
) : FrameRecognizerFactory {
    override fun create(
        context: Context,
        onResult: (RecognitionFrame) -> Unit,
        onError: (String) -> Unit,
    ): FrameRecognizer = when (backend) {
        RecognitionBackend.MEDIAPIPE_V1 -> MediaPipeFrameRecognizerFactory.create(context, onResult, onError)
        RecognitionBackend.UNIFIED_V2 -> requireNotNull(v2Factory) {
            "Unified V2 backend selected, but its model bundle is unavailable"
        }.create(context, onResult, onError)
    }
}
