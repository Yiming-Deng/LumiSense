package com.oppovisual.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oppovisual.app.recognition.RecognitionDomain
import com.oppovisual.core.GestureId
import com.oppovisual.core.Point3
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private val GestureMint = Color(0xFF7CFFD9)
private val GesturePink = Color(0xFFFF4F9A)
private val GestureYellow = Color(0xFFFFDF5D)
private val GestureBlue = Color(0xFF73B9FF)

private const val EMOJI_SPAWN_INTERVAL_MS = 280L
private const val MAX_SINGLE_EMOJI_PARTICLES = 6
private const val MAX_PAIR_EMOJI_PARTICLES = 9
private const val STATIC_EFFECT_CONFIRM_FRAMES = 10

private data class GestureAnchor(
    val center: Offset,
    val radius: Float,
    val points: List<Offset>,
)

private data class EmojiParticle(
    val id: Long,
    val emoji: String,
    val origin: Offset,
    val sizePx: Float,
    val createdAtMs: Long,
    val durationMs: Long,
    val driftX: Float,
    val driftY: Float,
    val swayAmplitude: Float,
    val swayFrequency: Float,
    val phase: Float,
)

private data class EmojiSource(
    val emoji: String,
    val anchor: GestureAnchor,
)

private class GestureEffectSmoother {
    private val anchors = mutableMapOf<Int, GestureAnchor>()

    fun update(trackId: Int, current: GestureAnchor): GestureAnchor {
        val previous = anchors[trackId]
        if (previous == null) {
            anchors[trackId] = current
            return current
        }
        val centerMovement = distance(previous.center, current.center)
        val centerAmount = adaptiveAmount(
            movement = centerMovement,
            reference = current.radius * 0.24f,
            minimum = 0.38f,
            maximum = 0.72f,
        )
        val next = GestureAnchor(
            center = blend(previous.center, current.center, centerAmount),
            radius = previous.radius + (current.radius - previous.radius) * 0.36f,
            points = previous.points.zip(current.points).map { (old, fresh) ->
                val amount = adaptiveAmount(
                    movement = distance(old, fresh),
                    reference = current.radius * 0.16f,
                    minimum = 0.34f,
                    maximum = 0.68f,
                )
                blend(old, fresh, amount)
            },
        )
        anchors[trackId] = next
        return next
    }

    fun clear() = anchors.clear()

    fun retain(trackIds: Set<Int>) {
        anchors.keys.retainAll(trackIds)
    }

    private fun blend(old: Offset, fresh: Offset, amount: Float): Offset = Offset(
        old.x + (fresh.x - old.x) * amount,
        old.y + (fresh.y - old.y) * amount,
    )

    private fun distance(first: Offset, second: Offset): Float {
        val dx = second.x - first.x
        val dy = second.y - first.y
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun adaptiveAmount(
        movement: Float,
        reference: Float,
        minimum: Float,
        maximum: Float,
    ): Float {
        val ratio = (movement / reference.coerceAtLeast(1f)).coerceIn(0f, 1f)
        return minimum + (maximum - minimum) * ratio
    }
}

/**
 * Static gesture effects are deliberately rendered from the stabilized display
 * result. This keeps the visual layer independent from dynamic event state and
 * prevents an animation from changing recognition or ProductFSM behavior.
 */
@Composable
fun GestureEventEffects(
    state: RecognitionUiState,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    var clockMs by remember { mutableLongStateOf(0L) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    val particles = remember { mutableStateListOf<EmojiParticle>() }
    var particleId by remember { mutableLongStateOf(0L) }
    var particleSignature by remember { mutableStateOf<String?>(null) }
    var lastSpawnMs by remember { mutableLongStateOf(0L) }
    val currentState by rememberUpdatedState(state)
    val smoother = remember { GestureEffectSmoother() }
    LaunchedEffect(state.domain, state.gestureHands.isEmpty()) {
        if (state.domain != RecognitionDomain.GESTURE || state.gestureHands.isEmpty()) {
            smoother.clear()
        }
    }
    LaunchedEffect(viewportSize) {
        if (viewportSize == IntSize.Zero) {
            return@LaunchedEffect
        }
        while (isActive) {
            val now = System.nanoTime() / 1_000_000L
            clockMs = now
            val latest = currentState
            val latestGesture = latest.gesture?.takeUnless { it.isDynamic }
            val signature = emojiParticleSignature(latest)
            if (signature != particleSignature) {
                particleSignature = signature
                lastSpawnMs = now
            }
            particles.removeAll { now - it.createdAtMs >= it.durationMs }
            val eligible = latest.domain == RecognitionDomain.GESTURE &&
                latestGesture != null &&
                latest.gestureHands.isNotEmpty() &&
                latest.gestureStableFrames >= STATIC_EFFECT_CONFIRM_FRAMES
            if (eligible && now - lastSpawnMs >= EMOJI_SPAWN_INTERVAL_MS) {
                val sources = resolveEmojiSources(
                    latest,
                    Size(viewportSize.width.toFloat(), viewportSize.height.toFloat()),
                )
                val maxParticles = if (latestGesture!!.requiredHands == 2) {
                    MAX_PAIR_EMOJI_PARTICLES
                } else {
                    MAX_SINGLE_EMOJI_PARTICLES
                }
                sources.forEach { source ->
                    if (particles.size < maxParticles) {
                        particles += createEmojiParticle(
                            id = particleId++,
                            source = source,
                            nowMs = now,
                        )
                    }
                }
                lastSpawnMs = now
            }
            delay(16L)
        }
    }
    val phase = (clockMs % 1_600L).toFloat() / 1_600f
    val staticGesture = state.gesture?.takeUnless { it.isDynamic }
    val viewport = Size(viewportSize.width.toFloat(), viewportSize.height.toFloat())
    val smoothedHands = remember(
        state.gestureHands,
        state.inputWidth,
        state.inputHeight,
        viewportSize,
    ) {
        if (
            viewportSize != IntSize.Zero &&
            state.inputWidth > 0 &&
            state.inputHeight > 0
        ) {
            smoother.retain(state.gestureHands.map { it.trackId }.toSet())
            state.gestureHands.mapNotNull { hand ->
                mapAnchor(hand.landmarks, state.inputWidth, state.inputHeight, viewport)
                    ?.let { hand to smoother.update(hand.trackId, it) }
            }
        } else {
            emptyList()
        }
    }

    Canvas(
        modifier
            .onSizeChanged { viewportSize = it }
            .testTag("gesture_event_effects"),
    ) {
        if (state.inputWidth <= 0 || state.inputHeight <= 0) return@Canvas
        drawEmojiTrail(particles, clockMs, textMeasurer)
        if (
            staticGesture == null ||
            state.gestureHands.isEmpty() ||
            state.gestureStableFrames < STATIC_EFFECT_CONFIRM_FRAMES
        ) return@Canvas
        val anchors = smoothedHands.mapNotNull { (hand, smoothed) ->
            hand.displayGesture?.takeUnless { it.isDynamic }?.let { gesture ->
                gesture to smoothed
            }
        }
        if (staticGesture.requiredHands == 2) {
            val pairAnchors = smoothedHands.map { it.second }
            val pair = pairAnchors.takeIf { it.size >= 2 }?.let { entries ->
                val points = entries.flatMap { it.points }
                val center = Offset(
                    entries.map { it.center.x }.average().toFloat(),
                    entries.map { it.center.y }.average().toFloat(),
                )
                GestureAnchor(
                    center = center,
                    radius = entries.map { it.radius }.average().toFloat(),
                    points = points,
                )
            }
            if (pair != null) {
                drawGesture(staticGesture, pair, phase)
            }
        } else {
            anchors.forEach { (gesture, anchor) ->
                drawGesture(gesture, anchor, phase)
            }
        }
    }
}

private fun mapAnchor(
    landmarks: List<Point3>,
    inputWidth: Int,
    inputHeight: Int,
    viewport: Size,
): GestureAnchor? {
    if (landmarks.size < 21) return null
    val scale = min(viewport.width / inputWidth.toFloat(), viewport.height / inputHeight.toFloat())
    val offsetX = (viewport.width - inputWidth * scale) / 2f
    val offsetY = (viewport.height - inputHeight * scale) / 2f
    val points = landmarks.map { point ->
        Offset(
            offsetX + point.x.coerceIn(0f, 1f) * inputWidth * scale,
            offsetY + point.y.coerceIn(0f, 1f) * inputHeight * scale,
        )
    }
    val center = points[0]
    val bounds = points.fold(
        floatArrayOf(center.x, center.y, center.x, center.y),
    ) { result, point ->
        result[0] = min(result[0], point.x)
        result[1] = min(result[1], point.y)
        result[2] = max(result[2], point.x)
        result[3] = max(result[3], point.y)
        result
    }
    val radius = max(bounds[2] - bounds[0], bounds[3] - bounds[1]).coerceAtLeast(48f) * 0.5f
    return GestureAnchor(center, radius, points)
}

private fun resolveEmojiSources(
    state: RecognitionUiState,
    viewport: Size,
): List<EmojiSource> {
    val gesture = state.gesture?.takeUnless { it.isDynamic } ?: return emptyList()
    if (gesture.requiredHands == 2) {
        val anchors = state.gestureHands.mapNotNull { hand ->
            mapAnchor(hand.landmarks, state.inputWidth, state.inputHeight, viewport)
        }
        if (anchors.size < 2) return emptyList()
        val fingerCenters = anchors.map(::fingerMiddleCenter)
        val pairAnchor = GestureAnchor(
            center = Offset(
                fingerCenters.map { it.x }.average().toFloat(),
                fingerCenters.map { it.y }.average().toFloat(),
            ),
            radius = anchors.map { it.radius }.average().toFloat(),
            points = anchors.flatMap { it.points },
        )
        val emoji = gestureEffectEmoji(gesture) ?: return emptyList()
        return listOf(EmojiSource(emoji, pairAnchor))
    }
    return state.gestureHands.mapNotNull { hand ->
        val handGesture = hand.displayGesture?.takeUnless { it.isDynamic } ?: return@mapNotNull null
        val anchor = mapAnchor(hand.landmarks, state.inputWidth, state.inputHeight, viewport)
            ?: return@mapNotNull null
        val emoji = gestureEffectEmoji(handGesture) ?: return@mapNotNull null
        EmojiSource(emoji, anchor.copy(center = fingerMiddleCenter(anchor)))
    }
}

private fun fingerMiddleCenter(anchor: GestureAnchor): Offset {
    val points = FINGER_MIDDLE_POINTS.mapNotNull(anchor.points::getOrNull)
    if (points.isEmpty()) return anchor.center
    return Offset(
        points.map { it.x }.average().toFloat(),
        points.map { it.y }.average().toFloat(),
    )
}

private fun emojiParticleSignature(state: RecognitionUiState): String = buildString {
    append(state.gesture?.wireName ?: "none")
    state.gestureHands.forEach { hand ->
        append('|').append(hand.trackId).append(':').append(hand.displayGesture?.wireName)
    }
}

private fun createEmojiParticle(
    id: Long,
    source: EmojiSource,
    nowMs: Long,
): EmojiParticle {
    val radius = source.anchor.radius
    return EmojiParticle(
        id = id,
        emoji = source.emoji,
        origin = source.anchor.center + Offset(
            (randomUnit(id, 8) - 0.5f) * radius * 0.36f,
            (randomUnit(id, 9) - 0.5f) * radius * 0.18f,
        ),
        sizePx = radius * (0.13f + randomUnit(id, 1) * 0.07f),
        createdAtMs = nowMs,
        durationMs = (1_650L + (randomUnit(id, 2) * 780f).toLong()),
        driftX = (randomUnit(id, 3) - 0.5f) * radius * 1.22f,
        driftY = radius * (0.55f + randomUnit(id, 4) * 0.50f),
        swayAmplitude = radius * (0.12f + randomUnit(id, 5) * 0.36f),
        swayFrequency = 0.75f + randomUnit(id, 6) * 1.35f,
        phase = randomUnit(id, 7) * (2f * PI.toFloat()),
    )
}

private fun randomUnit(id: Long, salt: Int): Float {
    val mixed = (id + 1L) * 1_103L + salt * 2_053L
    val value = (mixed xor (mixed shl 13) xor (mixed ushr 7)) and 0xFFFFL
    return value.toFloat() / 65_535f
}

private val FINGER_MIDDLE_POINTS = listOf(2, 6, 10, 14, 18)

private fun DrawScope.drawGesture(
    gesture: GestureId,
    anchor: GestureAnchor,
    phase: Float,
) {
    val emoji = gestureEffectEmoji(gesture)
    if (emoji != null) {
        return
    }
    when (gesture) {
        GestureId.CLOSED_FIST -> drawPulseRings(anchor, phase, GesturePink)
        GestureId.GRABBING -> drawGatheringArcs(anchor, phase)
        GestureId.GRIP -> drawGripRings(anchor, phase)
        GestureId.HAND_HEART, GestureId.HAND_HEART_ALT -> drawHeart(anchor, phase)
        GestureId.HOLY -> drawBeam(anchor, phase, GestureYellow)
        GestureId.LITTLE_FINGER -> drawLittleFingerHighlight(anchor, phase)
        GestureId.MUTE -> drawMute(anchor)
        GestureId.OK -> drawOkRing(anchor, phase)
        GestureId.POINTING_UP -> drawFingerBeam(anchor, 8, phase, GestureMint)
        GestureId.OPEN_PALM -> drawPalmRays(anchor, phase)
        GestureId.VICTORY -> drawPeaceRays(anchor, phase)
        GestureId.POINT -> drawPointLaser(anchor, phase)
        GestureId.ROCK -> drawRockSparks(anchor, phase)
        GestureId.TAKE_PICTURE -> drawCameraFrame(anchor, phase)
        GestureId.THREE_GUN -> drawCrosshair(anchor, phase)
        GestureId.TIMEOUT -> drawPause(anchor, phase)
        GestureId.TWO_UP, GestureId.TWO_UP_INVERTED -> {
            drawInfiniteLaser(anchor, 8, 7, phase, GestureBlue)
            drawInfiniteLaser(anchor, 12, 11, phase, GestureMint)
        }
        GestureId.XSIGN -> drawCrossBands(anchor, phase)
        else -> drawPulseRings(anchor, phase, GestureMint)
    }
}

internal fun gestureEffectEmoji(gesture: GestureId): String? = when (gesture) {
    GestureId.CALL -> "📞"
    GestureId.THUMB_DOWN -> "👎"
    GestureId.CLOSED_FIST -> "✊"
    GestureId.FOUR -> "4️⃣"
    GestureId.GRABBING -> "🫴"
    GestureId.GRIP -> "🤌"
    GestureId.HAND_HEART -> "🫶"
    GestureId.HAND_HEART_ALT -> "💖"
    GestureId.HOLY -> "🙏"
    GestureId.MIDDLE_FINGER -> "🖕"
    GestureId.MUTE -> "🔇"
    GestureId.OK -> "👌"
    GestureId.POINTING_UP -> "☝️"
    GestureId.VICTORY -> "✌️"
    GestureId.PEACE_INVERTED -> "✌️"
    GestureId.POINT -> "🫵"
    GestureId.ROCK -> "🤘"
    GestureId.STOP, GestureId.STOP_INVERTED -> "🤚"
    GestureId.TAKE_PICTURE -> "📸"
    GestureId.THREE, GestureId.THREE_VARIANT_2, GestureId.THREE_VARIANT_3 -> "3️⃣"
    GestureId.THUMB_UP -> "👍"
    GestureId.THUMB_INDEX -> "🤏"
    GestureId.THUMB_INDEX_PAIR -> "🤏🤏"
    GestureId.THREE_GUN -> "🔫"
    GestureId.OPEN_PALM -> "🖐"
    GestureId.TIMEOUT -> "⏸️"
    GestureId.XSIGN -> "❌"
    else -> null
}

private fun DrawScope.drawEmojiTrail(
    particles: List<EmojiParticle>,
    nowMs: Long,
    textMeasurer: TextMeasurer,
) {
    val density = density.coerceAtLeast(1f)
    particles.forEach { particle ->
        val local = ((nowMs - particle.createdAtMs).toFloat() / particle.durationMs)
            .coerceIn(0f, 1f)
        val alpha = emojiParticleAlpha(local)
        val sway = sin(
            local * particle.swayFrequency * 2f * PI.toFloat() + particle.phase,
        ) * particle.swayAmplitude * (0.25f + local * 0.75f)
        val x = particle.origin.x + particle.driftX * local + sway
        val y = particle.origin.y - particle.driftY * local
        val fontSize = (particle.sizePx / density).coerceIn(14f, 44f).sp
        val layout = textMeasurer.measure(
            particle.emoji,
            TextStyle(
                color = Color.White.copy(alpha = alpha),
                fontSize = fontSize,
                fontWeight = FontWeight.Bold,
            ),
            skipCache = true,
        )
        drawCircle(
            color = Color.White.copy(alpha = alpha * 0.045f),
            radius = particle.sizePx * 0.68f,
            center = Offset(x, y),
        )
        drawText(
            layout,
            topLeft = Offset(
                x - layout.size.width / 2f,
                y - layout.size.height / 2f,
            ),
            alpha = 1f,
        )
    }
}

internal fun emojiParticleAlpha(progress: Float): Float {
    val local = progress.coerceIn(0f, 1f)
    val fadeIn = (local / 0.24f).coerceIn(0f, 1f)
    val fadeOut = ((1f - local) / 0.32f).coerceIn(0f, 1f)
    return min(fadeIn, fadeOut) * 0.64f
}

private fun DrawScope.drawPulseRings(anchor: GestureAnchor, phase: Float, color: Color) {
    repeat(2) { index ->
        val local = (phase + index * 0.5f) % 1f
        drawCircle(
            color = color.copy(alpha = (1f - local) * 0.60f),
            radius = anchor.radius * (0.22f + local * 0.48f),
            center = anchor.center,
            style = Stroke(width = max(2.dp.toPx(), anchor.radius * 0.025f)),
        )
    }
}

private fun DrawScope.drawGatheringArcs(anchor: GestureAnchor, phase: Float) {
    val radius = anchor.radius * (0.38f + 0.04f * sin(phase * 2f * PI).toFloat())
    repeat(5) { index ->
        val angle = index * 72f + phase * 35f
        val start = Offset(
            anchor.center.x + cos(Math.toRadians(angle.toDouble())).toFloat() * radius,
            anchor.center.y + sin(Math.toRadians(angle.toDouble())).toFloat() * radius,
        )
        drawArc(
            color = GestureMint.copy(alpha = 0.72f),
            startAngle = angle + 30f,
            sweepAngle = 42f,
            useCenter = false,
            topLeft = Offset(anchor.center.x - radius, anchor.center.y - radius),
            size = Size(radius * 2f, radius * 2f),
            style = Stroke(width = max(2.dp.toPx(), anchor.radius * 0.028f), cap = StrokeCap.Round),
        )
        drawLine(
            color = GesturePink.copy(alpha = 0.36f),
            start = start,
            end = anchor.center,
            strokeWidth = max(1.dp.toPx(), anchor.radius * 0.012f),
            cap = StrokeCap.Round,
        )
    }
}

private fun DrawScope.drawGripRings(anchor: GestureAnchor, phase: Float) {
    drawCircle(
        color = GestureYellow.copy(alpha = 0.75f),
        radius = anchor.radius * (0.34f + 0.03f * sin(phase * 2f * PI).toFloat()),
        center = anchor.center,
        style = Stroke(width = max(3.dp.toPx(), anchor.radius * 0.035f)),
    )
    drawCircle(
        color = GesturePink.copy(alpha = 0.48f),
        radius = anchor.radius * 0.47f,
        center = anchor.center,
        style = Stroke(width = max(1.dp.toPx(), anchor.radius * 0.018f)),
    )
}

private fun DrawScope.drawHeart(anchor: GestureAnchor, phase: Float) {
    val scale = anchor.radius * (0.33f + 0.05f * sin(phase * 2f * PI).toFloat())
    val path = Path().apply {
        moveTo(anchor.center.x, anchor.center.y + scale * 0.72f)
        cubicTo(anchor.center.x - scale * 1.25f, anchor.center.y - scale * 0.05f, anchor.center.x - scale * 0.63f, anchor.center.y - scale * 1.00f, anchor.center.x, anchor.center.y - scale * 0.38f)
        cubicTo(anchor.center.x + scale * 0.63f, anchor.center.y - scale * 1.00f, anchor.center.x + scale * 1.25f, anchor.center.y - scale * 0.05f, anchor.center.x, anchor.center.y + scale * 0.72f)
    }
    drawPath(path, GesturePink.copy(alpha = 0.84f), style = Stroke(width = max(3.dp.toPx(), anchor.radius * 0.03f)))
}

private fun DrawScope.drawBeam(anchor: GestureAnchor, phase: Float, color: Color) {
    val length = anchor.radius * (0.72f + 0.14f * sin(phase * 2f * PI).toFloat())
    drawLine(color.copy(alpha = 0.62f), anchor.center + Offset(0f, anchor.radius * 0.1f), anchor.center - Offset(0f, length), strokeWidth = max(4.dp.toPx(), anchor.radius * 0.05f), cap = StrokeCap.Round)
    drawCircle(color.copy(alpha = 0.72f), anchor.radius * 0.08f, anchor.center - Offset(0f, length))
}

private fun DrawScope.drawLittleFingerHighlight(anchor: GestureAnchor, phase: Float) {
    val tip = anchor.points.getOrNull(20) ?: anchor.center
    val pulse = 0.82f + 0.18f * sin(phase * 2f * PI).toFloat()
    drawCircle(
        color = GesturePink.copy(alpha = 0.16f),
        radius = anchor.radius * 0.15f * pulse,
        center = tip,
    )
    drawFourPointStar(
        center = tip,
        radius = anchor.radius * (0.095f + 0.025f * pulse),
        color = GesturePink,
        alpha = 0.94f,
    )
    drawFourPointStar(
        center = tip + Offset(anchor.radius * 0.08f, -anchor.radius * 0.07f),
        radius = anchor.radius * 0.035f,
        color = Color.White,
        alpha = 0.86f,
    )
}

private fun DrawScope.drawMute(anchor: GestureAnchor) {
    drawCircle(GestureBlue.copy(alpha = 0.34f), anchor.radius * 0.35f, anchor.center, style = Stroke(width = max(2.dp.toPx(), anchor.radius * 0.025f)))
    drawLine(GesturePink.copy(alpha = 0.88f), anchor.center - Offset(anchor.radius * 0.38f, anchor.radius * 0.38f), anchor.center + Offset(anchor.radius * 0.38f, anchor.radius * 0.38f), strokeWidth = max(3.dp.toPx(), anchor.radius * 0.04f), cap = StrokeCap.Round)
}

private fun DrawScope.drawOkRing(anchor: GestureAnchor, phase: Float) {
    drawArc(GestureMint.copy(alpha = 0.9f), -90f, 285f + phase * 75f, false, Offset(anchor.center.x - anchor.radius * 0.34f, anchor.center.y - anchor.radius * 0.34f), Size(anchor.radius * 0.68f, anchor.radius * 0.68f), style = Stroke(width = max(3.dp.toPx(), anchor.radius * 0.035f), cap = StrokeCap.Round))
}

private fun DrawScope.drawFingerBeam(anchor: GestureAnchor, tipIndex: Int, phase: Float, color: Color) {
    val tip = anchor.points.getOrNull(tipIndex) ?: anchor.center
    val length = anchor.radius * (0.60f + phase * 0.16f)
    drawLine(color.copy(alpha = 0.82f), tip, tip - Offset(0f, length), strokeWidth = max(2.dp.toPx(), anchor.radius * 0.024f), cap = StrokeCap.Round)
    drawLine(Color.White.copy(alpha = 0.56f), tip + Offset(anchor.radius * 0.045f, 0f), tip + Offset(anchor.radius * 0.045f, -length * 0.8f), strokeWidth = max(1.dp.toPx(), anchor.radius * 0.012f), cap = StrokeCap.Round)
}

private fun DrawScope.drawInfiniteLaser(
    anchor: GestureAnchor,
    tipIndex: Int,
    previousIndex: Int,
    phase: Float,
    color: Color,
) {
    val tip = anchor.points.getOrNull(tipIndex) ?: return
    val previous = anchor.points.getOrNull(previousIndex) ?: anchor.center
    val vector = tip - previous
    val length = max(1f, kotlin.math.sqrt(vector.x * vector.x + vector.y * vector.y))
    val direction = Offset(vector.x / length, vector.y / length)
    val rayLength = max(size.width, size.height) * 2.2f
    val end = tip + direction * rayLength
    val shimmer = 0.72f + 0.20f * sin(phase * 2f * PI).toFloat()
    drawLine(
        color = color.copy(alpha = 0.15f * shimmer),
        start = tip,
        end = end,
        strokeWidth = max(8.dp.toPx(), anchor.radius * 0.12f),
        cap = StrokeCap.Round,
    )
    drawLine(
        color = color.copy(alpha = 0.55f * shimmer),
        start = tip,
        end = end,
        strokeWidth = max(3.dp.toPx(), anchor.radius * 0.042f),
        cap = StrokeCap.Round,
    )
    drawLine(
        color = Color.White.copy(alpha = 0.88f * shimmer),
        start = tip,
        end = end,
        strokeWidth = max(1.dp.toPx(), anchor.radius * 0.012f),
        cap = StrokeCap.Round,
    )
    drawCircle(color.copy(alpha = 0.9f), anchor.radius * 0.045f, tip)
}

private fun DrawScope.drawPalmRays(anchor: GestureAnchor, phase: Float) {
    repeat(5) { index ->
        val angle = -140f + index * 20f
        val radians = Math.toRadians(angle.toDouble())
        val end = anchor.center + Offset(cos(radians).toFloat() * anchor.radius * (0.52f + phase * 0.12f), sin(radians).toFloat() * anchor.radius * (0.52f + phase * 0.12f))
        drawLine(GestureMint.copy(alpha = 0.72f), anchor.center, end, strokeWidth = max(2.dp.toPx(), anchor.radius * 0.022f), cap = StrokeCap.Round)
    }
}

private fun DrawScope.drawPeaceRays(anchor: GestureAnchor, phase: Float) {
    val length = anchor.radius * (0.55f + phase * 0.10f)
    drawLine(GestureMint.copy(alpha = 0.9f), anchor.center, anchor.center + Offset(-length * 0.45f, -length), strokeWidth = max(3.dp.toPx(), anchor.radius * 0.028f), cap = StrokeCap.Round)
    drawLine(GesturePink.copy(alpha = 0.9f), anchor.center, anchor.center + Offset(length * 0.45f, -length), strokeWidth = max(3.dp.toPx(), anchor.radius * 0.028f), cap = StrokeCap.Round)
}

private fun DrawScope.drawPointLaser(anchor: GestureAnchor, phase: Float) {
    val tip = anchor.points.getOrNull(8) ?: anchor.center
    val direction = (tip - anchor.center).let { vector ->
        val length = max(1f, kotlin.math.sqrt(vector.x * vector.x + vector.y * vector.y))
        Offset(vector.x / length, vector.y / length)
    }
    drawLine(GestureYellow.copy(alpha = 0.82f), anchor.center, tip + direction * anchor.radius * (0.35f + phase * 0.12f), strokeWidth = max(2.dp.toPx(), anchor.radius * 0.02f), cap = StrokeCap.Round)
    drawCircle(Color.White.copy(alpha = 0.9f), anchor.radius * 0.045f, tip + direction * anchor.radius * 0.35f)
}

private fun DrawScope.drawRockSparks(anchor: GestureAnchor, phase: Float) {
    repeat(3) { index ->
        val start = anchor.center + Offset((index - 1) * anchor.radius * 0.18f, anchor.radius * 0.12f)
        val end = start + Offset((index - 1) * anchor.radius * 0.22f, -anchor.radius * (0.45f + phase * 0.12f))
        drawLine(GestureYellow.copy(alpha = 0.78f), start, end, strokeWidth = max(2.dp.toPx(), anchor.radius * 0.02f), cap = StrokeCap.Round)
    }
}

private fun DrawScope.drawCameraFrame(anchor: GestureAnchor, phase: Float) {
    val side = anchor.radius * (0.62f + phase * 0.08f)
    drawRoundRect(GestureMint.copy(alpha = 0.8f), Offset(anchor.center.x - side / 2f, anchor.center.y - side / 2f), Size(side, side), cornerRadius = androidx.compose.ui.geometry.CornerRadius(anchor.radius * 0.06f), style = Stroke(width = max(2.dp.toPx(), anchor.radius * 0.025f)))
}

private fun DrawScope.drawCrosshair(anchor: GestureAnchor, phase: Float) {
    val radius = anchor.radius * (0.20f + phase * 0.08f)
    drawCircle(GestureYellow.copy(alpha = 0.85f), radius, anchor.center, style = Stroke(width = max(2.dp.toPx(), anchor.radius * 0.02f)))
    drawLine(GestureYellow.copy(alpha = 0.85f), anchor.center - Offset(radius * 1.6f, 0f), anchor.center + Offset(radius * 1.6f, 0f), strokeWidth = max(2.dp.toPx(), anchor.radius * 0.018f))
    drawLine(GestureYellow.copy(alpha = 0.85f), anchor.center - Offset(0f, radius * 1.6f), anchor.center + Offset(0f, radius * 1.6f), strokeWidth = max(2.dp.toPx(), anchor.radius * 0.018f))
}

private fun DrawScope.drawPause(anchor: GestureAnchor, phase: Float) {
    val width = anchor.radius * 0.14f
    val height = anchor.radius * (0.44f + phase * 0.04f)
    drawRoundRect(GestureBlue.copy(alpha = 0.86f), Offset(anchor.center.x - width * 1.5f, anchor.center.y - height / 2f), Size(width, height), cornerRadius = androidx.compose.ui.geometry.CornerRadius(width / 2f))
    drawRoundRect(GestureBlue.copy(alpha = 0.86f), Offset(anchor.center.x + width * 0.5f, anchor.center.y - height / 2f), Size(width, height), cornerRadius = androidx.compose.ui.geometry.CornerRadius(width / 2f))
}

private fun DrawScope.drawCrossBands(anchor: GestureAnchor, phase: Float) {
    val length = anchor.radius * (0.52f + phase * 0.10f)
    drawLine(GesturePink.copy(alpha = 0.82f), anchor.center - Offset(length, length), anchor.center + Offset(length, length), strokeWidth = max(3.dp.toPx(), anchor.radius * 0.03f), cap = StrokeCap.Round)
    drawLine(GestureMint.copy(alpha = 0.82f), anchor.center - Offset(length, -length), anchor.center + Offset(length, -length), strokeWidth = max(3.dp.toPx(), anchor.radius * 0.03f), cap = StrokeCap.Round)
}

private fun DrawScope.drawFourPointStar(center: Offset, radius: Float, color: Color, alpha: Float) {
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
    drawPath(path, color.copy(alpha = alpha))
    drawCircle(Color.White.copy(alpha = alpha), radius * 0.15f, center)
}
