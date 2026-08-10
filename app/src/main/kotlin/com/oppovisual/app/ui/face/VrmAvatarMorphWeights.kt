package com.oppovisual.app.ui.face

import com.oppovisual.app.recognition.BlendshapeScore
import kotlin.math.max
import kotlin.math.pow

/**
 * Continuous MediaPipe-to-VRM mapping for VRM1_Constraint_Twist_Sample.
 *
 * The avatar stream intentionally consumes raw face coefficients rather than
 * ExpressionId events. Expression recognition remains a separate product path.
 */
class VrmAvatarMorphFilter(
    private val attack: Float = 0.56f,
    private val release: Float = 0.34f,
) {
    private var filtered = FloatArray(VrmAvatarMorphTargets.COUNT)

    fun update(blendshapes: List<BlendshapeScore>): FloatArray {
        val values = blendshapes.associate { it.name to it.score.coerceIn(0f, 1f) }
        fun score(name: String) = values[name] ?: 0f
        fun response(
            name: String,
            deadZone: Float,
            fullScale: Float = 1f,
            exponent: Float = 1f,
        ) = response(score(name), deadZone, fullScale, exponent)
        fun average(
            left: String,
            right: String,
            deadZone: Float,
            fullScale: Float = 1f,
            exponent: Float = 1f,
        ) = response((score(left) + score(right)) * 0.5f, deadZone, fullScale, exponent)

        val raw = FloatArray(VrmAvatarMorphTargets.COUNT)


        val browDown = average("browDownLeft", "browDownRight", 0.10f)
        val browUp = max(
            response("browInnerUp", 0.10f),
            average("browOuterUpLeft", "browOuterUpRight", 0.10f),
        )
        raw[VrmAvatarMorphTargets.BROW_ANGRY] = browDown * 0.82f
        raw[VrmAvatarMorphTargets.BROW_SORROW] = response("browInnerUp", 0.14f) * 0.45f
        raw[VrmAvatarMorphTargets.BROW_SURPRISED] = browUp

        val blinkLeft = response("eyeBlinkLeft", 0.14f)
        val blinkRight = response("eyeBlinkRight", 0.14f)
        val squintLeft = response("eyeSquintLeft", 0.10f)
        val squintRight = response("eyeSquintRight", 0.10f)
        val eyeWide = average("eyeWideLeft", "eyeWideRight", 0.10f)
        raw[VrmAvatarMorphTargets.EYE_CLOSE_LEFT] = blinkLeft
        raw[VrmAvatarMorphTargets.EYE_CLOSE_RIGHT] = blinkRight
        raw[VrmAvatarMorphTargets.EYE_ANGRY] = max(browDown * 0.52f, (squintLeft + squintRight) * 0.22f)
        raw[VrmAvatarMorphTargets.EYE_JOY_LEFT] = max(squintLeft, response("mouthSmileLeft", 0.18f) * 0.42f)
        raw[VrmAvatarMorphTargets.EYE_JOY_RIGHT] = max(squintRight, response("mouthSmileRight", 0.18f) * 0.42f)
        raw[VrmAvatarMorphTargets.EYE_SURPRISED] = eyeWide

        // jawOpen has a small non-zero floor even with a closed mouth.  Keep
        // that floor out of the avatar's open-mouth morphs; otherwise a closed
        // pucker or ordinary speech noise makes the avatar look like it is
        // talking.  The higher dead-zone is only for the avatar stream and
        // does not change the product's expression classifier.
        val jawOpen = response("jawOpen", 0.24f, exponent = 1.15f)
        // MediaPipe raises pucker/funnel during ordinary lip movement. Keep the
        // low range quiet and reserve most of the avatar travel for a deliberate
        // pucker. Funnel is a weaker secondary signal because it also rises while
        // speaking.
        val puckerSource = max(score("mouthPucker"), score("mouthFunnel") * 0.85f)
        // Leave a quiet lower range for lip jitter, then preserve a broad
        // high-end range so a deliberate pucker can still deepen visibly.
        val pucker = response(puckerSource, 0.48f, fullScale = 0.98f, exponent = 1.25f)
        val smile = average("mouthSmileLeft", "mouthSmileRight", 0.10f)
        val frown = average("mouthFrownLeft", "mouthFrownRight", 0.10f)
        val stretch = average("mouthStretchLeft", "mouthStretchRight", 0.10f)
        val dimple = average("mouthDimpleLeft", "mouthDimpleRight", 0.10f)
        val upperUp = average("mouthUpperUpLeft", "mouthUpperUpRight", 0.08f)
        val lowerDown = average("mouthLowerDownLeft", "mouthLowerDownRight", 0.08f)
        val roundedOpen = jawOpen * pucker

        raw[VrmAvatarMorphTargets.MOUTH_CLOSE] = maxOf(
            response("mouthClose", 0.10f),
            pucker * 0.48f,
            smile * (1f - jawOpen) * 0.42f,
        )
        raw[VrmAvatarMorphTargets.MOUTH_ANGRY] = max(frown * 0.72f, response("mouthPressLeft", 0.12f) * 0.22f)
        raw[VrmAvatarMorphTargets.MOUTH_FUN] = max(dimple * 0.55f, smile * (1f - jawOpen) * 0.72f)
        raw[VrmAvatarMorphTargets.MOUTH_JOY] = smile * jawOpen * 0.68f
        raw[VrmAvatarMorphTargets.MOUTH_SORROW] = frown
        raw[VrmAvatarMorphTargets.MOUTH_SURPRISED] = roundedOpen * 0.55f
        raw[VrmAvatarMorphTargets.MOUTH_UP] = upperUp * 0.52f
        raw[VrmAvatarMorphTargets.MOUTH_DOWN] = lowerDown * 0.52f
        raw[VrmAvatarMorphTargets.MOUTH_SMALL] = pucker * (1f - jawOpen * 0.35f) * 0.72f
        raw[VrmAvatarMorphTargets.MOUTH_LARGE] = jawOpen * (1f - pucker) * 0.18f
        raw[VrmAvatarMorphTargets.MOUTH_A] = jawOpen * (1f - pucker * 0.78f)
        raw[VrmAvatarMorphTargets.MOUTH_I] = stretch * (1f - jawOpen) * 0.55f
        raw[VrmAvatarMorphTargets.MOUTH_U] = roundedOpen * 0.55f
        raw[VrmAvatarMorphTargets.MOUTH_E] = stretch * jawOpen * 0.36f
        raw[VrmAvatarMorphTargets.MOUTH_O] = roundedOpen * 0.65f

        for (index in raw.indices) {
            val factor = if (raw[index] > filtered[index]) attack else release
            filtered[index] += (raw[index] - filtered[index]) * factor
        }
        return filtered.copyOf()
    }

    fun reset() {
        filtered.fill(0f)
    }

    private fun response(
        value: Float,
        deadZone: Float,
        fullScale: Float = 1f,
        exponent: Float = 1f,
    ): Float {
        val normalized = ((value - deadZone) / (fullScale - deadZone).coerceAtLeast(0.01f))
            .coerceIn(0f, 1f)
        return normalized.pow(exponent)
    }

}

object VrmAvatarMorphTargets {
    const val COUNT = 24

    const val BROW_ANGRY = 0
    const val BROW_SORROW = 1
    const val BROW_SURPRISED = 2

    const val EYE_CLOSE_LEFT = 3
    const val EYE_CLOSE_RIGHT = 4
    const val EYE_ANGRY = 5
    const val EYE_JOY_LEFT = 6
    const val EYE_JOY_RIGHT = 7
    const val EYE_SURPRISED = 8

    const val MOUTH_CLOSE = 9
    const val MOUTH_ANGRY = 10
    const val MOUTH_FUN = 11
    const val MOUTH_JOY = 12
    const val MOUTH_SORROW = 13
    const val MOUTH_SURPRISED = 14
    const val MOUTH_UP = 15
    const val MOUTH_DOWN = 16
    const val MOUTH_SMALL = 17
    const val MOUTH_LARGE = 18
    const val MOUTH_A = 19
    const val MOUTH_I = 20
    const val MOUTH_U = 21
    const val MOUTH_E = 22
    const val MOUTH_O = 23
}
