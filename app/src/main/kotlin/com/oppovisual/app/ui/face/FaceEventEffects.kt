package com.oppovisual.app.ui.face

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.sp
import com.oppovisual.app.ui.RecognitionUiState
import com.oppovisual.app.ui.theme.OppoScoreFont
import com.oppovisual.core.ExpressionId
import com.oppovisual.core.HeadMotionId
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

private val EffectMint = Color(0xFF7CFFD9)
private val EffectPink = Color(0xFFFF4F9A)
private val EffectYellow = Color(0xFFFFDF5D)
private val EffectInk = Color(0xFF101719)

@Composable
fun FaceEventEffects(
    state: RecognitionUiState,
    virtualFaceLayout: FaceHeadgearLayout?,
    effectsEnabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val expressionProgress = remember { Animatable(1f) }
    val headProgress = remember { Animatable(1f) }
    var expressionEffect by remember { mutableStateOf<ExpressionId?>(null) }
    var headEffect by remember { mutableStateOf<HeadMotionId?>(null) }
    val textMeasurer = rememberTextMeasurer()
    val bubblePhase = (state.lastFaceFrameTimestampMs % PUCKER_BUBBLE_CYCLE_MS).toFloat() /
        PUCKER_BUBBLE_CYCLE_MS.toFloat()

    LaunchedEffect(state.expressionEventSequence) {
        val event = state.lastExpressionEvent
        expressionProgress.stop()
        expressionProgress.snapTo(1f)
        expressionEffect = null
        if (effectsEnabled && state.expressionEventSequence > 0 && event in EFFECT_EXPRESSIONS) {
            expressionEffect = event
            expressionProgress.snapTo(0f)
            try {
                expressionProgress.animateTo(1f, tween(520, easing = FastOutSlowInEasing))
            } finally {
                withContext(NonCancellable) {
                    expressionProgress.snapTo(1f)
                    expressionEffect = null
                }
            }
        }
    }
    LaunchedEffect(state.headMotionEventSequence) {
        val event = state.lastHeadMotion
        headProgress.stop()
        headProgress.snapTo(1f)
        headEffect = null
        if (effectsEnabled && state.headMotionEventSequence > 0 && event != null) {
            headEffect = event
            headProgress.snapTo(0f)
            try {
                headProgress.animateTo(1f, tween(620, easing = FastOutSlowInEasing))
            } finally {
                withContext(NonCancellable) {
                    headProgress.snapTo(1f)
                    headEffect = null
                }
            }
        }
    }

    Canvas(modifier.testTag("face_event_effects")) {
        val layout = virtualFaceLayout ?: return@Canvas
        if (effectsEnabled) {
            drawExpressionEffect(expressionEffect, expressionProgress.value, layout, 1f, textMeasurer)
        }
        drawContinuousPuckerEffect(
            active = effectsEnabled && state.expression == ExpressionId.MOUTH_PUCKER,
            strength = puckerEffectStrength(state.expressionScores[ExpressionId.MOUTH_PUCKER] ?: 0f),
            phase = bubblePhase,
            layout = layout,
            anchorAlpha = 1f,
        )
        if (effectsEnabled) {
            drawHeadEffect(headEffect, headProgress.value, layout, 1f, textMeasurer)
        }
    }
}

private fun DrawScope.drawExpressionEffect(
    expression: ExpressionId?,
    progress: Float,
    layout: FaceHeadgearLayout,
    anchorAlpha: Float,
    textMeasurer: TextMeasurer,
) {
    if (expression == null || progress >= 1f) return
    val fade = effectFade(progress) * anchorAlpha
    val faceWidth = layout.faceWidthPx
    val faceHeight = layout.faceHeightPx

    when (expression) {
        ExpressionId.SMILE -> {
            drawSmileCheekFlash(layout, progress, fade)
        }
        ExpressionId.MOUTH_OPEN -> Unit
        ExpressionId.MOUTH_PUCKER -> Unit
        ExpressionId.BROW_RAISE -> drawFashionLabel(
            label = "!",
            center = Offset(layout.eyeCenterX + faceWidth * 0.27f, layout.eyeCenterY - faceHeight * 0.43f),
            progress = progress,
            alpha = anchorAlpha,
            fill = EffectYellow,
            rotation = -8f,
            textMeasurer = textMeasurer,
        )
        ExpressionId.LEFT_WINK -> drawFourPointStar(
            // The closed eye is on the user's left; place the effect on the
            // open right eye so it remains visible instead of being occluded.
            center = Offset(layout.eyeCenterX + layout.eyeDistancePx * 0.52f, layout.eyeCenterY),
            radius = faceWidth * 0.10f,
            color = EffectPink,
            alpha = fade,
            rotationDegrees = 10f - progress * 24f,
        )
        ExpressionId.RIGHT_WINK -> drawFourPointStar(
            // The closed eye is on the user's right; place the effect on the
            // open left eye.
            center = Offset(layout.eyeCenterX - layout.eyeDistancePx * 0.52f, layout.eyeCenterY),
            radius = faceWidth * 0.10f,
            color = EffectMint,
            alpha = fade,
            rotationDegrees = -10f + progress * 24f,
        )
        else -> Unit
    }
}

private fun DrawScope.drawHeadEffect(
    motion: HeadMotionId?,
    progress: Float,
    layout: FaceHeadgearLayout,
    anchorAlpha: Float,
    textMeasurer: TextMeasurer,
) {
    if (motion == null || progress >= 1f) return
    val fade = effectFade(progress) * anchorAlpha
    val faceWidth = layout.faceWidthPx
    val faceHeight = layout.faceHeightPx
    when (motion) {
        HeadMotionId.NOD -> drawFashionLabel(
            label = "YES",
            center = Offset(layout.eyeCenterX, layout.eyeCenterY - faceHeight * 0.48f),
            progress = progress,
            alpha = anchorAlpha,
            fill = EffectMint,
            rotation = -6f,
            textMeasurer = textMeasurer,
        )
        HeadMotionId.SHAKE -> drawFashionLabel(
            label = "NO",
            center = Offset(layout.eyeCenterX, layout.eyeCenterY - faceHeight * 0.48f),
            progress = progress,
            alpha = anchorAlpha,
            fill = EffectPink,
            rotation = 7f,
            textMeasurer = textMeasurer,
        )
        HeadMotionId.TURN_LEFT,
        HeadMotionId.TURN_RIGHT,
        -> drawTurnArrow(motion, layout, progress, fade)
    }
}

private fun DrawScope.drawSmileCheekFlash(
    layout: FaceHeadgearLayout,
    progress: Float,
    alpha: Float,
) {
    val faceWidth = layout.faceWidthPx
    val faceHeight = layout.faceHeightPx
    val bloom = (progress / 0.24f).coerceIn(0f, 1f)
    listOf(-1f to EffectPink, 1f to EffectMint).forEach { (side, color) ->
        val cheek = virtualFeaturePoint(
            layout = layout,
            alongEyeAxis = side * layout.eyeDistancePx * 0.62f,
            belowEyes = faceHeight * 0.20f,
        )
        rotate(layout.eyeRotationDegrees, pivot = cheek) {
            drawOval(
                color = color.copy(alpha = alpha * 0.18f),
                topLeft = Offset(cheek.x - faceWidth * 0.075f, cheek.y - faceHeight * 0.032f),
                size = Size(faceWidth * 0.15f, faceHeight * 0.064f),
            )
            repeat(2) { index ->
                val offset = (index - 0.5f) * faceWidth * 0.046f
                drawLine(
                    color = Color.White.copy(alpha = alpha * (0.92f - index * 0.18f)),
                    start = Offset(cheek.x + offset - side * faceWidth * 0.016f, cheek.y + faceHeight * 0.014f),
                    end = Offset(cheek.x + offset + side * faceWidth * 0.014f, cheek.y - faceHeight * 0.019f * bloom),
                    strokeWidth = max(2.5f, faceWidth * 0.010f),
                    cap = StrokeCap.Round,
                )
            }
            drawFourPointStar(
                center = Offset(cheek.x + side * faceWidth * 0.092f, cheek.y - faceHeight * 0.070f),
                radius = faceWidth * 0.036f * (0.72f + bloom * 0.28f),
                color = color,
                alpha = alpha,
                rotationDegrees = side * 12f,
            )
        }
    }
}

private fun DrawScope.drawContinuousPuckerEffect(
    active: Boolean,
    strength: Float,
    phase: Float,
    layout: FaceHeadgearLayout,
    anchorAlpha: Float,
) {
    if (!active || strength <= 0f) return
    val faceWidth = layout.faceWidthPx
    val faceHeight = layout.faceHeightPx
    val mouth = Offset(layout.mouthCenterX, layout.mouthCenterY)
    repeat(6) { index ->
        val local = (phase + index * 0.165f) % 1f
        val horizontalSpread = stableBubbleNoise(index, 1)
        val verticalSpread = stableBubbleNoise(index, 2)
        val sizeSpread = stableBubbleNoise(index, 3)
        val wave = local * Math.PI * 2f + index * 1.45f
        val wobbleX = sin(wave).toFloat() * faceWidth * (0.010f + horizontalSpread * 0.020f) * local
        val wobbleY = sin(wave * 0.73f + index).toFloat() * faceHeight * 0.014f * local
        val travelX = faceWidth * (0.018f + local * (0.25f + horizontalSpread * 0.14f))
        val travelY = faceHeight * (0.010f + local * (0.13f + verticalSpread * 0.15f))
        val radius = faceWidth * (0.009f + local * (0.032f + sizeSpread * 0.018f)) *
            (0.78f + strength * 0.30f)
        val life = sin(local * Math.PI).toFloat().coerceAtLeast(0f)
        val bubbleAlpha = anchorAlpha * strength * life
        val center = Offset(mouth.x + travelX + wobbleX, mouth.y - travelY + wobbleY)
        val rimColor = when (index % 3) {
            0 -> EffectPink
            1 -> EffectMint
            else -> EffectYellow
        }
        drawCircle(
            color = rimColor.copy(alpha = bubbleAlpha * 0.16f),
            radius = radius,
            center = center,
        )
        drawCircle(
            color = Color.White.copy(alpha = bubbleAlpha * 0.82f),
            radius = radius,
            center = center,
            style = Stroke(width = max(2f, faceWidth * 0.007f)),
        )
        drawArc(
            color = rimColor.copy(alpha = bubbleAlpha * 0.92f),
            startAngle = 20f,
            sweepAngle = 108f,
            useCenter = false,
            topLeft = Offset(center.x - radius, center.y - radius),
            size = Size(radius * 2f, radius * 2f),
            style = Stroke(width = max(1.5f, faceWidth * 0.004f), cap = StrokeCap.Round),
        )
        drawCircle(
            color = Color.White.copy(alpha = bubbleAlpha * 0.90f),
            radius = radius * 0.12f,
            center = center + Offset(-radius * 0.34f, -radius * 0.36f),
        )
    }
}

private fun virtualFeaturePoint(
    layout: FaceHeadgearLayout,
    alongEyeAxis: Float,
    belowEyes: Float,
): Offset {
    val radians = Math.toRadians(layout.eyeRotationDegrees.toDouble())
    val rightX = cos(radians).toFloat()
    val rightY = sin(radians).toFloat()
    val downX = -rightY
    val downY = rightX
    return Offset(
        x = layout.eyeCenterX + rightX * alongEyeAxis + downX * belowEyes,
        y = layout.eyeCenterY + rightY * alongEyeAxis + downY * belowEyes,
    )
}

private fun DrawScope.drawTurnArrow(
    motion: HeadMotionId,
    layout: FaceHeadgearLayout,
    progress: Float,
    alpha: Float,
) {
    val direction = turnArrowDirection(motion)
    val faceWidth = layout.faceWidthPx
    val faceHeight = layout.faceHeightPx
    val enter = (progress / 0.24f).coerceIn(0f, 1f)
    val arrowCenter = Offset(
        layout.centerX + direction * faceWidth * (0.48f + enter * 0.13f),
        layout.centerY - faceHeight * 0.04f,
    )
    drawChevron(
        center = arrowCenter - Offset(direction * faceWidth * 0.085f, 0f),
        direction = direction,
        size = faceWidth * 0.080f,
        color = EffectMint,
        alpha = alpha * 0.56f,
    )
    drawChevron(
        center = arrowCenter + Offset(-direction * faceWidth * 0.035f, faceHeight * 0.018f),
        direction = direction,
        size = faceWidth * 0.095f,
        color = EffectPink,
        alpha = alpha * 0.74f,
    )
    drawChevron(
        center = arrowCenter,
        direction = direction,
        size = faceWidth * 0.095f,
        color = Color.White,
        alpha = alpha,
    )
}

internal fun turnArrowDirection(motion: HeadMotionId): Float = when (motion) {
    HeadMotionId.TURN_LEFT -> -1f
    HeadMotionId.TURN_RIGHT -> 1f
    else -> error("Only turn events have an arrow direction")
}

private fun DrawScope.drawChevron(
    center: Offset,
    direction: Float,
    size: Float,
    color: Color,
    alpha: Float,
) {
    val tip = Offset(center.x + direction * size * 0.55f, center.y)
    val backX = center.x - direction * size * 0.45f
    drawLine(
        color = color.copy(alpha = alpha),
        start = Offset(backX, center.y - size * 0.55f),
        end = tip,
        strokeWidth = max(3f, size * 0.17f),
        cap = StrokeCap.Round,
    )
    drawLine(
        color = color.copy(alpha = alpha),
        start = tip,
        end = Offset(backX, center.y + size * 0.55f),
        strokeWidth = max(3f, size * 0.17f),
        cap = StrokeCap.Round,
    )
}

private fun DrawScope.drawFashionLabel(
    label: String,
    center: Offset,
    progress: Float,
    alpha: Float,
    fill: Color,
    rotation: Float,
    textMeasurer: TextMeasurer,
) {
    val fade = effectFade(progress) * alpha
    val entry = (progress / 0.22f).coerceIn(0f, 1f)
    val fontSize = if (label == "!") 82.sp else 58.sp
    val baseStyle = TextStyle(
        fontFamily = OppoScoreFont,
        fontWeight = FontWeight.Black,
        fontSize = fontSize,
    )
    val fillLayout = textMeasurer.measure(
        text = label,
        style = baseStyle.copy(color = Color.White.copy(alpha = fade)),
        skipCache = true,
    )
    val accentLayout = textMeasurer.measure(
        text = label,
        style = baseStyle.copy(color = fill.copy(alpha = fade)),
        skipCache = true,
    )
    val outlineLayout = textMeasurer.measure(
        text = label,
        style = baseStyle.copy(
            color = EffectInk.copy(alpha = fade),
            drawStyle = Stroke(width = 9f, join = androidx.compose.ui.graphics.StrokeJoin.Round),
        ),
        skipCache = true,
    )
    val rise = fillLayout.size.height * (0.10f + progress * 0.30f)
    val topLeft = Offset(
        center.x - fillLayout.size.width / 2f,
        center.y - fillLayout.size.height / 2f - rise,
    )
    rotate(rotation * entry, pivot = center) {
        drawText(
            textLayoutResult = accentLayout,
            topLeft = topLeft + Offset(9f, 10f),
        )
        drawText(
            textLayoutResult = outlineLayout,
            topLeft = topLeft,
        )
        drawText(
            textLayoutResult = fillLayout,
            topLeft = topLeft,
        )
    }
}

private fun DrawScope.drawFourPointStar(
    center: Offset,
    radius: Float,
    color: Color,
    alpha: Float,
    rotationDegrees: Float = 0f,
) {
    val path = Path().apply {
        moveTo(center.x, center.y - radius)
        lineTo(center.x + radius * 0.18f, center.y - radius * 0.18f)
        lineTo(center.x + radius, center.y)
        lineTo(center.x + radius * 0.18f, center.y + radius * 0.18f)
        lineTo(center.x, center.y + radius)
        lineTo(center.x - radius * 0.18f, center.y + radius * 0.18f)
        lineTo(center.x - radius, center.y)
        lineTo(center.x - radius * 0.18f, center.y - radius * 0.18f)
        close()
    }
    rotate(rotationDegrees, pivot = center) {
        drawPath(path, color = color.copy(alpha = alpha))
        drawPath(
            path = path,
            color = EffectInk.copy(alpha = alpha * 0.88f),
            style = Stroke(width = max(3f, radius * 0.13f), join = androidx.compose.ui.graphics.StrokeJoin.Round),
        )
        drawCircle(Color.White.copy(alpha = alpha), radius * 0.16f, center)
    }
}

private fun effectFade(progress: Float): Float = when {
    progress < 0.16f -> progress / 0.16f
    progress < 0.66f -> 1f
    else -> ((1f - progress) / 0.34f).coerceIn(0f, 1f)
}

internal fun puckerEffectStrength(score: Float): Float =
    ((score - 0.40f) / 0.35f).coerceIn(0f, 1f)

private fun stableBubbleNoise(index: Int, salt: Int): Float {
    val mixed = ((index + 1) * 1_103 + salt * 2_053) xor ((index + salt + 7) * 97)
    return (mixed and 0x3ff) / 1023f
}

private val EFFECT_EXPRESSIONS = setOf(
    ExpressionId.SMILE,
    ExpressionId.MOUTH_PUCKER,
    ExpressionId.BROW_RAISE,
    ExpressionId.LEFT_WINK,
    ExpressionId.RIGHT_WINK,
)

private const val PUCKER_BUBBLE_CYCLE_MS = 1_250L
