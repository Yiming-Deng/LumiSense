package com.oppovisual.app.recognition

import com.oppovisual.core.GestureId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FrameRecognitionTest {
    @Test
    fun `only zoom events accept scale parameters`() {
        val zoom = ConfirmedGestureEvent(
            GestureId.ZOOM_IN,
            0.9f,
            setOf(1),
            10,
            20,
            ScaleEventParameter(1.25f),
        )
        assertEquals(1.25f, zoom.scaleParameter?.scaleFactor)
        val twoHandZoom = ConfirmedGestureEvent(
            GestureId.TWO_HAND_ZOOM,
            0.9f,
            setOf(1, 2),
            10,
            20,
            ScaleEventParameter(0.8f),
        )
        assertEquals(0.8f, twoHandZoom.scaleParameter?.scaleFactor)
        assertThrows(IllegalArgumentException::class.java) {
            ConfirmedGestureEvent(
                GestureId.SWIPE_LEFT,
                0.9f,
                setOf(1),
                10,
                20,
                ScaleEventParameter(1.25f),
            )
        }
    }

    @Test
    fun pairPredictionTakesDisplayPriorityWithoutBreakingPerHandResults() {
        val frame = frame(
            hands = listOf(hand(1, GestureId.OPEN_PALM), hand(2, GestureId.CLOSED_FIST)),
            pairPrediction = PairGesturePrediction(
                GestureId.HAND_HEART,
                0.91f,
                setOf(1, 2),
            ),
        )

        assertEquals(GestureId.HAND_HEART, frame.displayGesture)
        assertEquals(2, frame.hands.size)
    }

    @Test
    fun confirmedPairEventTakesCompatibilityAccessorPriority() {
        val frame = frame(
            hands = listOf(hand(1, GestureId.SWIPE_LEFT, confirmed = GestureId.SWIPE_LEFT)),
            events = listOf(
                ConfirmedGestureEvent(GestureId.SWIPE_LEFT, 0.8f, setOf(1), 10, 20),
                ConfirmedGestureEvent(GestureId.HOLY, 0.9f, setOf(1, 2), 5, 20),
            ),
        )

        assertEquals(GestureId.HOLY, frame.confirmedEvent)
        assertEquals(0.9f, frame.eventConfidence)
    }

    @Test
    fun normalizedBoundingBoxRejectsPixelCoordinates() {
        assertThrows(IllegalArgumentException::class.java) {
            NormalizedBoundingBox(10f, 20f, 30f, 40f)
        }
    }

    private fun hand(trackId: Int, gesture: GestureId, confirmed: GestureId? = null) = RecognizedHand(
        trackId = trackId,
        handedness = null,
        displayGesture = gesture,
        displayConfidence = 0.8f,
        confirmedEvent = confirmed,
        eventConfidence = if (confirmed == null) 0f else 0.8f,
        landmarks = emptyList(),
    )

    private fun frame(
        hands: List<RecognizedHand>,
        pairPrediction: PairGesturePrediction? = null,
        events: List<ConfirmedGestureEvent> = emptyList(),
    ) = FrameRecognition(
        timestampMs = 20,
        hands = hands,
        inputWidth = 640,
        inputHeight = 480,
        processingLatencyMs = 40,
        averageLuma = 100f,
        pairPrediction = pairPrediction,
        confirmedEvents = events,
    )
}
