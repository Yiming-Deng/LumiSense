package com.oppovisual.app.recognition

import com.oppovisual.core.GestureId
import com.oppovisual.core.ProductBox
import com.oppovisual.core.ProductKeypoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class R8ProductPipelineTest {
    @Test
    fun `suppresses holy before product tracking display and events`() {
        val result = R8ProductPipeline().update(
            timestampMs = 100,
            detections = listOf(detection("holy", GestureId.HOLY)),
        )

        assertTrue(result.hands.isEmpty())
        assertNull(result.pairPrediction)
        assertTrue(result.events.isEmpty())
    }

    @Test
    fun `keeps supported detections when holy is present`() {
        val result = R8ProductPipeline().update(
            timestampMs = 100,
            detections = listOf(
                detection("holy", GestureId.HOLY),
                detection("palm", GestureId.OPEN_PALM, left = 0.55f),
            ),
        )

        assertEquals(1, result.hands.size)
        assertEquals(GestureId.OPEN_PALM, result.hands.single().displayGesture)
        assertNull(result.pairPrediction)
    }

    @Test
    fun `does not report ready for a hand that disappeared from the current frame`() {
        val pipeline = R8ProductPipeline()
        pipeline.update(100, listOf(detection("palm", GestureId.OPEN_PALM)))
        val ready = pipeline.update(134, listOf(detection("palm", GestureId.OPEN_PALM)))
        assertEquals(com.oppovisual.core.ProductInteractionStatus.READY, ready.status)

        val missing = pipeline.update(168, emptyList())
        assertEquals(com.oppovisual.core.ProductInteractionStatus.IDLE, missing.status)
        assertTrue(missing.hands.isEmpty())
    }

    private fun detection(
        label: String,
        gesture: GestureId,
        left: Float = 0.1f,
    ) = R8Detection(
        box = ProductBox(left, 0.1f, left + 0.3f, 0.5f),
        score = 0.95f,
        datasetLabel = label,
        gesture = gesture,
        keypoints = List(21) { ProductKeypoint(left + 0.1f, 0.2f, 0.9f) },
    )
}
