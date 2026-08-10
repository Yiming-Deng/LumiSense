package com.oppovisual.app.ui.face

import com.oppovisual.app.recognition.BlendshapeScore
import com.oppovisual.core.HeadPose
import kotlin.math.max

data class CatMocapMotion(
    val yaw: Float = 0f,
    val pitch: Float = 0f,
    val roll: Float = 0f,
    val blinkLeft: Float = 0f,
    val blinkRight: Float = 0f,
    val gazeX: Float = 0f,
    val gazeY: Float = 0f,
    val browLeft: Float = 0f,
    val browRight: Float = 0f,
    val jawOpen: Float = 0f,
    val smileLeft: Float = 0f,
    val smileRight: Float = 0f,
    val pucker: Float = 0f,
)

class CatMocapMotionFilter(private val smoothing: Float = 0.42f) {
    private var filtered: CatMocapMotion? = null

    fun update(headPose: HeadPose?, blendshapes: List<BlendshapeScore>): CatMocapMotion {
        val values = blendshapes.associate { it.name to it.score.coerceIn(0f, 1f) }
        fun score(name: String) = values[name] ?: 0f
        val raw = CatMocapMotion(
            yaw = (headPose?.yawDegrees ?: 0f).coerceIn(-50f, 50f),
            pitch = (headPose?.pitchDegrees ?: 0f).coerceIn(-35f, 35f),
            roll = (headPose?.rollDegrees ?: 0f).coerceIn(-35f, 35f),
            blinkLeft = response(score("eyeBlinkLeft"), 0.18f),
            blinkRight = response(score("eyeBlinkRight"), 0.18f),
            gazeX = (
                score("eyeLookOutLeft") - score("eyeLookInLeft") +
                    score("eyeLookInRight") - score("eyeLookOutRight")
                ).div(2f).coerceIn(-1f, 1f),
            gazeY = (
                score("eyeLookUpLeft") + score("eyeLookUpRight") -
                    score("eyeLookDownLeft") - score("eyeLookDownRight")
                ).div(2f).coerceIn(-1f, 1f),
            browLeft = response(max(score("browInnerUp"), score("browOuterUpLeft")), 0.12f),
            browRight = response(max(score("browInnerUp"), score("browOuterUpRight")), 0.12f),
            jawOpen = response(score("jawOpen"), 0.08f),
            smileLeft = response(score("mouthSmileLeft"), 0.12f),
            smileRight = response(score("mouthSmileRight"), 0.12f),
            pucker = response(max(score("mouthPucker"), score("mouthFunnel")), 0.10f),
        )
        val previous = filtered
        val result = if (previous == null) raw else previous.lerp(raw, smoothing)
        filtered = result
        return result
    }

    fun reset() {
        filtered = null
    }

    private fun response(value: Float, deadZone: Float): Float =
        ((value - deadZone) / (1f - deadZone)).coerceIn(0f, 1f)

    private fun CatMocapMotion.lerp(target: CatMocapMotion, t: Float) = CatMocapMotion(
        yaw = yaw + (target.yaw - yaw) * t,
        pitch = pitch + (target.pitch - pitch) * t,
        roll = roll + (target.roll - roll) * t,
        blinkLeft = blinkLeft + (target.blinkLeft - blinkLeft) * t,
        blinkRight = blinkRight + (target.blinkRight - blinkRight) * t,
        gazeX = gazeX + (target.gazeX - gazeX) * t,
        gazeY = gazeY + (target.gazeY - gazeY) * t,
        browLeft = browLeft + (target.browLeft - browLeft) * t,
        browRight = browRight + (target.browRight - browRight) * t,
        jawOpen = jawOpen + (target.jawOpen - jawOpen) * t,
        smileLeft = smileLeft + (target.smileLeft - smileLeft) * t,
        smileRight = smileRight + (target.smileRight - smileRight) * t,
        pucker = pucker + (target.pucker - pucker) * t,
    )
}
