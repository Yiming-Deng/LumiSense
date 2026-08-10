package com.oppovisual.app.recognition

import com.oppovisual.core.GestureId
import com.oppovisual.core.ProductBox
import com.oppovisual.core.ProductHandObservation
import com.oppovisual.core.ProductKeypoint
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import java.util.PriorityQueue

internal data class R8Letterbox(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val scale: Float,
    val padX: Float,
    val padY: Float,
)

internal data class R8Detection(
    val box: ProductBox,
    val score: Float,
    val datasetLabel: String,
    val gesture: GestureId?,
    val keypoints: List<ProductKeypoint>,
)

internal object R8OutputParser {
    const val DEFAULT_INPUT_SIZE = 640
    const val CANDIDATE_COUNT = 300
    const val ROW_SIZE = 69
    const val KEYPOINT_COUNT = 21
    const val CLASSIFICATION_SCORE = 0.15f
    const val RAW_CANDIDATE_COUNT = 8400
    const val RAW_ROW_SIZE = 101
    private const val MAX_HANDS = 2
    private const val RAW_TOP_CANDIDATES = 300
    private const val RAW_CLASS_OFFSET = 4
    private const val RAW_KEYPOINT_OFFSET = RAW_CLASS_OFFSET + 34
    private const val DUPLICATE_IOU = 0.50f
    private const val DUPLICATE_INTERSECTION_OVER_SMALLER = 0.75f
    private const val DUPLICATE_KEYPOINT_DISTANCE = 0.18f
    private const val DUPLICATE_FALLBACK_IOU = 0.90f
    private const val MIN_COMMON_DUPLICATE_KEYPOINTS = 3
    private const val VALID_KEYPOINT_CONFIDENCE = 0.25f
    private val DUPLICATE_KEYPOINTS = intArrayOf(0, 5, 9, 13, 17)

    private data class ScoredRawCandidate(
        val index: Int,
        val score: Float,
        val classId: Int,
    )

    val datasetLabels = listOf(
        "call", "dislike", "fist", "four", "grabbing", "grip", "hand_heart", "hand_heart2",
        "holy", "like", "little_finger", "middle_finger", "mute", "ok", "one", "palm", "peace",
        "peace_inverted", "point", "rock", "stop", "stop_inverted", "take_picture", "three", "three2",
        "three3", "three_gun", "thumb_index", "thumb_index2", "timeout", "two_up", "two_up_inverted",
        "xsign", "no_gesture",
    )
    private val gesturesByLabel = GestureId.entries.associateBy(GestureId::datasetLabel)

    fun letterbox(
        sourceWidth: Int,
        sourceHeight: Int,
        inputSize: Int = DEFAULT_INPUT_SIZE,
    ): R8Letterbox {
        require(sourceWidth > 0 && sourceHeight > 0)
        require(inputSize > 0 && inputSize % 32 == 0)
        val scale = min(inputSize.toFloat() / sourceWidth, inputSize.toFloat() / sourceHeight)
        val resizedWidth = (sourceWidth * scale).roundToInt()
        val resizedHeight = (sourceHeight * scale).roundToInt()
        return R8Letterbox(
            sourceWidth,
            sourceHeight,
            scale,
            (inputSize - resizedWidth) / 2f,
            (inputSize - resizedHeight) / 2f,
        )
    }

    fun parse(output: FloatArray, transform: R8Letterbox): List<R8Detection> {
        return when (output.size) {
            CANDIDATE_COUNT * ROW_SIZE -> parseFused(output, transform)
            RAW_CANDIDATE_COUNT * RAW_ROW_SIZE -> parseRaw(output, transform)
            else -> throw IllegalArgumentException(
                "R8 output must contain ${CANDIDATE_COUNT * ROW_SIZE} fused or " +
                    "${RAW_CANDIDATE_COUNT * RAW_ROW_SIZE} raw floats, got ${output.size}",
            )
        }
    }

    private fun parseFused(output: FloatArray, transform: R8Letterbox): List<R8Detection> {
        val candidates = buildList {
            for (row in 0 until CANDIDATE_COUNT) {
                val offset = row * ROW_SIZE
                val score = output[offset + 4]
                if (!score.isFinite() || score < CLASSIFICATION_SCORE) continue
                val rawClass = output[offset + 5]
                val classId = rawClass.roundToInt()
                if (!rawClass.isFinite() || abs(rawClass - classId) > 0.01f || classId !in datasetLabels.indices) continue
                val box = mapBox(output, offset, transform) ?: continue
                val keypoints = (0 until KEYPOINT_COUNT).map { index ->
                    val base = offset + 6 + index * 3
                    ProductKeypoint(
                        mapX(output[base], transform),
                        mapY(output[base + 1], transform),
                        output[base + 2].coerceIn(0f, 1f),
                    )
                }
                val label = datasetLabels[classId]
                add(R8Detection(box, score.coerceIn(0f, 1f), label, gesturesByLabel[label], keypoints))
            }
        }
        return selectDetections(candidates)
    }

    private fun parseRaw(output: FloatArray, transform: R8Letterbox): List<R8Detection> {
        val strongest = PriorityQueue<ScoredRawCandidate>(
            compareBy(ScoredRawCandidate::score).thenByDescending(ScoredRawCandidate::index),
        )
        for (candidate in 0 until RAW_CANDIDATE_COUNT) {
            var bestClass = -1
            var bestScore = Float.NEGATIVE_INFINITY
            for (classId in datasetLabels.indices) {
                val score = rawValue(output, RAW_CLASS_OFFSET + classId, candidate)
                if (score.isFinite() && score > bestScore) {
                    bestClass = classId
                    bestScore = score
                }
            }
            if (bestClass < 0 || bestScore < CLASSIFICATION_SCORE) continue
            val scored = ScoredRawCandidate(candidate, bestScore, bestClass)
            if (strongest.size < RAW_TOP_CANDIDATES) {
                strongest += scored
            } else if (requireNotNull(strongest.peek()).let { weakest ->
                    bestScore > weakest.score ||
                        (bestScore == weakest.score && candidate < weakest.index)
                }
            ) {
                strongest.poll()
                strongest += scored
            }
        }

        val candidates = strongest
            .sortedWith(
                compareByDescending(ScoredRawCandidate::score).thenBy(ScoredRawCandidate::index),
            )
            .mapNotNull { raw ->
            val box = mapBox(
                rawValue(output, 0, raw.index),
                rawValue(output, 1, raw.index),
                rawValue(output, 2, raw.index),
                rawValue(output, 3, raw.index),
                transform,
            ) ?: return@mapNotNull null
            val keypoints = (0 until KEYPOINT_COUNT).map { keypoint ->
                val channel = RAW_KEYPOINT_OFFSET + keypoint * 3
                ProductKeypoint(
                    mapX(rawValue(output, channel, raw.index), transform),
                    mapY(rawValue(output, channel + 1, raw.index), transform),
                    rawValue(output, channel + 2, raw.index).coerceIn(0f, 1f),
                )
            }
            val label = datasetLabels[raw.classId]
            R8Detection(box, raw.score.coerceIn(0f, 1f), label, gesturesByLabel[label], keypoints)
            }
        return selectDetections(candidates)
    }

    private fun selectDetections(candidates: List<R8Detection>): List<R8Detection> {
        val sorted = candidates.sortedByDescending(R8Detection::score)

        val selected = mutableListOf<R8Detection>()
        sorted.forEach { candidate ->
            if (selected.none { isSuppressibleDuplicate(it, candidate) }) selected += candidate
            if (selected.size == MAX_HANDS) return selected
        }
        return selected
    }

    private fun isSuppressibleDuplicate(selected: R8Detection, candidate: R8Detection): Boolean {
        val overlap = boxOverlap(selected.box, candidate.box)
        if (
            overlap.iou < DUPLICATE_IOU &&
            overlap.intersectionOverSmaller < DUPLICATE_INTERSECTION_OVER_SMALLER
        ) return false

        val normalizedKeypointDistance = palmKeypointDistance(selected, candidate)
        return normalizedKeypointDistance?.let { it <= DUPLICATE_KEYPOINT_DISTANCE }
            ?: (overlap.iou >= DUPLICATE_FALLBACK_IOU)
    }

    private fun palmKeypointDistance(first: R8Detection, second: R8Detection): Float? {
        val boxDiagonal = (
            hypot(first.box.width, first.box.height) +
                hypot(second.box.width, second.box.height)
            ) / 2f
        if (boxDiagonal <= 0f) return null
        var distance = 0f
        var count = 0
        DUPLICATE_KEYPOINTS.forEach { index ->
            val firstPoint = first.keypoints.getOrNull(index) ?: return@forEach
            val secondPoint = second.keypoints.getOrNull(index) ?: return@forEach
            if (
                firstPoint.confidence < VALID_KEYPOINT_CONFIDENCE ||
                secondPoint.confidence < VALID_KEYPOINT_CONFIDENCE
            ) return@forEach
            distance += hypot(firstPoint.x - secondPoint.x, firstPoint.y - secondPoint.y)
            count++
        }
        return if (count >= MIN_COMMON_DUPLICATE_KEYPOINTS) {
            distance / count / boxDiagonal
        } else {
            null
        }
    }

    private fun rawValue(output: FloatArray, channel: Int, candidate: Int): Float =
        output[channel * RAW_CANDIDATE_COUNT + candidate]

    fun toObservation(trackId: Int, detection: R8Detection) = ProductHandObservation(
        trackId,
        detection.box,
        detection.score,
        detection.datasetLabel,
        detection.keypoints,
    )

    private fun mapBox(output: FloatArray, offset: Int, transform: R8Letterbox): ProductBox? {
        return mapBox(output[offset], output[offset + 1], output[offset + 2], output[offset + 3], transform)
    }

    private fun mapBox(
        rawLeft: Float,
        rawTop: Float,
        rawRight: Float,
        rawBottom: Float,
        transform: R8Letterbox,
    ): ProductBox? {
        val left = mapX(rawLeft, transform)
        val top = mapY(rawTop, transform)
        val right = mapX(rawRight, transform)
        val bottom = mapY(rawBottom, transform)
        return if (right - left > 1e-4f && bottom - top > 1e-4f) ProductBox(left, top, right, bottom) else null
    }

    private fun mapX(value: Float, transform: R8Letterbox): Float =
        ((value - transform.padX) / transform.scale / transform.sourceWidth).coerceIn(0f, 1f)

    private fun mapY(value: Float, transform: R8Letterbox): Float =
        ((value - transform.padY) / transform.scale / transform.sourceHeight).coerceIn(0f, 1f)

    private fun intersectionOverUnion(first: ProductBox, second: ProductBox): Float {
        return boxOverlap(first, second).iou
    }

    private data class BoxOverlap(
        val iou: Float,
        val intersectionOverSmaller: Float,
    )

    private fun boxOverlap(first: ProductBox, second: ProductBox): BoxOverlap {
        val width = max(0f, min(first.right, second.right) - max(first.left, second.left))
        val height = max(0f, min(first.bottom, second.bottom) - max(first.top, second.top))
        val intersection = width * height
        val firstArea = first.width * first.height
        val secondArea = second.width * second.height
        val union = firstArea + secondArea - intersection
        val smaller = min(firstArea, secondArea)
        return BoxOverlap(
            iou = if (union <= 0f) 0f else intersection / union,
            intersectionOverSmaller = if (smaller <= 0f) 0f else intersection / smaller,
        )
    }
}
