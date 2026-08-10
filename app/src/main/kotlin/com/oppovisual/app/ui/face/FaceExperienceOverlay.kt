package com.oppovisual.app.ui.face

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.oppovisual.app.R
import com.oppovisual.app.ui.RecognitionUiState
import com.oppovisual.app.ui.theme.OppoScoreFont
import com.oppovisual.core.ExpressionId
import kotlin.math.ceil
import kotlin.math.abs
import kotlin.math.min

private val FaceChrome = Color(0xFF101719)
private val FaceAccent = Color(0xFF67E4CF)
private val ChallengeGold = Color(0xFFFFD45C)

@Composable
fun FaceExperienceOverlay(
    state: RecognitionUiState,
    onModeSelected: (FaceExperienceMode) -> Unit,
    onHeadgearSelected: (HeadgearId) -> Unit,
    onStartChallenge: () -> Unit,
    onPauseChallenge: () -> Unit,
    onResumeChallenge: () -> Unit,
    onQuitChallenge: () -> Unit,
    onRetryChallenge: () -> Unit,
    modifier: Modifier = Modifier,
    enableCat3d: Boolean = true,
    contentTopPadding: Dp = 172.dp,
    showModeSelector: Boolean = true,
    modeSelectorAtBottom: Boolean = false,
    controlsInExternalPanel: Boolean = false,
) {
    BoxWithConstraints(modifier.fillMaxSize().testTag("face_experience_root")) {
        var virtualFaceLayout by remember { mutableStateOf<FaceHeadgearLayout?>(null) }
        var effectsBlockedUntilMs by remember { mutableLongStateOf(0L) }
        LaunchedEffect(state.selectedHeadgear) {
            if (state.selectedHeadgear != HeadgearId.CAT) {
                virtualFaceLayout = null
            }
            effectsBlockedUntilMs = if (state.selectedHeadgear == HeadgearId.CAT) {
                SystemClock.uptimeMillis() + AVATAR_EFFECT_START_DELAY_MS
            } else {
                0L
            }
        }
        LaunchedEffect(state.facePresent) {
            if (state.selectedHeadgear == HeadgearId.CAT && state.facePresent) {
                effectsBlockedUntilMs = SystemClock.uptimeMillis() + AVATAR_EFFECT_START_DELAY_MS
            }
        }
        val isLandscape = maxWidth > maxHeight
        val cameraModifier = if (maxWidth <= maxHeight) {
            Modifier.align(Alignment.Center).fillMaxWidth().aspectRatio(3f / 4f)
        } else {
            Modifier.align(Alignment.Center).fillMaxHeight().aspectRatio(4f / 3f)
        }

        FaceHeadgearLayer(
            state = state,
            modifier = cameraModifier,
            enableCat3d = enableCat3d,
            onVirtualFaceLayoutChanged = { virtualFaceLayout = it },
        )

        if (state.faceExperienceMode == FaceExperienceMode.FREE) {
            if (state.selectedHeadgear == HeadgearId.CAT) {
                FaceEventEffects(
                    state = state,
                    virtualFaceLayout = virtualFaceLayout,
                    effectsEnabled = SystemClock.uptimeMillis() >= effectsBlockedUntilMs,
                    modifier = cameraModifier,
                )
            }
            AnimatedVisibility(
                visible = state.selectedHeadgear == HeadgearId.CAT &&
                    state.facePresent && abs(state.headPose?.yawDegrees ?: 0f) >= 50f,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = contentTopPadding + 8.dp),
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut(),
            ) {
                Surface(color = FaceChrome.copy(alpha = 0.86f), shape = MaterialTheme.shapes.medium) {
                    Text("请正对镜头", modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp), color = Color.White)
                }
            }
            if (modeSelectorAtBottom && !controlsInExternalPanel) {
                FaceModeSelector(
                    selected = state.faceExperienceMode,
                    onSelected = onModeSelected,
                    modifier = bottomModeSelectorModifier(),
                )
                FreeModeControls(
                    selected = state.selectedHeadgear,
                    onSelected = onHeadgearSelected,
                    vertical = false,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 158.dp),
                )
            } else if (!controlsInExternalPanel) {
                FreeModeControls(
                    selected = state.selectedHeadgear,
                    onSelected = onHeadgearSelected,
                    vertical = isLandscape,
                    modifier = if (isLandscape) {
                        Modifier.align(Alignment.CenterEnd).navigationBarsPadding().padding(end = 16.dp)
                    } else {
                        Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 18.dp)
                    },
                )
            }
        } else {
            ChallengeThemeFrame(cameraModifier)
            ChallengeExperience(
                state = state.challenge,
                onStart = onStartChallenge,
                onPause = onPauseChallenge,
                onResume = onResumeChallenge,
                onQuit = onQuitChallenge,
                onRetry = onRetryChallenge,
                contentTopPadding = contentTopPadding,
                showControls = !controlsInExternalPanel,
                modifier = Modifier.fillMaxSize(),
            )
            if (modeSelectorAtBottom && !controlsInExternalPanel) {
                FaceModeSelector(
                    selected = state.faceExperienceMode,
                    onSelected = onModeSelected,
                    modifier = bottomModeSelectorModifier(),
                )
                FreeModeControls(
                    selected = state.selectedHeadgear,
                    onSelected = onHeadgearSelected,
                    vertical = false,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 158.dp),
                )
            } else if (!controlsInExternalPanel) {
                FreeModeControls(
                    selected = state.selectedHeadgear,
                    onSelected = onHeadgearSelected,
                    vertical = isLandscape,
                    modifier = if (isLandscape) {
                        Modifier.align(Alignment.CenterEnd).navigationBarsPadding().padding(end = 16.dp)
                    } else {
                        Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 18.dp)
                    },
                )
            }
        }

        if (showModeSelector && !modeSelectorAtBottom && !controlsInExternalPanel) {
            FaceModeSelector(
                selected = state.faceExperienceMode,
                onSelected = onModeSelected,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 116.dp),
            )
        }
    }
}

private const val AVATAR_EFFECT_START_DELAY_MS = 500L

private fun BoxScope.bottomModeSelectorModifier(): Modifier = Modifier
        .align(Alignment.BottomCenter)
        .navigationBarsPadding()
        .padding(start = 16.dp, top = 0.dp, end = 16.dp, bottom = 100.dp)

@Composable
private fun FaceStatusPanel(state: RecognitionUiState, modifier: Modifier = Modifier) {
    val expression = if (state.facePresent) state.expression.displayName else "未检测到"
    val recentMotion = state.lastHeadMotion?.takeIf {
        state.lastFaceFrameTimestampMs >= state.lastHeadMotionTimestampMs &&
            state.lastFaceFrameTimestampMs - state.lastHeadMotionTimestampMs <= HEAD_EVENT_STATUS_MS
    }
    val direction = when {
        !state.facePresent -> "未检测到"
        !state.headCalibrated -> "校准中"
        recentMotion != null -> recentMotion.displayName
        else -> state.headDirection.displayName
    }
    Surface(
        modifier = modifier.testTag("face_status_panel"),
        color = FaceChrome.copy(alpha = 0.88f),
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FaceStatusValue("表情", expression)
            FaceStatusValue("头动", direction)
        }
    }
}

private const val HEAD_EVENT_STATUS_MS = 900L

@Composable
private fun FaceStatusValue(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp)
        Text(value, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
fun FaceModeSelector(
    selected: FaceExperienceMode,
    onSelected: (FaceExperienceMode) -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 188.dp,
) {
    SingleChoiceSegmentedButtonRow(modifier = modifier.width(width)) {
        FaceExperienceMode.entries.forEachIndexed { index, mode ->
            SegmentedButton(
                modifier = Modifier.testTag("face_mode_${mode.name}"),
                selected = selected == mode,
                onClick = { onSelected(mode) },
                shape = SegmentedButtonDefaults.itemShape(index, FaceExperienceMode.entries.size),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = FaceAccent,
                    activeContentColor = FaceChrome,
                    inactiveContainerColor = FaceChrome.copy(alpha = 0.82f),
                    inactiveContentColor = Color.White,
                ),
                label = { Text(mode.displayName, maxLines = 1) },
            )
        }
    }
}

/** Controls rendered below the camera viewport so they never cover the face. */
@Composable
fun FaceExperienceControlPanel(
    state: RecognitionUiState,
    onModeSelected: (FaceExperienceMode) -> Unit,
    onHeadgearSelected: (HeadgearId) -> Unit,
    onStartChallenge: () -> Unit,
    onPauseChallenge: () -> Unit,
    onResumeChallenge: () -> Unit,
    onQuitChallenge: () -> Unit,
    onRetryChallenge: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(96.dp),
        color = FaceChrome.copy(alpha = 0.96f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FaceModeSelector(
                selected = state.faceExperienceMode,
                onSelected = onModeSelected,
                width = 168.dp,
            )
            FaceExperienceActionRow(
                state = state,
                onHeadgearSelected = onHeadgearSelected,
                onStartChallenge = onStartChallenge,
                onPauseChallenge = onPauseChallenge,
                onResumeChallenge = onResumeChallenge,
                onQuitChallenge = onQuitChallenge,
                onRetryChallenge = onRetryChallenge,
            )
        }
    }
}

@Composable
fun FaceExperienceActionRow(
    state: RecognitionUiState,
    onHeadgearSelected: (HeadgearId) -> Unit,
    onStartChallenge: () -> Unit,
    onPauseChallenge: () -> Unit,
    onResumeChallenge: () -> Unit,
    onQuitChallenge: () -> Unit,
    onRetryChallenge: () -> Unit,
    modifier: Modifier = Modifier,
    expandStartButton: Boolean = false,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state.faceExperienceMode == FaceExperienceMode.CHALLENGE) {
            val nextHeadgear = if (state.selectedHeadgear == HeadgearId.CAT) HeadgearId.OFF else HeadgearId.CAT
            HeadgearButton(nextHeadgear, state.selectedHeadgear, onHeadgearSelected)
        }
        when (state.faceExperienceMode) {
            FaceExperienceMode.FREE -> HeadgearId.entries.forEach { headgear ->
                HeadgearButton(headgear, state.selectedHeadgear, onHeadgearSelected)
            }
            FaceExperienceMode.CHALLENGE -> when (state.challenge.phase) {
                ChallengePhase.READY -> Button(
                    onClick = onStartChallenge,
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("challenge_start_external")
                        .then(if (expandStartButton) Modifier.weight(1f) else Modifier),
                ) { Text("开始") }
                ChallengePhase.PAUSED -> {
                    FilledIconButton(
                        onClick = onResumeChallenge,
                        modifier = Modifier.testTag("challenge_resume_external"),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = FaceAccent.copy(alpha = 0.18f)),
                    ) { Icon(Icons.Outlined.PlayArrow, contentDescription = "继续", tint = FaceAccent) }
                    FilledIconButton(
                        onClick = onQuitChallenge,
                        modifier = Modifier.testTag("challenge_paused_exit_external"),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.White.copy(alpha = 0.10f)),
                    ) { Icon(Icons.Outlined.Close, contentDescription = "退出", tint = Color.White) }
                }
                ChallengePhase.RESULT -> {
                    FilledIconButton(
                        onClick = onRetryChallenge,
                        modifier = Modifier.testTag("challenge_retry_external"),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = FaceAccent.copy(alpha = 0.18f)),
                    ) { Icon(Icons.Outlined.Refresh, contentDescription = "再来一次", tint = FaceAccent) }
                    FilledIconButton(
                        onClick = onQuitChallenge,
                        modifier = Modifier.testTag("challenge_result_exit_external"),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.White.copy(alpha = 0.10f)),
                    ) { Icon(Icons.Outlined.Close, contentDescription = "退出", tint = Color.White) }
                }
                else -> {
                    FilledIconButton(
                        onClick = onPauseChallenge,
                        modifier = Modifier.testTag("challenge_pause_external"),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = FaceAccent.copy(alpha = 0.18f)),
                    ) { Icon(Icons.Outlined.Pause, contentDescription = "暂停", tint = FaceAccent) }
                    FilledIconButton(
                        onClick = onQuitChallenge,
                        modifier = Modifier.testTag("challenge_exit_external"),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.White.copy(alpha = 0.10f)),
                    ) { Icon(Icons.Outlined.Close, contentDescription = "退出", tint = Color.White) }
                }
            }
            }
        }
    }

@Composable
private fun FaceHeadgearLayer(
    state: RecognitionUiState,
    modifier: Modifier,
    enableCat3d: Boolean,
    onVirtualFaceLayoutChanged: (FaceHeadgearLayout?) -> Unit,
) {
    Box(modifier) {
        if (state.selectedHeadgear == HeadgearId.CAT && enableCat3d) {
            ElderSpriteMocapOverlay(
                state = state,
                modifier = Modifier.fillMaxSize(),
                onVirtualFaceLayoutChanged = onVirtualFaceLayoutChanged,
            )
        }
    }
}

@Composable
private fun ChallengeThemeFrame(modifier: Modifier) {
    Canvas(modifier) {
        val inset = 12.dp.toPx()
        val segment = min(size.width, size.height) * 0.16f
        val stroke = 3.dp.toPx()
        val color = ChallengeGold.copy(alpha = 0.78f)
        drawLine(color, Offset(inset, inset), Offset(inset + segment, inset), stroke, StrokeCap.Round)
        drawLine(color, Offset(inset, inset), Offset(inset, inset + segment), stroke, StrokeCap.Round)
        drawLine(color, Offset(size.width - inset, inset), Offset(size.width - inset - segment, inset), stroke, StrokeCap.Round)
        drawLine(color, Offset(size.width - inset, inset), Offset(size.width - inset, inset + segment), stroke, StrokeCap.Round)
        drawLine(color, Offset(inset, size.height - inset), Offset(inset + segment, size.height - inset), stroke, StrokeCap.Round)
        drawLine(color, Offset(inset, size.height - inset), Offset(inset, size.height - inset - segment), stroke, StrokeCap.Round)
        drawLine(color, Offset(size.width - inset, size.height - inset), Offset(size.width - inset - segment, size.height - inset), stroke, StrokeCap.Round)
        drawLine(color, Offset(size.width - inset, size.height - inset), Offset(size.width - inset, size.height - inset - segment), stroke, StrokeCap.Round)
    }
}

@Composable
private fun FreeModeControls(
    selected: HeadgearId,
    onSelected: (HeadgearId) -> Unit,
    vertical: Boolean,
    modifier: Modifier,
) {
    Surface(
        modifier = modifier,
        color = FaceChrome.copy(alpha = 0.88f),
        shape = MaterialTheme.shapes.medium,
    ) {
        if (vertical) {
            Column(
                modifier = Modifier.padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HeadgearId.entries.forEach { headgear ->
                    HeadgearButton(headgear, selected, onSelected)
                }
            }
        } else {
            Row(
                modifier = Modifier.padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HeadgearId.entries.forEach { headgear ->
                    HeadgearButton(headgear, selected, onSelected)
                }
            }
        }
    }
}

@Composable
private fun HeadgearButton(
    headgear: HeadgearId,
    selected: HeadgearId,
    onSelected: (HeadgearId) -> Unit,
) {
    Surface(
        modifier = Modifier
            .size(48.dp)
            .testTag("headgear_${headgear.name}")
            .clickable { onSelected(headgear) },
        shape = MaterialTheme.shapes.small,
        color = if (headgear == selected) FaceAccent else Color.White.copy(alpha = 0.08f),
        contentColor = if (headgear == selected) FaceChrome else Color.White,
    ) {
        HeadgearThumbnail(headgear, Modifier.padding(3.dp))
    }
}

@Composable
private fun HeadgearThumbnail(headgear: HeadgearId, modifier: Modifier) {
    when (headgear) {
        HeadgearId.OFF -> Box(modifier, contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Outlined.VisibilityOff,
                contentDescription = headgear.contentDescription,
                modifier = Modifier.size(28.dp),
            )
        }
        HeadgearId.CAT -> Image(
            painter = painterResource(R.drawable.virtual_face_thumbnail),
            contentDescription = headgear.contentDescription,
            modifier = modifier,
            alignment = Alignment.TopCenter,
            contentScale = ContentScale.Crop,
        )
    }
}

@Composable
private fun ChallengeExperience(
    state: ChallengeUiState,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onQuit: () -> Unit,
    onRetry: () -> Unit,
    contentTopPadding: Dp,
    showControls: Boolean,
    modifier: Modifier,
) {
    Box(modifier) {
        if (showControls && state.phase !in setOf(ChallengePhase.READY, ChallengePhase.RESULT, ChallengePhase.PAUSED)) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = contentTopPadding + 8.dp, end = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledIconButton(
                    onClick = onPause,
                    modifier = Modifier.testTag("challenge_pause"),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = FaceChrome.copy(alpha = 0.84f)),
                ) { Icon(Icons.Outlined.Pause, contentDescription = "暂停", tint = Color.White) }
                FilledIconButton(
                    onClick = onQuit,
                    modifier = Modifier.testTag("challenge_exit"),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = FaceChrome.copy(alpha = 0.84f)),
                ) { Icon(Icons.Outlined.Close, contentDescription = "退出", tint = Color.White) }
            }
        }

        AnimatedContent(
            targetState = state.phase,
            modifier = Modifier.align(Alignment.Center),
            label = "challenge-phase",
        ) { phase ->
            when (phase) {
                ChallengePhase.COUNTDOWN -> CountdownText(state.remainingMs)
                ChallengePhase.PROMPT -> PromptPanel(state)
                ChallengePhase.SUCCESS -> FeedbackBadge("命中", FaceAccent, state.attempts.lastOrNull()?.points)
                ChallengePhase.TIMEOUT -> FeedbackBadge("超时", Color(0xFFFF8076), null)
                else -> Spacer(Modifier.size(1.dp))
            }
        }

        when {
            state.phase == ChallengePhase.RESULT -> ChallengeResult(
                state = state,
                onRetry = onRetry,
                onQuit = onQuit,
                showActions = showControls,
                modifier = Modifier.align(Alignment.Center),
            )
            showControls -> when (state.phase) {
                ChallengePhase.READY -> ChallengeReady(
                    onStart,
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 180.dp),
                )
                ChallengePhase.PAUSED -> ChallengePaused(onResume, onQuit, Modifier.align(Alignment.Center))
                else -> Unit
            }
        }
    }
}

@Composable
private fun CountdownText(remainingMs: Long) {
    val value = ceil(remainingMs / 1000.0).toInt().coerceAtLeast(1)
    Surface(color = FaceChrome.copy(alpha = 0.66f), shape = CircleShape) {
        Text(
            value.toString(),
            modifier = Modifier.padding(horizontal = 34.dp, vertical = 22.dp),
            color = ChallengeGold,
            fontSize = 72.sp,
            lineHeight = 72.sp,
            fontWeight = FontWeight.Black,
            fontFamily = OppoScoreFont,
        )
    }
}

@Composable
private fun PromptPanel(state: ChallengeUiState) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            "${state.roundIndex + 1} / ${state.sequence.size}",
            color = Color.White.copy(alpha = 0.72f),
            fontWeight = FontWeight.Bold,
        )
        Text(
            state.target?.displayName.orEmpty(),
            color = Color.White,
            fontSize = 44.sp,
            lineHeight = 48.sp,
            fontWeight = FontWeight.Black,
            fontFamily = OppoScoreFont,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.displaySmall.copy(
                shadow = androidx.compose.ui.graphics.Shadow(Color.Black, Offset(4f, 5f), 0f),
            ),
        )
        LinearProgressIndicator(
            progress = { (state.remainingMs / 2_500f).coerceIn(0f, 1f) },
            modifier = Modifier.width(220.dp).height(8.dp),
            color = ChallengeGold,
            trackColor = Color.White.copy(alpha = 0.18f),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Text("${state.score}", color = ChallengeGold, fontSize = 26.sp, fontWeight = FontWeight.Black, fontFamily = OppoScoreFont)
            Text("×${state.combo}", color = FaceAccent, fontSize = 26.sp, fontWeight = FontWeight.Black, fontFamily = OppoScoreFont)
        }
    }
}

@Composable
private fun FeedbackBadge(label: String, color: Color, points: Int?) {
    Surface(color = FaceChrome.copy(alpha = 0.72f), shape = MaterialTheme.shapes.medium) {
        Column(
            modifier = Modifier.padding(horizontal = 30.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(label, color = color, fontSize = 40.sp, fontWeight = FontWeight.Black)
            points?.let { Text("+$it", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun ChallengeReady(onStart: () -> Unit, modifier: Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = FaceChrome.copy(alpha = 0.94f),
    ) {
        Column(
            modifier = Modifier.navigationBarsPadding().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("表情挑战", color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
            Button(onClick = onStart, modifier = Modifier.fillMaxWidth().height(54.dp).testTag("challenge_start"), shape = MaterialTheme.shapes.medium) {
                Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("开始")
            }
        }
    }
}

@Composable
private fun ChallengePaused(onResume: () -> Unit, onQuit: () -> Unit, modifier: Modifier) {
    Surface(modifier, color = FaceChrome.copy(alpha = 0.94f), shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("已暂停", color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
            Button(onClick = onResume, modifier = Modifier.width(220.dp).testTag("challenge_resume")) {
                Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("继续")
            }
            OutlinedButton(onClick = onQuit, modifier = Modifier.width(220.dp).testTag("challenge_paused_exit")) {
                Icon(Icons.Outlined.Close, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("退出")
            }
        }
    }
}

@Composable
private fun ChallengeResult(
    state: ChallengeUiState,
    onRetry: () -> Unit,
    onQuit: () -> Unit,
    showActions: Boolean,
    modifier: Modifier,
) {
    Surface(
        modifier = modifier.testTag("challenge_result_summary"),
        color = FaceChrome.copy(alpha = 0.94f),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.padding(26.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("本局得分", color = Color.White.copy(alpha = 0.7f))
            Text(state.score.toString(), color = ChallengeGold, fontSize = 58.sp, lineHeight = 62.sp, fontWeight = FontWeight.Black, fontFamily = OppoScoreFont)
            Text("最佳 ${state.bestScore}  ·  最高连击 ${state.bestCombo}", color = Color.White)
            if (showActions) {
                Button(onClick = onRetry, modifier = Modifier.width(230.dp).testTag("challenge_retry")) {
                Icon(Icons.Outlined.Refresh, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("再来一次")
            }
            if (showActions) OutlinedButton(onClick = onQuit, modifier = Modifier.width(230.dp).testTag("challenge_result_exit")) {
                Icon(Icons.Outlined.Close, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("退出")
            }
        }
    }
}
}
