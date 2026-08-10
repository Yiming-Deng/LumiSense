package com.oppovisual.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class HandTrackAssignerTest {
    @Test
    fun keepsTwoTrackIdsWhenDetectorOrderChanges() {
        val assigner = HandTrackAssigner()
        val first = assigner.assign(
            0,
            listOf(hand(0.2f, "Left"), hand(0.8f, "Right")),
        )
        val reordered = assigner.assign(
            33,
            listOf(hand(0.78f, "Right"), hand(0.22f, "Left")),
        )

        assertEquals(first[1].trackId, reordered[0].trackId)
        assertEquals(first[0].trackId, reordered[1].trackId)
        assertNotEquals(reordered[0].trackId, reordered[1].trackId)
    }

    @Test
    fun doesNotAssignOneTrackToBothHands() {
        val assigner = HandTrackAssigner()
        assigner.assign(0, listOf(hand(0.5f, null)))

        val assignments = assigner.assign(33, listOf(hand(0.45f, null), hand(0.55f, null)))

        assertEquals(2, assignments.map { it.trackId }.toSet().size)
    }

    @Test
    fun createsNewTrackAfterTimeout() {
        val assigner = HandTrackAssigner(HandTrackConfig(timeoutMs = 100))
        val firstId = assigner.assign(0, listOf(hand(0.5f, "Left"))).single().trackId

        val laterId = assigner.assign(101, listOf(hand(0.5f, "Left"))).single().trackId

        assertNotEquals(firstId, laterId)
        assertEquals(setOf(laterId), assigner.activeTrackIds())
    }

    private fun hand(x: Float, handedness: String?) = HandTrackObservation(x, 0.5f, handedness)
}
