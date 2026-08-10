package com.oppovisual.app.background

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.util.Range
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.oppovisual.app.MainActivity
import com.oppovisual.app.R
import com.oppovisual.app.camera.bindWithFrameRateFallback
import com.oppovisual.app.recognition.FrameRecognizer
import com.oppovisual.app.recognition.ProductionGestureRecognizerFactory
import com.oppovisual.app.recognition.RecognitionFrame
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class GestureControlForegroundService : LifecycleService() {
    private val analyzerExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val recognizerBusy = AtomicBoolean(false)
    private val frameCount = AtomicLong(0L)
    private var recognizer: FrameRecognizer? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraBindingGeneration = 0

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startAsCameraForeground(buildNotification(paused = false, starting = true))
        recognizer = ProductionGestureRecognizerFactory.create(
            context = applicationContext,
            onResult = { frame ->
                recognizerBusy.set(false)
                val recognition = (frame as? RecognitionFrame.Gesture)?.recognition ?: return@create
                val count = frameCount.incrementAndGet()
                if (count == 1L) {
                    // CameraX can be bound before its first analyzer callback. Mark
                    // the service as running only once real frames are flowing.
                    BackgroundGestureControl.updatePhase(BackgroundControlPhase.RUNNING)
                    updateNotification(paused = false, starting = false)
                }
                if (count % DIAGNOSTIC_LOG_EVERY_FRAMES == 0L || recognition.primaryEvent != null) {
                    Log.i(
                        TAG,
                            "frame=$count hands=${recognition.hands.size} " +
                            "status=${recognition.interactionStatus} scale=${recognition.scaleStatus} " +
                            "luma=${"%.1f".format(java.util.Locale.US, recognition.averageLuma)} " +
                            "event=${recognition.primaryEvent?.gesture?.name ?: "none"} " +
                            "input=${recognition.inputWidth}x${recognition.inputHeight} " +
                            "model_ms=${recognition.processingLatencyMs} " +
                            "callback_age_ms=${(SystemClock.uptimeMillis() - recognition.timestampMs).coerceAtLeast(0L)}",
                    )
                }
                val event = recognition.primaryEvent
                // Pair zoom is applied incrementally while both fists move.
                // The terminal event below only flushes the remaining delta.
                if (event?.gesture != com.oppovisual.core.GestureId.TWO_HAND_ZOOM) {
                    GestureAccessibilityService.updateLiveScale(
                        recognition.activeScaleParameter,
                        recognition.scaleStatus,
                    )
                }
                if (event != null) {
                    if (!GestureAccessibilityService.dispatchAndUpdate(
                            event,
                            recognition.interactionStatus,
                            recognition.scaleStatus,
                        )
                    ) {
                        failAndStop("无障碍服务未连接")
                    }
                } else {
                    GestureAccessibilityService.updateInteractionStatus(
                        recognition.interactionStatus,
                        recognition.scaleStatus,
                    )
                }
            },
            onError = { message ->
                recognizerBusy.set(false)
                failAndStop(message)
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action ?: ACTION_START) {
            ACTION_START, ACTION_RESUME -> bindCamera()
            ACTION_PAUSE -> pauseCamera()
            ACTION_STOP -> stopSelf()
        }
        return Service.START_NOT_STICKY
    }

    override fun onDestroy() {
        cameraBindingGeneration++
        runCatching { cameraProvider?.unbindAll() }
        cameraProvider = null
        recognizer?.close()
        recognizer = null
        GestureAccessibilityService.clearStatusOverlay()
        analyzerExecutor.shutdownNow()
        recognizerBusy.set(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        BackgroundGestureControl.serviceStopped()
        super.onDestroy()
    }

    @SuppressLint("MissingPermission")
    @OptIn(ExperimentalCamera2Interop::class)
    private fun bindCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            failAndStop("缺少相机权限")
            return
        }
        if (!BackgroundGestureControl.isAccessibilityEnabled(this)) {
            failAndStop("请先启用 OppoVisual 无障碍服务")
            return
        }
        val generation = ++cameraBindingGeneration
        frameCount.set(0L)
        BackgroundGestureControl.updatePhase(BackgroundControlPhase.STARTING)
        updateNotification(paused = false, starting = true)
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener(
            {
                if (generation != cameraBindingGeneration) return@addListener
                runCatching {
                    val provider = providerFuture.get().also { cameraProvider = it }
                    val resolutionSelector = ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .build()
                    bindWithFrameRateFallback(BACKGROUND_CAMERA_FPS..BACKGROUND_CAMERA_FPS) { frameRate ->
                        val builder = ImageAnalysis.Builder()
                            .setResolutionSelector(resolutionSelector)
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        frameRate?.let {
                            Camera2Interop.Extender(builder).setCaptureRequestOption(
                                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                                Range(it.first, it.last),
                            )
                        }
                        val analysis = builder.build().also { useCase ->
                            useCase.setAnalyzer(analyzerExecutor) { image ->
                                val activeRecognizer = recognizer
                                if (activeRecognizer == null || !recognizerBusy.compareAndSet(false, true)) {
                                    image.close()
                                } else {
                                    runCatching { activeRecognizer.recognize(image) }
                                        .onFailure {
                                            image.close()
                                            recognizerBusy.set(false)
                                            failAndStop(it.message ?: "后台识别失败")
                                        }
                                }
                            }
                        }
                        provider.unbindAll()
                        provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
                    }
                }.onSuccess {
                    BackgroundGestureControl.updatePhase(BackgroundControlPhase.RUNNING)
                    updateNotification(paused = false, starting = false)
                }.onFailure {
                    failAndStop("后台相机启动失败：${it.message ?: it.javaClass.simpleName}")
                }
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    private fun pauseCamera() {
        cameraBindingGeneration++
        runCatching { cameraProvider?.unbindAll() }
        recognizerBusy.set(false)
        GestureAccessibilityService.clearStatusOverlay()
        BackgroundGestureControl.updatePhase(BackgroundControlPhase.PAUSED)
        updateNotification(paused = true, starting = false)
    }

    private fun failAndStop(message: String) {
        Log.e(TAG, message)
        GestureAccessibilityService.clearStatusOverlay()
        BackgroundGestureControl.updatePhase(BackgroundControlPhase.ERROR, message)
        stopSelf()
    }

    private fun startAsCameraForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "全局手势控制",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "显示后台相机手势识别的运行状态"
                setShowBadge(false)
            },
        )
    }

    private fun updateNotification(paused: Boolean, starting: Boolean) {
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            buildNotification(paused, starting),
        )
    }

    private fun buildNotification(paused: Boolean, starting: Boolean): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val toggleAction = if (paused) ACTION_RESUME else ACTION_PAUSE
        val toggleLabel = if (paused) "继续" else "暂停"
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("灵映全局手势控制")
            .setContentText(
                when {
                    starting -> "正在启动本机识别"
                    paused -> "已暂停，相机已释放"
                    else -> "运行中 · 图像仅在本机处理"
                },
            )
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, toggleLabel, servicePendingIntent(toggleAction, 1))
            .addAction(0, "停止", servicePendingIntent(ACTION_STOP, 2))
            .build()
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent = PendingIntent.getService(
        this,
        requestCode,
        Intent(this, GestureControlForegroundService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val ACTION_START = "com.oppovisual.app.background.START"
        const val ACTION_PAUSE = "com.oppovisual.app.background.PAUSE"
        const val ACTION_RESUME = "com.oppovisual.app.background.RESUME"
        const val ACTION_STOP = "com.oppovisual.app.background.STOP"

        private const val TAG = "BackgroundGesture"
        private const val NOTIFICATION_CHANNEL_ID = "background_gesture_control"
        private const val NOTIFICATION_ID = 4203
        private const val BACKGROUND_CAMERA_FPS = 60
        private const val DIAGNOSTIC_LOG_EVERY_FRAMES = 30L
    }
}
