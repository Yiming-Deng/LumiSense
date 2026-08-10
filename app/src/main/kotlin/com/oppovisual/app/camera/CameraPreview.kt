package com.oppovisual.app.camera

import android.annotation.SuppressLint
import android.hardware.camera2.CaptureRequest
import android.util.Log
import android.util.Range
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.Executors

private const val CAMERA_LOG_TAG = "OppoVisualCamera"
internal const val PREFERRED_CAMERA_FPS = 60

internal fun <T> bindWithFrameRateFallback(
    preferredFrameRate: IntRange,
    bind: (IntRange?) -> T,
): T {
    require(preferredFrameRate.first > 0 && preferredFrameRate.last >= preferredFrameRate.first)
    return try {
        bind(preferredFrameRate)
    } catch (preferredFailure: Exception) {
        try {
            bind(null)
        } catch (fallbackFailure: Exception) {
            fallbackFailure.addSuppressed(preferredFailure)
            throw fallbackFailure
        }
    }
}

@SuppressLint("MissingPermission")
@OptIn(ExperimentalCamera2Interop::class)
@Composable
fun CameraPreview(
    onFrame: (androidx.camera.core.ImageProxy) -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val resolutionSelector = remember {
        ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .build()
    }

    AndroidView(factory = { previewView }, modifier = modifier)

    DisposableEffect(lifecycleOwner, previewView) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        val listener = Runnable {
            runCatching {
                val provider = cameraProviderFuture.get()
                bindWithFrameRateFallback(PREFERRED_CAMERA_FPS..PREFERRED_CAMERA_FPS) { frameRate ->
                    val previewBuilder = Preview.Builder()
                        .setResolutionSelector(resolutionSelector)
                    val analysisBuilder = ImageAnalysis.Builder()
                        .setResolutionSelector(resolutionSelector)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    if (frameRate != null) {
                        val target = Range(frameRate.first, frameRate.last)
                        Camera2Interop.Extender(previewBuilder).setCaptureRequestOption(
                            CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                            target,
                        )
                        Camera2Interop.Extender(analysisBuilder).setCaptureRequestOption(
                            CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                            target,
                        )
                    }
                    val preview = previewBuilder.build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val analysis = analysisBuilder.build().also {
                        it.setAnalyzer(executor, onFrame)
                    }
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_FRONT_CAMERA,
                        preview,
                        analysis,
                    )
                    Log.i(
                        CAMERA_LOG_TAG,
                        if (frameRate == null) {
                            "CameraX bound with automatic frame-rate fallback"
                        } else {
                            "CameraX bound with target ${frameRate.first}-${frameRate.last} FPS"
                        },
                    )
                }
            }.onFailure { onError("相机启动失败：${it.message}") }
        }
        cameraProviderFuture.addListener(listener, ContextCompat.getMainExecutor(context))
        onDispose {
            runCatching { cameraProviderFuture.get().unbindAll() }
            executor.shutdownNow()
        }
    }
}
