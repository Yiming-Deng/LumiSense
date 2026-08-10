package com.oppovisual.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StaticGestureStabilizerTest {
    @Test
    fun confirmsFourOfFiveMatchingFramesOnlyOnce() {
        val stabilizer = StaticGestureStabilizer()
        repeat(3) { assertNull(stabilizer.update(GestureId.OPEN_PALM, 0.9f)) }
        stabilizer.update(null, 0f)

        val first = stabilizer.update(GestureId.OPEN_PALM, 0.8f)
        assertEquals(GestureId.OPEN_PALM, first?.label)
        assertTrue(first?.isNewEvent == true)

        val repeated = stabilizer.update(GestureId.OPEN_PALM, 0.9f)
        assertFalse(repeated?.isNewEvent ?: true)
    }

    @Test
    fun ignoresLowConfidenceCandidates() {
        val stabilizer = StaticGestureStabilizer()
        repeat(5) { assertNull(stabilizer.update(GestureId.CLOSED_FIST, 0.69f)) }
    }

    @Test
    fun resetAllowsSameGestureToEmitAgain() {
        val stabilizer = StaticGestureStabilizer()
        repeat(4) { stabilizer.update(GestureId.VICTORY, 0.9f) }
        stabilizer.reset()
        var result: StableGesture? = null
        repeat(4) { result = stabilizer.update(GestureId.VICTORY, 0.9f) }
        assertTrue(result?.isNewEvent == true)
    }
}

