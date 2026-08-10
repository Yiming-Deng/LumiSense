package com.oppovisual.app.ui

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.oppovisual.app.ui.theme.OppoScoreFont
import com.oppovisual.core.GestureId
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

private val InteractionMint = Color(0xFF7CFFD9)
private val InteractionPink = Color(0xFFFF4F9A)
private val InteractionYellow = Color(0xFFFFDF5D)
private val InteractionInk = Color(0xFF101719)

/** Event-only feedback for interaction mode, matching the face/head motion language. */
@Composable
fun DynamicGestureEventEffects(
    state: RecognitionUiState,
    modifier: Modifier = Modifier,
) {
    val progress = remember { Animatable(1f) }
    val textMeasurer = rememberTextMeasurer()
    var effect by remember { mutableStateOf<GestureId?>(null) }

    LaunchedEffect(state.feedbackSequence) {
        val event = state.lastEvent
        progress.stop()
        progress.snapTo(1f)
        effect = null
        if (event == GestureId.TWO_HAND_ZOOM) return@LaunchedEffect
        if (state.feedbackSequence > 0 && event?.isDynamic == true) {
            effect = event
            progress.snapTo(0f)
            try {
                progress.animateTo(1f, tween(500, easing = FastOutSlowInEasing))
            } finally {
                withContext(NonCancellable) {
                    progress.snapTo(1f)
                    effect = null
                }
            }
        }
    }

    Canvas(modifier.testTag("dynamic_gesture_event_effects")) {
        val fixedCenter = Offset(size.width * 0.5f, size.height * 0.5f)
        val fixedRadius = min(size.width, size.height) * 0.13f
        state.activeScaleFactor?.let { scale ->
            drawLiveTwoHandScale(fixedCenter, fixedRadius, scale, textMeasurer)
            return@Canvas
        }
        val gesture = effect ?: return@Canvas
        drawDynamicEffect(
            gesture,
            fixedCenter,
            fixedRadius,
            progress.value,
            textMeasurer,
        )
    }
}

private fun DrawScope.drawDynamicEffect(
    gesture: GestureId,
    center: Offset,
    radius: Float,
    progress: Float,
    textMeasurer: TextMeasurer,
) {
    if (progress >= 1f) return
    val alpha = interactionEffectFade(progress)
    when (gesture) {
        GestureId.SWIPE_LEFT -> drawDirectionalStack(center, radius, Offset(-1f, 0f), progress, alpha)
        GestureId.SWIPE_RIGHT -> drawDirectionalStack(center, radius, Offset(1f, 0f), progress, alpha)
        GestureId.SWIPE_UP -> drawDirectionalStack(center, radius, Offset(0f, -1f), progress, alpha)
        GestureId.SWIPE_DOWN -> drawDirectionalStack(center, radius, Offset(0f, 1f), progress, alpha)
        GestureId.ZOOM_IN -> drawZoomEffect(center, radius, progress, alpha, true, textMeasurer)
        GestureId.ZOOM_OUT -> drawZoomEffect(center, radius, progress, alpha, false, textMeasurer)
        else -> Unit
    }
}

private fun DrawScope.drawLiveTwoHandScale(
    center: Offset,
    radius: Float,
    scaleFactor: Float,
    textMeasurer: TextMeasurer,
) {
    val boundedScale = scaleFactor.coerceIn(0.1f, 10f)
    val normalized = (boundedScale - 0.1f) / 9.9f
    val ringScale = 0.62f + normalized * 2.30f
    repeat(2) { index ->
        drawCircle(
            color = (if (index == 0) InteractionMint else InteractionPink)
                .copy(alpha = 0.88f - index * 0.28f),
            radius = radius * (ringScale + index * 0.22f),
            center = center,
            style = Stroke(width = max(3f, radius * 0.055f), cap = StrokeCap.Round),
        )
    }
    drawFashionSymbol(
        symbol = "${"%.2f".format(scaleFactor)}×",
        center = center,
        radius = radius * 0.82f,
        progress = 0f,
        alpha = 1f,
        accent = if (scaleFactor >= 1f) InteractionMint else InteractionYellow,
        textMeasurer = textMeasurer,
    )
}

private fun DrawScope.drawDirectionalStack(
    center: Offset,
    radius: Float,
    direction: Offset,
    progress: Float,
    alpha: Float,
) {
    val enter = (progress / 0.24f).coerceIn(0f, 1f)
    val travel = radius * (0.10f + enter * 0.72f)
    val head = center + direction * travel
    val layers = listOf(
        Triple(-radius * 0.34f, InteractionMint, 0.52f),
        Triple(-radius * 0.16f, InteractionPink, 0.74f),
        Triple(0f, Color.White, 1f),
    )
    layers.forEach { (distance, color, layerAlpha) ->
        drawVectorChevron(
            center = head + direction * distance,
            direction = direction,
            size = radius * 0.72f,
            color = color,
            alpha = alpha * layerAlpha,
        )
    }
}

private fun DrawScope.drawVectorChevron(
    center: Offset,
    direction: Offset,
    size: Float,
    color: Color,
    alpha: Float,
) {
    val perpendicular = Offset(-direction.y, direction.x)
    val tip = center + direction * size * 0.56f
    val back = center - direction * size * 0.44f
    val wing = perpendicular * size * 0.54f
    val stroke = max(3f, size * 0.17f)
    drawLine(color.copy(alpha = alpha), back - wing, tip, stroke, StrokeCap.Round)
    drawLine(color.copy(alpha = alpha), tip, back + wing, stroke, StrokeCap.Round)
}

private fun DrawScope.drawZoomEffect(
    center: Offset,
    radius: Float,
    progress: Float,
    alpha: Float,
    zoomIn: Boolean,
    textMeasurer: TextMeasurer,
) {
    val enter = (progress / 0.22f).coerceIn(0f, 1f)
    val ringScale = if (zoomIn) 0.48f + enter * 0.72f else 1.20f - enter * 0.72f
    repeat(2) { index ->
        val ringRadius = radius * (ringScale + index * 0.20f)
        val color = if (index == 0) InteractionMint else InteractionPink
        drawCircle(
            color = color.copy(alpha = alpha * (0.82f - index * 0.24f)),
            radius = ringRadius,
            center = center,
            style = Stroke(width = max(3f, radius * 0.055f), cap = StrokeCap.Round),
        )
    }
    drawFashionSymbol(
        symbol = if (zoomIn) "+" else "−",
        center = center,
        radius = radius,
        progress = progress,
        alpha = alpha,
        accent = if (zoomIn) InteractionMint else InteractionYellow,
        textMeasurer = textMeasurer,
    )
}

private fun DrawScope.drawFashionSymbol(
    symbol: String,
    center: Offset,
    radius: Float,
    progress: Float,
    alpha: Float,
    accent: Color,
    textMeasurer: TextMeasurer,
) {
    val style = TextStyle(
        fontFamily = OppoScoreFont,
        fontWeight = FontWeight.Black,
        fontSize = (radius * 0.62f).coerceIn(38f, 74f).sp,
    )
    // Reuse Compose's paragraph cache for the fixed +/- event symbols. The
    // live scale value changes text, but avoiding forced cache bypass still
    // prevents rebuilding identical layouts on every animation frame.
    val fill = textMeasurer.measure(symbol, style.copy(color = Color.White.copy(alpha = alpha)))
    val shadow = textMeasurer.measure(symbol, style.copy(color = accent.copy(alpha = alpha)))
    val outline = textMeasurer.measure(
        symbol,
        style.copy(
            color = InteractionInk.copy(alpha = alpha),
            drawStyle = Stroke(width = 8f, join = StrokeJoin.Round),
        ),
    )
    val rise = radius * progress * 0.18f
    val topLeft = Offset(center.x - fill.size.width / 2f, center.y - fill.size.height / 2f - rise)
    rotate(if (symbol == "+") -5f else 5f, pivot = center) {
        drawText(textLayoutResult = shadow, topLeft = topLeft + Offset(8f, 9f))
        drawText(textLayoutResult = outline, topLeft = topLeft)
        drawText(textLayoutResult = fill, topLeft = topLeft)
    }
}

private fun interactionEffectFade(progress: Float): Float = when {
    progress < 0.14f -> progress / 0.14f
    progress < 0.64f -> 1f
    else -> ((1f - progress) / 0.36f).coerceIn(0f, 1f)
}
