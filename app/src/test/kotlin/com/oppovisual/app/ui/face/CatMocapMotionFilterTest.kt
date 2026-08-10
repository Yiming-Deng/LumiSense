package com.oppovisual.app.ui.face

import com.oppovisual.app.recognition.BlendshapeScore
import com.oppovisual.core.HeadPose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatMocapMotionFilterTest {
    @Test
    fun mapsFaceLandmarkerChannelsToIndependentParts() {
        val motion = CatMocapMotionFilter(smoothing = 1f).update(
            HeadPose(yawDegrees = 18f, pitchDegrees = -7f, rollDegrees = 9f),
            listOf(
                BlendshapeScore("eyeBlinkLeft", 0.9f),
                BlendshapeScore("eyeBlinkRight", 0.2f),
                BlendshapeScore("jawOpen", 0.8f),
                BlendshapeScore("mouthPucker", 0.7f),
                BlendshapeScore("mouthSmileLeft", 0.6f),
                BlendshapeScore("browOuterUpRight", 0.75f),
                BlendshapeScore("eyeLookOutLeft", 0.8f),
                BlendshapeScore("eyeLookInRight", 0.8f),
            ),
        )

        assertEquals(18f, motion.yaw, 0.001f)
        assertTrue(motion.blinkLeft > motion.blinkRight)
        assertTrue(motion.jawOpen > 0.7f)
        assertTrue(motion.pucker > 0.6f)
        assertTrue(motion.smileLeft > 0.5f)
        assertTrue(motion.browRight > 0.6f)
        assertTrue(motion.gazeX > 0.7f)
    }

    @Test
    fun filtersAbruptMotionInsteadOfJumpingDirectly() {
        val filter = CatMocapMotionFilter(smoothing = 0.25f)
        filter.update(null, emptyList())
        val motion = filter.update(null, listOf(BlendshapeScore("jawOpen", 1f)))
        assertEquals(0.25f, motion.jawOpen, 0.001f)
    }
}
