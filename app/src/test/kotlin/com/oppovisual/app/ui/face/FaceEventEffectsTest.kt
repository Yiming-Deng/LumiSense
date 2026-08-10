package com.oppovisual.app.ui.face

import com.oppovisual.core.HeadMotionId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceEventEffectsTest {
    @Test
    fun turnArrowsFollowTheNamedHeadDirection() {
        assertEquals(-1f, turnArrowDirection(HeadMotionId.TURN_LEFT))
        assertEquals(1f, turnArrowDirection(HeadMotionId.TURN_RIGHT))
    }

    @Test
    fun puckerStrengthUsesAQuietDeadZoneAndSaturates() {
        assertEquals(0f, puckerEffectStrength(0.40f))
        assertTrue(puckerEffectStrength(0.65f) > 0.70f)
        assertEquals(1f, puckerEffectStrength(1f))
    }
}
