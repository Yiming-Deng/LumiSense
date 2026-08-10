package com.oppovisual.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LatencyWindowTest {
    @Test
    fun calculatesNearestRankPercentilesAndEvictsOldValues() {
        val window = LatencyWindow(capacity = 4)
        assertNull(window.summary())
        listOf(10L, 20L, 30L, 40L, 50L).forEach(window::add)

        val summary = window.summary()
        assertEquals(4, summary?.sampleCount)
        assertEquals(30, summary?.p50Ms)
        assertEquals(50, summary?.p95Ms)
        assertEquals(50, summary?.p99Ms)
        assertEquals(50, summary?.maximumMs)
    }
}
