package com.oppovisual.app.recognition

import com.oppovisual.core.GestureId
import com.oppovisual.core.Point3
import com.oppovisual.core.ProductInteractionStatus
import com.oppovisual.core.ProductScaleStatus

data class NormalizedBoundingBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        require(left in 0f..1f && top in 0f..1f && right in 0f..1f && bottom in 0f..1f) {
            "Bounding-box coordinates must be normalized to [0, 1]"
        }
        require(right >= left && bottom >= top) { "Bounding box must have non-negative size" }
    }
}

data class GesturePrediction(
    val gesture: GestureId,
    val confidence: Float,
)

data class PairGesturePrediction(
    val gesture: GestureId,
    val confidence: Float,
    val participantTrackIds: Set<Int>,
) {
    init {
        require(gesture.requiredHands == 2) { "Pair prediction requires a two-hand gesture" }
        require(participantTrackIds.size == 2) { "Pair prediction requires exactly two track IDs" }
    }
}

data class ScaleEventParameter(
    val scaleFactor: Float,
) {
    init {
        require(scaleFactor.isFinite() && scaleFactor > 0f) {
            "Scale factor must be finite and positive"
        }
    }
}

data class ConfirmedGestureEvent(
    val gesture: GestureId,
    val confidence: Float,
    val participantTrackIds: Set<Int>,
    val startTimestampMs: Long,
    val confirmedTimestampMs: Long,
    val scaleParameter: ScaleEventParameter? = null,
) {
    init {
        require(
            participantTrackIds.size == gesture.requiredHands ||
                (gesture in setOf(GestureId.ZOOM_IN, GestureId.ZOOM_OUT) && participantTrackIds.size == 2)
        ) {
            "Event participant count must match the gesture contract"
        }
        require(confirmedTimestampMs >= startTimestampMs) {
            "Event confirmation cannot precede its start"
        }
        require(scaleParameter == null || gesture in setOf(
            GestureId.ZOOM_IN,
            GestureId.ZOOM_OUT,
            GestureId.TWO_HAND_ZOOM,
        )) {
            "Only zoom events may carry a scale parameter"
        }
    }
}

data class ComponentLatency(
    val preprocessingMs: Long = 0,
    val detectorMs: Long = 0,
    val classifierMs: Long = 0,
    val postprocessingMs: Long = 0,
    val trackingAndPolicyMs: Long = 0,
    val totalMs: Long,
)

data class RecognizedHand(
    val trackId: Int,
    val handedness: String?,
    val displayGesture: GestureId?,
    val displayConfidence: Float,
    val confirmedEvent: GestureId?,
    val eventConfidence: Float,
    val landmarks: List<Point3>,
    val boundingBox: NormalizedBoundingBox? = null,
    val staticPrediction: GesturePrediction? = displayGesture
        ?.takeIf { !it.isDynamic && it.requiredHands == 1 }
        ?.let { GesturePrediction(it, displayConfidence) },
    val dynamicPrediction: GesturePrediction? = displayGesture
        ?.takeIf { it.isDynamic }
        ?.let { GesturePrediction(it, displayConfidence) },
)

data class FrameRecognition(
    val timestampMs: Long,
    val hands: List<RecognizedHand>,
    val inputWidth: Int,
    val inputHeight: Int,
    val processingLatencyMs: Long,
    val averageLuma: Float,
    val pairPrediction: PairGesturePrediction? = null,
    val confirmedEvents: List<ConfirmedGestureEvent> = emptyList(),
    val componentLatency: ComponentLatency = ComponentLatency(totalMs = processingLatencyMs),
    val interactionStatus: ProductInteractionStatus = ProductInteractionStatus.IDLE,
    val activeScaleParameter: ScaleEventParameter? = null,
    val scaleStatus: ProductScaleStatus = ProductScaleStatus.IDLE,
) {
    val primaryHand: RecognizedHand? = hands
        .sortedWith(
            compareByDescending<RecognizedHand> { it.confirmedEvent != null }
                .thenByDescending { if (it.confirmedEvent != null) it.eventConfidence else it.displayConfidence },
        )
        .firstOrNull()
    val displayGesture: GestureId? get() = pairPrediction?.gesture ?: primaryHand?.displayGesture
    val displayConfidence: Float get() = pairPrediction?.confidence ?: primaryHand?.displayConfidence ?: 0f
    val primaryEvent: ConfirmedGestureEvent? = confirmedEvents.maxWithOrNull(
        compareBy<ConfirmedGestureEvent> { it.gesture.requiredHands }
            .thenBy { if (it.gesture.isDynamic) 1 else 0 }
            .thenBy { it.confidence },
    )
    val confirmedEvent: GestureId? get() = primaryEvent?.gesture ?: primaryHand?.confirmedEvent
    val eventConfidence: Float get() = primaryEvent?.confidence ?: primaryHand?.eventConfidence ?: 0f
    val landmarks: List<Point3> get() = primaryHand?.landmarks.orEmpty()
    val allLandmarks: List<List<Point3>> get() = hands.map { it.landmarks }
    val handPresent: Boolean get() = hands.isNotEmpty()
}
