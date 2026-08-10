package com.oppovisual.app.ui.face

import com.oppovisual.app.recognition.BlendshapeScore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VrmAvatarMorphWeightsTest {
    @Test
    fun keepsLeftAndRightBlinkIndependent() {
        val weights = VrmAvatarMorphFilter(attack = 1f, release = 1f).update(
            listOf(
                BlendshapeScore("eyeBlinkLeft", 0.92f),
                BlendshapeScore("eyeBlinkRight", 0.08f),
            ),
        )

        assertTrue(weights[VrmAvatarMorphTargets.EYE_CLOSE_LEFT] > 0.8f)
        assertEquals(0f, weights[VrmAvatarMorphTargets.EYE_CLOSE_RIGHT], 0.001f)
    }

    @Test
    fun mapsContinuousMouthShapesWithoutExpressionEvents() {
        val weights = VrmAvatarMorphFilter(attack = 1f, release = 1f).update(
            listOf(
                BlendshapeScore("jawOpen", 0.90f),
                BlendshapeScore("mouthPucker", 0.95f),
                BlendshapeScore("mouthSmileLeft", 0.38f),
                BlendshapeScore("mouthSmileRight", 0.22f),
            ),
        )

        assertTrue(weights[VrmAvatarMorphTargets.MOUTH_A] > 0f)
        assertTrue(weights[VrmAvatarMorphTargets.MOUTH_O] > 0.4f)
        assertTrue(weights[VrmAvatarMorphTargets.MOUTH_JOY] > 0f)
        assertEquals(VrmAvatarMorphTargets.COUNT, weights.size)
    }

    @Test
    fun keepsSmileClosedUntilJawActuallyOpens() {
        val weights = VrmAvatarMorphFilter(attack = 1f, release = 1f).update(
            listOf(
                BlendshapeScore("mouthSmileLeft", 0.88f),
                BlendshapeScore("mouthSmileRight", 0.88f),
                BlendshapeScore("jawOpen", 0.10f),
            ),
        )

        assertTrue(weights[VrmAvatarMorphTargets.MOUTH_FUN] > 0.5f)
        assertTrue(weights[VrmAvatarMorphTargets.MOUTH_CLOSE] > 0.25f)
        assertEquals(0f, weights[VrmAvatarMorphTargets.MOUTH_JOY], 0.001f)
        assertEquals(0f, weights[VrmAvatarMorphTargets.MOUTH_A], 0.001f)
        assertEquals(0f, weights[VrmAvatarMorphTargets.MOUTH_LARGE], 0.001f)
    }

    @Test
    fun usesDifferentAttackAndReleaseForResponsiveStableMotion() {
        val filter = VrmAvatarMorphFilter(attack = 0.6f, release = 0.25f)
        val attack = filter.update(listOf(BlendshapeScore("jawOpen", 1f)))
        val release = filter.update(emptyList())

        assertEquals(0.6f, attack[VrmAvatarMorphTargets.MOUTH_A], 0.001f)
        assertEquals(0.45f, release[VrmAvatarMorphTargets.MOUTH_A], 0.001f)
    }

    @Test
    fun capsPuckerDrivenMorphs() {
        val weights = VrmAvatarMorphFilter(attack = 1f, release = 1f).update(
            listOf(
                BlendshapeScore("mouthPucker", 1f),
                BlendshapeScore("mouthFunnel", 1f),
            ),
        )

        assertTrue(weights[VrmAvatarMorphTargets.MOUTH_SMALL] <= 0.72f)
        assertEquals(0f, weights[VrmAvatarMorphTargets.MOUTH_U], 0.001f)
        assertEquals(0f, weights[VrmAvatarMorphTargets.MOUTH_O], 0.001f)
        assertTrue(weights[VrmAvatarMorphTargets.MOUTH_CLOSE] > 0.40f)
    }

    @Test
    fun preservesPuckerRangeWithoutOpeningAClosedMouth() {
        val filter = VrmAvatarMorphFilter(attack = 1f, release = 1f)
        val subtle = filter.update(listOf(BlendshapeScore("mouthPucker", 0.50f)))
        filter.reset()
        val deliberate = filter.update(listOf(BlendshapeScore("mouthPucker", 0.90f)))

        assertTrue(subtle[VrmAvatarMorphTargets.MOUTH_SMALL] < 0.05f)
        assertTrue(deliberate[VrmAvatarMorphTargets.MOUTH_SMALL] > 0.45f)
        assertTrue(deliberate[VrmAvatarMorphTargets.MOUTH_SMALL] < 0.72f)
        assertEquals(0f, deliberate[VrmAvatarMorphTargets.MOUTH_U], 0.001f)
        assertEquals(0f, deliberate[VrmAvatarMorphTargets.MOUTH_O], 0.001f)
    }

    @Test
    fun keepsSmallPuckerMotionNearNeutralWithoutReducingJawResponse() {
        val weights = VrmAvatarMorphFilter(attack = 1f, release = 1f).update(
            listOf(
                BlendshapeScore("jawOpen", 0.40f),
                BlendshapeScore("mouthPucker", 0.22f),
                BlendshapeScore("mouthFunnel", 0.20f),
            ),
        )

        assertTrue(weights[VrmAvatarMorphTargets.MOUTH_A] > 0.10f)
        assertTrue(weights[VrmAvatarMorphTargets.MOUTH_O] < 0.10f)
        assertTrue(weights[VrmAvatarMorphTargets.MOUTH_SMALL] < 0.10f)
    }
}
