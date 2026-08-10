package com.oppovisual.app.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.oppovisual.app.camera.CameraPreview
import com.oppovisual.app.background.BackgroundControlPhase
import com.oppovisual.app.background.BackgroundControlState
import com.oppovisual.app.background.BackgroundGestureControl
import com.oppovisual.app.recognition.RecognitionDomain
import com.oppovisual.app.ui.face.FaceExperienceActionRow
import com.oppovisual.app.ui.face.FaceExperienceOverlay
import com.oppovisual.app.ui.face.FaceModeSelector
import com.oppovisual.core.GestureId
import com.oppovisual.core.Point3
import com.oppovisual.core.ProductScaleStatus
import com.oppovisual.core.RecognitionMode
import kotlin.math.min

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecognitionScreen(
    viewModel: MainViewModel,
    onOpenSettings: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val backgroundControl by BackgroundGestureControl.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val feedback = remember { FeedbackController(context) }
    var accessibilityEnabled by remember {
        mutableStateOf(BackgroundGestureControl.isAccessibilityEnabled(context))
    }
    var showAccessibilityDialog by remember { mutableStateOf(false) }
    var pendingBackgroundStart by remember { mutableStateOf(false) }
    val startBackgroundControl: () -> Unit = {
        runCatching {
            viewModel.stopRecognizer()
            BackgroundGestureControl.start(context)
            (context as? Activity)?.moveTaskToBack(true)
        }.onFailure {
                viewModel.reportError("全局控制启动失败：${it.message ?: it.javaClass.simpleName}")
            viewModel.ensureRecognizer()
        }
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (pendingBackgroundStart) {
            pendingBackgroundStart = false
            if (granted) {
                startBackgroundControl()
            } else {
                viewModel.reportError("全局控制需要通知权限，以便显示运行状态和暂停按钮")
            }
        }
    }
    val requestBackgroundStart: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingBackgroundStart = true
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            pendingBackgroundStart = false
            startBackgroundControl()
        }
    }
    val onBackgroundControlChanged: (Boolean) -> Unit = { enabled ->
        if (!enabled) {
            pendingBackgroundStart = false
            BackgroundGestureControl.stop(context)
        } else if (!accessibilityEnabled) {
            pendingBackgroundStart = true
            showAccessibilityDialog = true
        } else {
            requestBackgroundStart()
        }
    }

    SystemBarAppearance(
        darkStatusBarIcons = false,
        darkNavigationBarIcons = false,
        navigationBarColor = CAMERA_CHROME,
    )

    DisposableEffect(Unit) {
        viewModel.ensureRecognizer()
        onDispose {
            feedback.close()
            viewModel.stopRecognizer()
        }
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                accessibilityEnabled = BackgroundGestureControl.isAccessibilityEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(accessibilityEnabled, pendingBackgroundStart, backgroundControl.active) {
        if (accessibilityEnabled && pendingBackgroundStart && !backgroundControl.active) {
            requestBackgroundStart()
        }
    }
    LaunchedEffect(backgroundControl.phase) {
        if (backgroundControl.phase == BackgroundControlPhase.IDLE ||
            backgroundControl.phase == BackgroundControlPhase.ERROR
        ) {
            viewModel.ensureRecognizer()
        }
    }
    LaunchedEffect(backgroundControl.error) {
        backgroundControl.error?.let(viewModel::reportError)
    }
    LaunchedEffect(state.feedbackSequence) {
        if (state.feedbackSequence > 0) {
            // Audio and haptic services can cross into system-server synchronously;
            // keep that work off the Compose/UI thread at the event boundary.
            withContext(Dispatchers.Default) {
                feedback.play(settings.soundEnabled, settings.hapticsEnabled)
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val isPortrait = maxWidth <= maxHeight
        val viewportWidth = maxWidth
        val viewportHeight = maxHeight
        // Reserve the toolbar and the domain selector above the 3:4 viewport.
        val topChromeHeight = if (isPortrait) 150.dp else 122.dp
        // Both domains use the same 48 dp control row and shared info bar.
        // Keep one viewport contract so switching domains cannot move the camera.
        val bottomPanelHeight = if (isPortrait) 144.dp else 124.dp

        // The camera and all camera-relative overlays share this bounded region.
        // Controls live outside it, so a 3:4 preview is never covered by UI.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = topChromeHeight, bottom = bottomPanelHeight),
        ) {
            val cameraViewportModifier = if (viewportWidth <= viewportHeight) {
                Modifier.align(Alignment.Center).fillMaxWidth().aspectRatio(3f / 4f)
            } else {
                Modifier.align(Alignment.Center).fillMaxHeight().aspectRatio(4f / 3f)
            }
            if (!backgroundControl.active) {
                CameraPreview(
                    onFrame = viewModel::submitFrame,
                    onError = viewModel::reportError,
                    modifier = cameraViewportModifier,
                )
            } else {
                Surface(
                    modifier = Modifier.align(Alignment.Center),
                    color = CAMERA_CHROME.copy(alpha = 0.92f),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text(
                        if (backgroundControl.phase == BackgroundControlPhase.PAUSED) {
                            "全局控制已暂停"
                        } else {
                            "全局控制已接管相机"
                        },
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                        color = Color.White,
                    )
                }
            }
            if (!backgroundControl.active && !state.isRecognizerReady) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text("正在准备识别", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    LinearProgressIndicator(modifier = Modifier.width(140.dp), color = CAMERA_ACCENT)
                }
            }

            if (!backgroundControl.active && settings.showLandmarks) {
                when (state.domain) {
                    RecognitionDomain.GESTURE -> state.allLandmarks.forEachIndexed { index, landmarks ->
                        LandmarkOverlay(landmarks, state.inputWidth, state.inputHeight, index, cameraViewportModifier)
                    }
                    RecognitionDomain.FACE -> if (state.faceLandmarks.isNotEmpty()) {
                        FaceLandmarkOverlay(state.faceLandmarks, state.inputWidth, state.inputHeight, cameraViewportModifier)
                    }
                }
            }
            if (!backgroundControl.active && state.domain == RecognitionDomain.GESTURE) {
                if (state.gestureMode == RecognitionMode.DISPLAY) {
                    GestureEventEffects(state = state, modifier = cameraViewportModifier)
                }
                if (state.gestureMode == RecognitionMode.INTERACTION) {
                    AirGestureDemoOverlay(state = state, modifier = cameraViewportModifier)
                    DynamicGestureEventEffects(state = state, modifier = cameraViewportModifier)
                }
            }
            if (!backgroundControl.active && state.domain == RecognitionDomain.FACE) {
                FaceExperienceOverlay(
                    state = state,
                    onModeSelected = viewModel::setFaceExperienceMode,
                    onHeadgearSelected = viewModel::selectHeadgear,
                    onStartChallenge = viewModel::startFaceChallenge,
                    onPauseChallenge = viewModel::pauseFaceChallenge,
                    onResumeChallenge = viewModel::resumeFaceChallenge,
                    onQuitChallenge = viewModel::quitFaceChallenge,
                    onRetryChallenge = viewModel::retryFaceChallenge,
                    showModeSelector = false,
                    controlsInExternalPanel = true,
                )
            }
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.isLowLight) LowLightBanner()
                if (settings.diagnosticsOverlayEnabled) DiagnosticsOverlay(state)
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(color = CAMERA_CHROME.copy(alpha = 0.78f), shape = MaterialTheme.shapes.medium) {
                Text("灵映", modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp), color = Color.White, style = MaterialTheme.typography.titleMedium)
            }
            Surface(color = CAMERA_CHROME.copy(alpha = 0.78f), shape = MaterialTheme.shapes.medium) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onOpenDiagnostics) { Icon(Icons.Outlined.Analytics, contentDescription = "诊断", tint = Color.White) }
                    VerticalDivider(modifier = Modifier.height(24.dp), color = Color.White.copy(alpha = 0.18f))
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Outlined.Settings, contentDescription = "设置", tint = Color.White) }
                }
            }
        }

        RecognitionModeControls(
            selectedDomain = state.domain,
            isPortrait = isPortrait,
            onDomainSelected = viewModel::setRecognitionDomain,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = if (isPortrait) 62.dp else 50.dp),
        )

        Column(modifier = Modifier.align(Alignment.BottomCenter)) {
            when (state.domain) {
                RecognitionDomain.FACE -> FaceCompactControlRow(
                    state = state,
                    isPortrait = isPortrait,
                    onModeSelected = viewModel::setFaceExperienceMode,
                    onHeadgearSelected = viewModel::selectHeadgear,
                    onStartChallenge = viewModel::startFaceChallenge,
                    onPauseChallenge = viewModel::pauseFaceChallenge,
                    onResumeChallenge = viewModel::resumeFaceChallenge,
                    onQuitChallenge = viewModel::quitFaceChallenge,
                    onRetryChallenge = viewModel::retryFaceChallenge,
                )
                RecognitionDomain.GESTURE -> GestureCompactControlRow(
                    selected = state.gestureMode,
                    onSelected = viewModel::setGestureMode,
                    backgroundControl = backgroundControl,
                    onBackgroundControlChanged = onBackgroundControlChanged,
                )
            }
            RecognitionInfoBar(state)
        }

    }

    state.error?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::clearError,
            icon = { Icon(Icons.Outlined.ErrorOutline, contentDescription = null) },
            title = { Text("识别暂不可用") },
            text = { Text(message) },
            confirmButton = {
                Button(onClick = {
                    viewModel.stopRecognizer()
                    viewModel.clearError()
                    viewModel.ensureRecognizer()
                }, shape = MaterialTheme.shapes.medium) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("重试")
                }
            },
        )
    }

    if (showAccessibilityDialog) {
        AlertDialog(
            onDismissRequest = {
                showAccessibilityDialog = false
                pendingBackgroundStart = false
            },
            title = { Text("先启用无障碍服务") },
            text = {
                Text(
                    "全局控制需要系统无障碍服务来执行滑动和缩放。只会注入已确认的手势，不读取其他应用内容。",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showAccessibilityDialog = false
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text("去设置")
                }
            },
            dismissButton = {
                Button(
                    onClick = {
                        showAccessibilityDialog = false
                        pendingBackgroundStart = false
                    },
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text("暂不")
                }
            },
        )
    }
}

@Composable
private fun FaceCompactControlRow(
    state: RecognitionUiState,
    isPortrait: Boolean,
    onModeSelected: (com.oppovisual.app.ui.face.FaceExperienceMode) -> Unit,
    onHeadgearSelected: (com.oppovisual.app.ui.face.HeadgearId) -> Unit,
    onStartChallenge: () -> Unit,
    onPauseChallenge: () -> Unit,
    onResumeChallenge: () -> Unit,
    onQuitChallenge: () -> Unit,
    onRetryChallenge: () -> Unit,
) {
    val panelHeight = 48.dp
    Surface(
        modifier = Modifier.fillMaxWidth().height(panelHeight),
        color = CAMERA_CHROME.copy(alpha = 0.98f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FaceModeSelector(
                selected = state.faceExperienceMode,
                onSelected = onModeSelected,
                width = if (isPortrait) 176.dp else 168.dp,
            )
            FaceExperienceActionRow(
                state = state,
                onHeadgearSelected = onHeadgearSelected,
                onStartChallenge = onStartChallenge,
                onPauseChallenge = onPauseChallenge,
                onResumeChallenge = onResumeChallenge,
                onQuitChallenge = onQuitChallenge,
                onRetryChallenge = onRetryChallenge,
                modifier = Modifier.width(if (isPortrait) 152.dp else 144.dp),
                expandStartButton = true,
            )
        }
    }
}

@Composable
private fun RecognitionModeControls(
    selectedDomain: RecognitionDomain,
    isPortrait: Boolean,
    onDomainSelected: (RecognitionDomain) -> Unit,
    modifier: Modifier = Modifier,
) {
    RecognitionDomainSelector(
        selected = selectedDomain,
        onSelected = onDomainSelected,
        width = if (isPortrait) 224.dp else 208.dp,
        modifier = modifier,
    )
}

@Composable
private fun GestureCompactControlRow(
    selected: RecognitionMode,
    onSelected: (RecognitionMode) -> Unit,
    backgroundControl: BackgroundControlState,
    onBackgroundControlChanged: (Boolean) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().height(48.dp),
        color = CAMERA_CHROME.copy(alpha = 0.98f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                val entries = listOf(
                    Triple("自由", selected == RecognitionMode.DISPLAY && !backgroundControl.active, true),
                    Triple("交互", selected == RecognitionMode.INTERACTION && !backgroundControl.active, true),
                    Triple(
                        when (backgroundControl.phase) {
                            BackgroundControlPhase.STARTING -> "启动中"
                            BackgroundControlPhase.PAUSED -> "已暂停"
                            else -> "全局控制"
                        },
                        backgroundControl.active,
                        backgroundControl.phase != BackgroundControlPhase.STARTING,
                    ),
                )
                entries.forEachIndexed { index, (label, isSelected, isEnabled) ->
                    SegmentedButton(
                        modifier = Modifier.weight(1f),
                        selected = isSelected,
                        enabled = isEnabled,
                        onClick = {
                            when (index) {
                                0 -> {
                                    if (backgroundControl.active) onBackgroundControlChanged(false)
                                    onSelected(RecognitionMode.DISPLAY)
                                }
                                1 -> {
                                    if (backgroundControl.active) onBackgroundControlChanged(false)
                                    onSelected(RecognitionMode.INTERACTION)
                                }
                                else -> {
                                    if (backgroundControl.active) {
                                        onBackgroundControlChanged(false)
                                    } else {
                                        onSelected(RecognitionMode.INTERACTION)
                                        onBackgroundControlChanged(true)
                                    }
                                }
                            }
                        },
                        shape = SegmentedButtonDefaults.itemShape(index, entries.size),
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = CAMERA_ACCENT,
                            activeContentColor = CAMERA_CHROME,
                            inactiveContainerColor = CAMERA_CHROME.copy(alpha = 0.84f),
                            inactiveContentColor = Color.White,
                            disabledActiveContainerColor = CAMERA_ACCENT.copy(alpha = 0.72f),
                            disabledActiveContentColor = CAMERA_CHROME.copy(alpha = 0.72f),
                        ),
                        label = { Text(label, maxLines = 1) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RecognitionDomainSelector(
    selected: RecognitionDomain,
    onSelected: (RecognitionDomain) -> Unit,
    width: androidx.compose.ui.unit.Dp = 224.dp,
    modifier: Modifier = Modifier,
) {
    SingleChoiceSegmentedButtonRow(modifier = modifier.width(width)) {
        RecognitionDomain.entries.forEachIndexed { index, domain ->
            SegmentedButton(
                selected = selected == domain,
                onClick = { onSelected(domain) },
                shape = SegmentedButtonDefaults.itemShape(index, RecognitionDomain.entries.size),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = CAMERA_ACCENT,
                    activeContentColor = CAMERA_CHROME,
                    inactiveContainerColor = CAMERA_CHROME.copy(alpha = 0.84f),
                    inactiveContentColor = Color.White,
                ),
                label = { Text(domain.displayName, maxLines = 1) },
            )
        }
    }
}

@Composable
private fun RecognitionInfoBar(state: RecognitionUiState, modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxWidth(), color = CAMERA_CHROME.copy(alpha = 0.96f)) {
        Row(
            modifier = Modifier
                .navigationBarsPadding()
                .heightIn(min = 84.dp)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                when (state.domain) {
                    RecognitionDomain.GESTURE -> {
                        Text(
                            when (state.gestureMode) {
                                RecognitionMode.DISPLAY -> state.gesture?.displayName
                                RecognitionMode.INTERACTION -> if (state.scaleStatus != ProductScaleStatus.IDLE) {
                                    GestureId.TWO_HAND_ZOOM.displayName
                                } else {
                                    state.lastEvent?.displayName
                                }
                            } ?: "等待手势",
                            style = MaterialTheme.typography.headlineSmall,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            if (state.handPresent) {
                                "${state.handCount}只手 · ${"%.0f".format(state.confidence * 100)}%"
                            } else {
                                "未检测到手部"
                            },
                            color = Color.White.copy(alpha = 0.68f),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    RecognitionDomain.FACE -> if (state.faceExperienceMode == com.oppovisual.app.ui.face.FaceExperienceMode.FREE) {
                        FaceStatusSummary(state)
                    } else {
                        Text("表情挑战", style = MaterialTheme.typography.headlineSmall, color = Color.White)
                    }
                }
            }
            Spacer(Modifier.width(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                RecognitionMetric(
                    label = "端到端",
                    value = state.endToEndLatencyMs?.let { "$it ms" } ?: "--",
                )
                RecognitionMetric(
                    label = "模型",
                    value = state.modelLatencyMs?.let { "$it ms" } ?: "--",
                )
                RecognitionMetric(
                    label = "帧率",
                    value = if (state.fps > 0f) "${"%.1f".format(state.fps)} FPS" else "--",
                )
            }
        }
    }
}

@Composable
private fun FaceStatusSummary(state: RecognitionUiState) {
    Text(
        if (state.facePresent) state.expression.displayName else "等待面部",
        style = MaterialTheme.typography.headlineSmall,
        color = Color.White,
    )
    val status = when {
        !state.facePresent -> "未检测到面部"
        !state.headCalibrated -> "正在校准中立姿态"
        else -> "表情 ${"%.0f".format(state.expressionConfidence * 100)}% · 头部${state.headDirection.displayName}"
    }
    Text(status, color = Color.White.copy(alpha = 0.68f), style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun RecognitionMetric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = Color.White.copy(alpha = 0.55f), style = MaterialTheme.typography.labelSmall)
        Text(value, color = CAMERA_ACCENT, style = MaterialTheme.typography.titleSmall, maxLines = 1)
    }
}

@Composable
private fun LowLightBanner() {
    Surface(color = CAMERA_CHROME.copy(alpha = 0.84f), shape = MaterialTheme.shapes.medium) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.LightMode, contentDescription = null, tint = CAMERA_ACCENT)
            Spacer(Modifier.width(8.dp))
            Text("环境光线不足", color = Color.White, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun DiagnosticsOverlay(state: RecognitionUiState) {
    val latency = state.latency
    Surface(color = CAMERA_CHROME.copy(alpha = 0.84f), shape = MaterialTheme.shapes.medium) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${"%.1f".format(state.fps)} FPS", color = Color.White)
                VerticalDivider(
                    modifier = Modifier.padding(horizontal = 10.dp).height(18.dp),
                    color = Color.White.copy(alpha = 0.2f),
                )
                Text("P95 ${latency?.p95Ms ?: 0} ms", color = CAMERA_ACCENT)
            }
            if (state.domain == RecognitionDomain.FACE) {
                state.headPose?.let { pose ->
                    Text(
                        "Yaw ${"%.1f".format(pose.yawDegrees)}°  Pitch ${"%.1f".format(pose.pitchDegrees)}°",
                        color = Color.White.copy(alpha = 0.68f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                state.blendshapes.take(3).forEach { shape ->
                    Text(
                        "${shape.name}  ${"%.2f".format(shape.score)}",
                        color = Color.White.copy(alpha = 0.68f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun LandmarkOverlay(
    landmarks: List<Point3>,
    inputWidth: Int,
    inputHeight: Int,
    colorIndex: Int,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val scale = min(size.width / inputWidth, size.height / inputHeight)
        val offsetX = (size.width - inputWidth * scale) / 2f
        val offsetY = (size.height - inputHeight * scale) / 2f
        fun mapped(index: Int): Offset {
            val point = landmarks[index]
            return Offset(
                offsetX + point.x * inputWidth * scale,
                offsetY + point.y * inputHeight * scale,
            )
        }
        HAND_CONNECTIONS.forEach { (start, end) ->
            val handColor = if (colorIndex % 2 == 0) CAMERA_ACCENT else SECOND_HAND_COLOR
            drawLine(
                color = handColor,
                start = mapped(start),
                end = mapped(end),
                strokeWidth = 2.5.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
        landmarks.indices.forEach { index ->
            val handColor = if (colorIndex % 2 == 0) CAMERA_ACCENT else SECOND_HAND_COLOR
            drawCircle(handColor, radius = 3.5.dp.toPx(), center = mapped(index))
        }
    }
}

@Composable
private fun FaceLandmarkOverlay(
    landmarks: List<Point3>,
    inputWidth: Int,
    inputHeight: Int,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val scale = min(size.width / inputWidth, size.height / inputHeight)
        val offsetX = (size.width - inputWidth * scale) / 2f
        val offsetY = (size.height - inputHeight * scale) / 2f
        fun mapped(index: Int): Offset {
            val point = landmarks[index]
            return Offset(
                offsetX + point.x * inputWidth * scale,
                offsetY + point.y * inputHeight * scale,
            )
        }
        FACE_CONTOURS.forEach { contour ->
            val valid = contour.filter { it in landmarks.indices }
            valid.zipWithNext().forEach { (start, end) ->
                drawLine(
                    color = CAMERA_ACCENT,
                    start = mapped(start),
                    end = mapped(end),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
            if (valid.size == contour.size && valid.size > 2) {
                drawLine(
                    color = CAMERA_ACCENT,
                    start = mapped(valid.last()),
                    end = mapped(valid.first()),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

private val HAND_CONNECTIONS = listOf(
    0 to 1, 1 to 2, 2 to 3, 3 to 4,
    0 to 5, 5 to 6, 6 to 7, 7 to 8,
    5 to 9, 9 to 10, 10 to 11, 11 to 12,
    9 to 13, 13 to 14, 14 to 15, 15 to 16,
    13 to 17, 17 to 18, 18 to 19, 19 to 20,
    0 to 17,
)

private val FACE_CONTOURS = listOf(
    listOf(10, 338, 297, 332, 284, 251, 389, 356, 454, 323, 361, 288, 397, 365, 379, 378, 400, 377, 152, 148, 176, 149, 150, 136, 172, 58, 132, 93, 234, 127, 162, 21, 54, 103, 67, 109),
    listOf(33, 160, 158, 133, 153, 144),
    listOf(362, 385, 387, 263, 373, 380),
    listOf(61, 40, 37, 0, 267, 270, 291, 321, 314, 17, 84, 91),
)

private val CAMERA_CHROME = Color(0xFF111719)
private val CAMERA_ACCENT = Color(0xFF66D8C6)
private val SECOND_HAND_COLOR = Color(0xFFFFB86B)

private class FeedbackController(context: Context) {
    private val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 45)
    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    fun play(sound: Boolean, haptics: Boolean) {
        if (sound) tone.startTone(ToneGenerator.TONE_PROP_ACK, 90)
        if (haptics) vibrator.vibrate(VibrationEffect.createOneShot(45, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    fun close() = tone.release()
}
