package com.oppovisual.app.ui

import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.oppovisual.app.recognition.BlendshapeScore
import com.oppovisual.app.recognition.FrameRecognizer
import com.oppovisual.app.recognition.FrameRecognizerFactory
import com.oppovisual.app.recognition.MediaPipeFaceRecognizerFactory
import com.oppovisual.app.recognition.ProductionGestureRecognizerFactory
import com.oppovisual.app.recognition.RecognitionDomain
import com.oppovisual.app.recognition.RecognitionFrame
import com.oppovisual.app.recognition.RecognizedHand
import com.oppovisual.app.recognition.SerializedCloseableSlot
import com.oppovisual.app.settings.AppSettings
import com.oppovisual.app.settings.SettingsRepository
import com.oppovisual.app.ui.face.ChallengePhase
import com.oppovisual.app.ui.face.ChallengeUiState
import com.oppovisual.app.ui.face.FACE_EFFECT_EXPRESSIONS
import com.oppovisual.app.ui.face.FaceChallengeController
import com.oppovisual.app.ui.face.FaceExperienceMode
import com.oppovisual.app.ui.face.HeadgearId
import com.oppovisual.core.ExpressionId
import com.oppovisual.core.GestureId
import com.oppovisual.core.HeadDirection
import com.oppovisual.core.HeadMotionId
import com.oppovisual.core.HeadPose
import com.oppovisual.core.LatencySummary
import com.oppovisual.core.LatencyWindow
import com.oppovisual.core.Point3
import com.oppovisual.core.ProductInteractionStatus
import com.oppovisual.core.ProductScaleStatus
import com.oppovisual.core.RecognitionMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

data class RecognitionUiState(
    val domain: RecognitionDomain = RecognitionDomain.GESTURE,
    val gestureMode: RecognitionMode = RecognitionMode.DISPLAY,
    val isRecognizerReady: Boolean = false,
    val handPresent: Boolean = false,
    val handCount: Int = 0,
    val gesture: GestureId? = null,
    val confidence: Float = 0f,
    val landmarks: List<Point3> = emptyList(),
    val allLandmarks: List<List<Point3>> = emptyList(),
    val gestureHands: List<RecognizedHand> = emptyList(),
    val facePresent: Boolean = false,
    val expression: ExpressionId = ExpressionId.NONE,
    val expressionConfidence: Float = 0f,
    val expressionScores: Map<ExpressionId, Float> = emptyMap(),
    val expressionEventSequence: Long = 0,
    val lastExpressionEvent: ExpressionId? = null,
    val lastExpressionEventTimestampMs: Long = 0,
    val headDirection: HeadDirection = HeadDirection.CENTER,
    val headPose: HeadPose? = null,
    val headCalibrated: Boolean = false,
    val lastHeadMotion: HeadMotionId? = null,
    val headMotionEventSequence: Long = 0,
    val lastHeadMotionTimestampMs: Long = 0,
    val blendshapes: List<BlendshapeScore> = emptyList(),
    val faceLandmarks: List<Point3> = emptyList(),
    val lastFaceFrameTimestampMs: Long = 0,
    val inputWidth: Int = 1,
    val inputHeight: Int = 1,
    val eventSequence: Long = 0,
    val feedbackSequence: Long = 0,
    val lastEvent: GestureId? = null,
    val lastEventTimestampMs: Long = 0,
    val activeScaleFactor: Float? = null,
    val lastScaleFactor: Float? = null,
    val interactionZoomFactor: Float = 1f,
    val scaleStatus: ProductScaleStatus = ProductScaleStatus.IDLE,
    val gestureStableFrames: Int = 0,
    val interactionStatus: ProductInteractionStatus = ProductInteractionStatus.IDLE,
    val fps: Float = 0f,
    val latency: LatencySummary? = null,
    val endToEndLatencyMs: Long? = null,
    val modelLatencyMs: Long? = null,
    val isLowLight: Boolean = false,
    val modelVersion: String = "",
    val parameterVersion: String = "",
    val faceExperienceMode: FaceExperienceMode = FaceExperienceMode.FREE,
    val selectedHeadgear: HeadgearId = HeadgearId.OFF,
    val challenge: ChallengeUiState = ChallengeUiState(),
    val error: String? = null,
)

data class DiagnosticRecord(
    val domain: RecognitionDomain,
    val timestampMs: Long,
    val label: String,
    val confidence: Float,
    val handCount: Int,
    val latencyMs: Long,
    val event: Boolean,
    val modelVersion: String,
    val parameterVersion: String,
)

class MainViewModel @JvmOverloads constructor(
    application: Application,
    private val gestureRecognizerFactory: FrameRecognizerFactory = ProductionGestureRecognizerFactory,
    private val faceRecognizerFactory: FrameRecognizerFactory = MediaPipeFaceRecognizerFactory,
) : AndroidViewModel(application) {
    private val settingsRepository = SettingsRepository(application)
    private val latencyWindow = LatencyWindow(DIAGNOSTIC_CAPACITY)
    private val records = ArrayDeque<DiagnosticRecord>(DIAGNOSTIC_CAPACITY)
    private val recognizerBusy = AtomicBoolean(false)
    private val _uiState = MutableStateFlow(RecognitionUiState())
    private val recognizerSlot = SerializedCloseableSlot<FrameRecognizer>()
    private var recognizerGeneration = 0
    private var fpsWindowStartMs = 0L
    private var fpsFrames = 0
    private var endToEndLatencyTotalMs = 0L
    private var modelLatencyTotalMs = 0L
    private var pairZoomBaseFactor = 1f
    private var performanceSampleCount = 0
    private var displayedEndToEndLatencyMs: Long? = null
    private var displayedModelLatencyMs: Long? = null
    private var lowLightFrames = 0
    private val faceChallengeController = FaceChallengeController()
    private var challengeTicker: Job? = null

    val uiState: StateFlow<RecognitionUiState> = _uiState.asStateFlow()
    val settings: StateFlow<AppSettings> = settingsRepository.settings.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        AppSettings(),
    )

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { appSettings ->
                faceChallengeController.setBestScore(appSettings.bestChallengeScore)
                _uiState.update {
                    it.copy(
                        selectedHeadgear = HeadgearId.fromWireName(appSettings.selectedHeadgear),
                        challenge = faceChallengeController.state,
                    )
                }
            }
        }
    }

    fun ensureRecognizer() {
        if (recognizerSlot.isPresent()) return
        val domain = _uiState.value.domain
        val generation = recognizerGeneration
        val factory = when (domain) {
            RecognitionDomain.GESTURE -> gestureRecognizerFactory
            RecognitionDomain.FACE -> faceRecognizerFactory
        }
        runCatching {
            factory.create(
                context = getApplication(),
                onResult = { frame -> handleRecognition(frame, generation) },
                onError = { message ->
                    if (generation != recognizerGeneration) return@create
                    recognizerBusy.set(false)
                    Log.e(TAG, "Recognizer callback error: $message")
                    _uiState.value = _uiState.value.copy(error = message)
                },
            )
        }.onSuccess {
            if (generation != recognizerGeneration) {
                it.close()
                return@onSuccess
            }
            if (!recognizerSlot.install(it)) {
                it.close()
                return@onSuccess
            }
            _uiState.value = _uiState.value.copy(
                isRecognizerReady = true,
                modelVersion = it.descriptor.modelVersion,
                parameterVersion = it.descriptor.parameterVersion,
                error = null,
            )
        }.onFailure {
            Log.e(TAG, "Recognizer creation failed", it)
            _uiState.value = _uiState.value.copy(error = "模型加载失败：${it.toDiagnosticMessage()}")
        }
    }

    fun setRecognitionDomain(domain: RecognitionDomain) {
        if (_uiState.value.domain == domain) return
        if (domain != RecognitionDomain.FACE) resetChallenge()
        stopRecognizer()
        _uiState.value = _uiState.value.copy(
            domain = domain,
            selectedHeadgear = if (domain == RecognitionDomain.FACE) HeadgearId.OFF else _uiState.value.selectedHeadgear,
            interactionZoomFactor = 1f,
        )
        ensureRecognizer()
    }

    fun setFaceExperienceMode(mode: FaceExperienceMode) {
        if (_uiState.value.faceExperienceMode == mode) return
        if (mode == FaceExperienceMode.FREE) resetChallenge()
        _uiState.update { it.copy(faceExperienceMode = mode, challenge = faceChallengeController.state) }
    }

    fun setGestureMode(mode: RecognitionMode) {
        if (_uiState.value.gestureMode == mode) return
        _uiState.update {
            it.copy(
                gestureMode = mode,
                lastEvent = if (mode == RecognitionMode.DISPLAY) null else it.lastEvent,
                lastEventTimestampMs = if (mode == RecognitionMode.DISPLAY) 0 else it.lastEventTimestampMs,
                activeScaleFactor = null,
                lastScaleFactor = if (mode == RecognitionMode.DISPLAY) null else it.lastScaleFactor,
                interactionZoomFactor = if (mode == RecognitionMode.DISPLAY) 1f else it.interactionZoomFactor,
                scaleStatus = if (mode == RecognitionMode.DISPLAY) ProductScaleStatus.IDLE else it.scaleStatus,
                interactionStatus = if (mode == RecognitionMode.DISPLAY) {
                    ProductInteractionStatus.IDLE
                } else {
                    it.interactionStatus
                },
            )
        }
    }

    fun selectHeadgear(headgear: HeadgearId) {
        _uiState.update { it.copy(selectedHeadgear = headgear) }
        viewModelScope.launch { settingsRepository.setSelectedHeadgear(headgear.wireName) }
    }

    fun startFaceChallenge() {
        if (_uiState.value.domain != RecognitionDomain.FACE) return
        faceChallengeController.start(
            nowMs = SystemClock.uptimeMillis(),
            expressionEventSequence = _uiState.value.expressionEventSequence,
            headMotionEventSequence = _uiState.value.headMotionEventSequence,
        )
        publishChallengeState()
        startChallengeTicker()
    }

    fun pauseFaceChallenge() {
        faceChallengeController.pause(SystemClock.uptimeMillis())
        publishChallengeState()
    }

    fun resumeFaceChallenge() {
        faceChallengeController.resume(SystemClock.uptimeMillis())
        publishChallengeState()
        startChallengeTicker()
    }

    fun quitFaceChallenge() = resetChallenge()

    fun retryFaceChallenge() = startFaceChallenge()

    fun submitFrame(imageProxy: ImageProxy) {
        if (!recognizerBusy.compareAndSet(false, true)) {
            imageProxy.close()
            return
        }
        runCatching {
            if (!recognizerSlot.useIfPresent { it.recognize(imageProxy) }) {
                imageProxy.closeSafely()
                recognizerBusy.set(false)
            }
        }
            .onFailure {
                imageProxy.closeSafely()
                Log.e(TAG, "Frame analysis failed", it)
                _uiState.value = _uiState.value.copy(error = "图像分析失败：${it.toDiagnosticMessage()}")
                recognizerBusy.set(false)
            }
    }

    fun stopRecognizer() {
        recognizerGeneration++
        recognizerSlot.closeAndClear()
        recognizerBusy.set(false)
        latencyWindow.clear()
        fpsWindowStartMs = 0L
        fpsFrames = 0
        endToEndLatencyTotalMs = 0L
        modelLatencyTotalMs = 0L
        performanceSampleCount = 0
        displayedEndToEndLatencyMs = null
        displayedModelLatencyMs = null
        currentFps = 0f
        lowLightFrames = 0
        _uiState.value = _uiState.value.copy(
            isRecognizerReady = false,
            handPresent = false,
            handCount = 0,
            gesture = null,
            confidence = 0f,
            landmarks = emptyList(),
            allLandmarks = emptyList(),
            gestureHands = emptyList(),
            facePresent = false,
            expression = ExpressionId.NONE,
            expressionConfidence = 0f,
            expressionScores = emptyMap(),
            headDirection = HeadDirection.CENTER,
            headPose = null,
            headCalibrated = false,
            lastHeadMotion = null,
            headMotionEventSequence = 0,
            lastHeadMotionTimestampMs = 0,
            blendshapes = emptyList(),
            faceLandmarks = emptyList(),
            lastEvent = null,
            lastEventTimestampMs = 0,
            gestureStableFrames = 0,
            interactionStatus = ProductInteractionStatus.IDLE,
            fps = 0f,
            latency = null,
            endToEndLatencyMs = null,
            modelLatencyMs = null,
            isLowLight = false,
            error = null,
        )
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun reportError(message: String) {
        _uiState.value = _uiState.value.copy(error = message)
    }

    fun acceptOnboarding() = viewModelScope.launch { settingsRepository.acceptOnboarding() }
    fun setShowLandmarks(value: Boolean) = viewModelScope.launch { settingsRepository.setShowLandmarks(value) }
    fun setSoundEnabled(value: Boolean) = viewModelScope.launch { settingsRepository.setSoundEnabled(value) }
    fun setHapticsEnabled(value: Boolean) = viewModelScope.launch { settingsRepository.setHapticsEnabled(value) }
    fun setDiagnosticsOverlayEnabled(value: Boolean) = viewModelScope.launch {
        settingsRepository.setDiagnosticsOverlayEnabled(value)
    }

    fun exportDiagnostics(): File {
        val application = getApplication<Application>()
        val directory = (application.getExternalFilesDir("diagnostics")
            ?: File(application.cacheDir, "diagnostics")).apply { mkdirs() }
        val file = File(directory, "oppovisual-diagnostics-${System.currentTimeMillis()}.csv")
        file.bufferedWriter().use { writer ->
            writer.appendLine("timestamp_ms,domain,label,confidence,subject_count,latency_ms,event,model_version,parameter_version")
            synchronized(records) {
                records.forEach { record ->
                    writer.appendLine(
                        String.format(
                            Locale.US,
                            "%d,%s,%s,%.4f,%d,%d,%s,%s,%s",
                            record.timestampMs,
                            record.domain.name.lowercase(Locale.US),
                            record.label,
                            record.confidence,
                            record.handCount,
                            record.latencyMs,
                            record.event,
                            record.modelVersion,
                            record.parameterVersion,
                        ),
                    )
                }
            }
        }
        return file
    }

    override fun onCleared() {
        challengeTicker?.cancel()
        recognizerSlot.closeAndClear()
        super.onCleared()
    }

    private fun handleRecognition(frame: RecognitionFrame, generation: Int) {
        if (generation != recognizerGeneration || frame.domain != _uiState.value.domain) return
        recognizerBusy.set(false)
        val endToEndLatencyMs = (SystemClock.uptimeMillis() - frame.timestampMs)
            .coerceAtLeast(frame.processingLatencyMs)
        latencyWindow.add(frame.processingLatencyMs)
        updatePerformanceMetrics(
            timestampMs = frame.timestampMs,
            endToEndLatencyMs = endToEndLatencyMs,
            modelLatencyMs = frame.modelLatencyMs,
        )
        lowLightFrames = if (frame.averageLuma < LOW_LIGHT_THRESHOLD) lowLightFrames + 1 else 0
        val current = _uiState.value
        val gestureInteractionEnabled = current.gestureMode == RecognitionMode.INTERACTION
        val isEvent = when (frame) {
            is RecognitionFrame.Gesture -> gestureInteractionEnabled && frame.recognition.confirmedEvent != null
            is RecognitionFrame.Face -> frame.recognition.expression.isNewEvent || frame.recognition.headMotion?.event != null
        }
        val label = when (frame) {
            is RecognitionFrame.Gesture -> {
                val gesture = if (gestureInteractionEnabled) {
                    frame.recognition.confirmedEvent
                        ?: frame.recognition.displayGesture?.takeIf { it.isDynamic }
                } else {
                    frame.recognition.displayGesture?.takeUnless { it.isDynamic }
                }
                gesture?.wireName.orEmpty()
            }
            is RecognitionFrame.Face -> frame.recognition.headMotion?.event?.wireName
                ?: frame.recognition.expression.expression.wireName
        }
        val confidence = when (frame) {
            is RecognitionFrame.Gesture -> if (isEvent) {
                frame.recognition.eventConfidence
            } else {
                frame.recognition.displayConfidence
            }
            is RecognitionFrame.Face -> frame.recognition.expression.confidence
        }
        val subjectCount = when (frame) {
            is RecognitionFrame.Gesture -> frame.recognition.hands.size
            is RecognitionFrame.Face -> if (frame.recognition.facePresent) 1 else 0
        }
        synchronized(records) {
            if (records.size == DIAGNOSTIC_CAPACITY) records.removeFirst()
            records.addLast(
                DiagnosticRecord(
                    domain = frame.domain,
                    timestampMs = frame.timestampMs,
                    label = label,
                    confidence = confidence,
                    handCount = subjectCount,
                    latencyMs = frame.processingLatencyMs,
                    event = isEvent,
                    modelVersion = current.modelVersion,
                    parameterVersion = current.parameterVersion,
                ),
            )
        }
        val common = current.copy(
            eventSequence = current.eventSequence + if (isEvent) 1 else 0,
            feedbackSequence = current.feedbackSequence + if (
                frame is RecognitionFrame.Gesture && isEvent &&
                    frame.recognition.confirmedEvent != GestureId.TWO_HAND_ZOOM
            ) 1 else 0,
            fps = currentFps,
            latency = latencyWindow.summary(),
            endToEndLatencyMs = displayedEndToEndLatencyMs,
            modelLatencyMs = displayedModelLatencyMs,
            isLowLight = lowLightFrames >= LOW_LIGHT_FRAME_COUNT,
            error = null,
        )
        _uiState.value = when (frame) {
            is RecognitionFrame.Gesture -> frame.recognition.let { gestureFrame ->
                val confirmedEvent = gestureFrame.confirmedEvent
                val visibleEvent = when {
                    !gestureInteractionEnabled -> null
                    confirmedEvent != null -> confirmedEvent
                    current.lastEventTimestampMs > 0L &&
                        frame.timestampMs - current.lastEventTimestampMs <= GESTURE_EVENT_DISPLAY_MS -> current.lastEvent
                    else -> null
                }
                val displayGesture = if (gestureFrame.handPresent) {
                    if (gestureInteractionEnabled) {
                        gestureFrame.displayGesture?.takeIf { it.isDynamic }
                    } else {
                        gestureFrame.displayGesture?.takeUnless { it.isDynamic }
                    }
                } else {
                    null
                }
                val activeScaleFactor = gestureFrame.activeScaleParameter?.scaleFactor
                if (activeScaleFactor != null &&
                    current.scaleStatus != ProductScaleStatus.ADJUSTING &&
                    current.scaleStatus != ProductScaleStatus.PAUSED
                ) {
                    pairZoomBaseFactor = current.interactionZoomFactor
                }
                val interactionZoomFactor = when {
                    !gestureInteractionEnabled -> 1f
                    activeScaleFactor != null -> (pairZoomBaseFactor * activeScaleFactor)
                        .coerceIn(PREVIEW_ZOOM_MIN, PREVIEW_ZOOM_MAX)
                    gestureFrame.primaryEvent?.scaleParameter != null -> (
                        current.interactionZoomFactor *
                            requireNotNull(gestureFrame.primaryEvent).scaleParameter!!.scaleFactor
                    ).coerceIn(PREVIEW_ZOOM_MIN, PREVIEW_ZOOM_MAX)
                    else -> current.interactionZoomFactor
                }
                val stableFrames = if (!gestureInteractionEnabled && displayGesture != null && !displayGesture.isDynamic) {
                    if (current.gesture == displayGesture) current.gestureStableFrames + 1 else 1
                } else {
                    0
                }
                common.copy(
                    handPresent = gestureFrame.handPresent,
                    handCount = gestureFrame.hands.size,
                    gesture = displayGesture,
                    confidence = if (gestureFrame.handPresent) gestureFrame.displayConfidence else 0f,
                    landmarks = gestureFrame.landmarks,
                    allLandmarks = gestureFrame.allLandmarks,
                    gestureHands = gestureFrame.hands,
                    inputWidth = gestureFrame.inputWidth,
                    inputHeight = gestureFrame.inputHeight,
                    lastEvent = visibleEvent,
                    lastEventTimestampMs = when {
                        confirmedEvent != null -> frame.timestampMs
                        visibleEvent != null -> current.lastEventTimestampMs
                        else -> 0L
                    },
                    activeScaleFactor = if (gestureInteractionEnabled) activeScaleFactor else null,
                    lastScaleFactor = if (gestureInteractionEnabled && gestureFrame.primaryEvent != null) {
                        gestureFrame.primaryEvent?.scaleParameter?.scaleFactor
                    } else {
                        current.lastScaleFactor
                    },
                    interactionZoomFactor = interactionZoomFactor,
                    scaleStatus = if (gestureInteractionEnabled) {
                        gestureFrame.scaleStatus
                    } else {
                        ProductScaleStatus.IDLE
                    },
                    gestureStableFrames = stableFrames,
                    interactionStatus = if (gestureInteractionEnabled) {
                        gestureFrame.interactionStatus
                    } else {
                        ProductInteractionStatus.IDLE
                    },
                    facePresent = false,
                    faceLandmarks = emptyList(),
                    blendshapes = emptyList(),
                )
            }
            is RecognitionFrame.Face -> frame.recognition.let { faceFrame ->
                val expressionIsNew = faceFrame.expression.isNewEvent
                val expressionSequence = current.expressionEventSequence + if (expressionIsNew) 1 else 0
                val headMotionEvent = faceFrame.headMotion?.event
                val headMotionSequence = current.headMotionEventSequence + if (headMotionEvent != null) 1 else 0
                val previousChallengeAttempts = faceChallengeController.state.attempts.size
                var challengeState = faceChallengeController.tick(
                    nowMs = frame.timestampMs,
                    expressionEventSequence = expressionSequence,
                    headMotionEventSequence = headMotionSequence,
                )
                if (expressionIsNew) {
                    challengeState = faceChallengeController.onExpressionEvent(
                        expression = faceFrame.expression.expression,
                        expressionEventSequence = expressionSequence,
                        nowMs = frame.timestampMs,
                        headMotionEventSequence = headMotionSequence,
                    )
                } else if (
                    faceFrame.expression.expression == ExpressionId.LEFT_WINK ||
                    faceFrame.expression.expression == ExpressionId.RIGHT_WINK
                ) {
                    challengeState = faceChallengeController.onHeldExpression(
                        expression = faceFrame.expression.expression,
                        nowMs = frame.timestampMs,
                        headMotionEventSequence = headMotionSequence,
                    )
                }
                challengeState = faceChallengeController.onTargetExpressionScores(
                    scores = faceFrame.expression.scores,
                    nowMs = frame.timestampMs,
                    expressionEventSequence = expressionSequence,
                    headMotionEventSequence = headMotionSequence,
                )
                if (headMotionEvent != null) {
                    challengeState = faceChallengeController.onHeadMotionEvent(
                        motion = headMotionEvent,
                        headMotionEventSequence = headMotionSequence,
                        nowMs = frame.timestampMs,
                        expressionEventSequence = expressionSequence,
                    )
                }
                val challengeHit = challengeState.attempts.size > previousChallengeAttempts &&
                    challengeState.attempts.lastOrNull()?.success == true
                if (challengeState.phase == ChallengePhase.RESULT && challengeState.bestScore > current.challenge.bestScore) {
                    viewModelScope.launch { settingsRepository.setBestChallengeScore(challengeState.bestScore) }
                }
                val freeModeFeedback = current.faceExperienceMode == FaceExperienceMode.FREE &&
                    expressionIsNew && faceFrame.expression.expression in FACE_EFFECT_EXPRESSIONS
                common.copy(
                    handPresent = false,
                    handCount = 0,
                    gesture = null,
                    confidence = 0f,
                    landmarks = emptyList(),
                    allLandmarks = emptyList(),
                    gestureHands = emptyList(),
                    gestureStableFrames = 0,
                    activeScaleFactor = null,
                    lastScaleFactor = null,
                    scaleStatus = ProductScaleStatus.IDLE,
                    facePresent = faceFrame.facePresent,
                    expression = faceFrame.expression.expression,
                    expressionConfidence = faceFrame.expression.confidence,
                    expressionScores = faceFrame.expression.scores,
                    expressionEventSequence = expressionSequence,
                    lastExpressionEvent = if (expressionIsNew) {
                        faceFrame.expression.expression
                    } else {
                        current.lastExpressionEvent
                    },
                    lastExpressionEventTimestampMs = if (expressionIsNew) {
                        frame.timestampMs
                    } else {
                        current.lastExpressionEventTimestampMs
                    },
                    headDirection = faceFrame.headMotion?.direction ?: HeadDirection.CENTER,
                    headPose = faceFrame.headMotion?.pose,
                    headCalibrated = faceFrame.headMotion?.calibrated == true,
                    lastHeadMotion = headMotionEvent ?: current.lastHeadMotion,
                    headMotionEventSequence = headMotionSequence,
                    lastHeadMotionTimestampMs = if (headMotionEvent != null) {
                        frame.timestampMs
                    } else {
                        current.lastHeadMotionTimestampMs
                    },
                    blendshapes = faceFrame.blendshapes,
                    faceLandmarks = faceFrame.landmarks,
                    lastFaceFrameTimestampMs = frame.timestampMs,
                    inputWidth = faceFrame.inputWidth,
                    inputHeight = faceFrame.inputHeight,
                    feedbackSequence = current.feedbackSequence + if (freeModeFeedback || challengeHit) 1 else 0,
                    challenge = challengeState,
                )
            }
        }
    }

    private fun startChallengeTicker() {
        if (challengeTicker?.isActive == true) return
        challengeTicker = viewModelScope.launch {
            while (isActive) {
                val phase = faceChallengeController.state.phase
                if (phase == ChallengePhase.READY || phase == ChallengePhase.RESULT) break
                if (phase != ChallengePhase.PAUSED) {
                    faceChallengeController.tick(
                        nowMs = SystemClock.uptimeMillis(),
                        expressionEventSequence = _uiState.value.expressionEventSequence,
                        headMotionEventSequence = _uiState.value.headMotionEventSequence,
                    )
                    publishChallengeState()
                    persistBestScoreIfNeeded()
                }
                delay(CHALLENGE_TICK_MS)
            }
        }
    }

    private fun publishChallengeState() {
        _uiState.update { it.copy(challenge = faceChallengeController.state) }
    }

    private fun persistBestScoreIfNeeded() {
        val challenge = faceChallengeController.state
        if (challenge.phase == ChallengePhase.RESULT && challenge.bestScore > settings.value.bestChallengeScore) {
            viewModelScope.launch { settingsRepository.setBestChallengeScore(challenge.bestScore) }
        }
    }

    private fun resetChallenge() {
        challengeTicker?.cancel()
        challengeTicker = null
        faceChallengeController.quit()
        publishChallengeState()
    }

    private var currentFps = 0f

    private fun updatePerformanceMetrics(
        timestampMs: Long,
        endToEndLatencyMs: Long,
        modelLatencyMs: Long,
    ) {
        if (fpsWindowStartMs == 0L) fpsWindowStartMs = timestampMs
        fpsFrames++
        endToEndLatencyTotalMs += endToEndLatencyMs
        modelLatencyTotalMs += modelLatencyMs
        performanceSampleCount++
        val elapsed = timestampMs - fpsWindowStartMs
        if (elapsed >= 1_000) {
            currentFps = fpsFrames * 1_000f / elapsed
            displayedEndToEndLatencyMs = endToEndLatencyTotalMs / performanceSampleCount.coerceAtLeast(1)
            displayedModelLatencyMs = modelLatencyTotalMs / performanceSampleCount.coerceAtLeast(1)
            fpsFrames = 0
            endToEndLatencyTotalMs = 0L
            modelLatencyTotalMs = 0L
            performanceSampleCount = 0
            fpsWindowStartMs = timestampMs
        }
    }

    private fun ImageProxy.closeSafely() = runCatching { close() }.getOrNull()

    private fun Throwable.toDiagnosticMessage(): String =
        message?.takeIf { it.isNotBlank() }?.let { "${javaClass.simpleName}: $it" } ?: javaClass.simpleName

    private companion object {
        const val TAG = "OppoVisual"
        const val LOW_LIGHT_THRESHOLD = 40f
        const val LOW_LIGHT_FRAME_COUNT = 15
        const val DIAGNOSTIC_CAPACITY = 10_000
        const val CHALLENGE_TICK_MS = 50L
        const val GESTURE_EVENT_DISPLAY_MS = 1_000L
        const val PREVIEW_ZOOM_MIN = 0.65f
        const val PREVIEW_ZOOM_MAX = 2.20f
    }
}
