package com.oppovisual.app.recognition

import com.oppovisual.core.HandTrackAssigner
import com.oppovisual.core.HandTrackObservation
import com.oppovisual.core.GestureId
import com.oppovisual.core.Point3
import com.oppovisual.core.ProductInteractionDecoder
import com.oppovisual.core.ProductInteractionStatus

internal data class R8ProcessedDetections(
    val hands: List<RecognizedHand>,
    val pairPrediction: PairGesturePrediction?,
    val events: List<ConfirmedGestureEvent>,
    val status: ProductInteractionStatus,
    val activeScaleParameter: ScaleEventParameter?,
    val scaleStatus: com.oppovisual.core.ProductScaleStatus,
)

/** Shared tracking and product-event layer for the LiteRT and QNN R8 backends. */
internal class R8ProductPipeline {
    private val trackAssigner = HandTrackAssigner()
    private val decoder = ProductInteractionDecoder()

    fun update(timestampMs: Long, detections: List<R8Detection>): R8ProcessedDetections {
        val productDetections = detections.filterNot { it.gesture == GestureId.HOLY }
        val assignments = trackAssigner.assign(
            timestampMs,
            productDetections.map { HandTrackObservation(it.box.centerX, it.box.centerY, null) },
        )
        val tracked = assignments.associate { it.observationIndex to it.trackId }
        val observations = productDetections.mapIndexed { index, detection ->
            R8OutputParser.toObservation(requireNotNull(tracked[index]), detection)
        }
        val decoded = decoder.update(timestampMs, observations)
        val events = decoded.events.map { event ->
            ConfirmedGestureEvent(
                gesture = event.gesture,
                confidence = event.confidence,
                participantTrackIds = event.participantTrackIds,
                startTimestampMs = event.startTimestampMs,
                confirmedTimestampMs = event.confirmedTimestampMs,
                scaleParameter = event.scaleFactor?.let(::ScaleEventParameter),
            )
        }
        val eventByTrack = events.flatMap { event ->
            event.participantTrackIds.map { it to event }
        }.toMap()
        val hands = productDetections.mapIndexed { index, detection ->
            val trackId = requireNotNull(tracked[index])
            val event = eventByTrack[trackId]
            RecognizedHand(
                trackId = trackId,
                handedness = null,
                displayGesture = detection.gesture,
                displayConfidence = detection.score,
                confirmedEvent = event?.gesture,
                eventConfidence = event?.confidence ?: 0f,
                landmarks = detection.keypoints.map { Point3(it.x, it.y, 0f) },
                boundingBox = detection.box.let {
                    NormalizedBoundingBox(it.left, it.top, it.right, it.bottom)
                },
            )
        }
        val pairPrediction = productDetections
            .mapIndexedNotNull { index, detection ->
                detection.gesture?.takeIf { it.requiredHands == 2 }?.let { index to it }
            }
            .groupBy({ it.second }, { it.first })
            .entries
            .firstOrNull { it.value.size >= 2 }
            ?.let { (gesture, indexes) ->
                val firstTwo = indexes.take(2)
                PairGesturePrediction(
                    gesture,
                    firstTwo.minOf { productDetections[it].score },
                    firstTwo.mapTo(mutableSetOf()) { requireNotNull(tracked[it]) },
                )
            }
        // The decoder retains missing tracks briefly for motion recovery. The
        // UI's READY affordance must only reflect hands visible in this frame,
        // so stale ready states cannot leak into the current preview.
        val currentTrackIds = observations.mapTo(mutableSetOf()) { it.trackId }
        val status = decoded.statuses
            .filterKeys { it in currentTrackIds }
            .values
            .maxByOrNull {
                when (it) {
                    ProductInteractionStatus.TRACKING -> 4
                    ProductInteractionStatus.READY -> 3
                    ProductInteractionStatus.HOLD_STILL -> 2
                    ProductInteractionStatus.RETURNING -> 1
                    ProductInteractionStatus.IDLE -> 0
                }
            } ?: ProductInteractionStatus.IDLE
        return R8ProcessedDetections(
            hands,
            pairPrediction,
            events,
            status,
            decoded.scalePreview?.scaleFactor?.let(::ScaleEventParameter),
            decoded.scaleStatus,
        )
    }

    fun reset() {
        decoder.reset()
        trackAssigner.reset()
    }
}
