package com.oppovisual.core

import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

data class ProductInteractionConfig(
    val classificationScore: Float = 0.15f,
    val classHistoryFrames: Int = 5,
    val classVoteFrames: Int = 3,
    val poseGraceFrames: Int = 2,
    val swipeArmClasses: Set<String> = setOf("palm", "stop"),
    /** Starting classes used by the intentional vertical gesture transitions. */
    val verticalStartClasses: Set<String> = setOf("point", "fist", "stop"),
    val verticalTransitionGraceFrames: Int = 3,
    val verticalTransitionConfirmFrames: Int = 2,
    val semanticBridgeFrames: Int = 8,
    val singleZoomBridgeFrames: Int = 24,
    val noGestureBridgeFrames: Int = 5,
    val keypointConfidence: Float = 0.25f,
    val fastReadyScore: Float = 0.30f,
    val fastReadyFrames: Int = 2,
    val fastReadyMs: Long = 15L,
    val startHandoffFrames: Int = 2,
    val pointArmGraceFrames: Int = 3,
    val readyMs: Long = 160L,
    val readyMaxPath: Float = 0.10f,
    val readyMaxDrift: Float = 0.06f,
    val armedTimeoutMs: Long = 700L,
    val onsetDistance: Float = 0.10f,
    val onsetDominance: Float = 1.30f,
    val actionMs: Long = 1_000L,
    val zoomActionMs: Long = 2_000L,
    val swipeDistance: Float = 0.50f,
    val swipeDominance: Float = 1.50f,
    val swipeEfficiency: Float = 0.60f,
    val landmarkAgreement: Float = 0.70f,
    val confirmFrames: Int = 2,
    val horizontalCooldownMs: Long = 1_000L,
    val returningMinMs: Long = 180L,
    val missingReleaseFrames: Int = 8,
    val zoomPoseFrames: Int = 3,
    /** Single-hand pose changes are easier to miss than the two-hand arm pose. */
    val singleZoomPoseFrames: Int = 2,
    val singleZoomScaleFactor: Float = 1.55f,
    val pairScaleDeadZone: Float = 0.08f,
) {
    init {
        require(classificationScore in 0f..1f)
        require(classHistoryFrames >= classVoteFrames && classVoteFrames > 0)
        require(poseGraceFrames >= 0 && missingReleaseFrames > 0)
        require(swipeArmClasses.isNotEmpty() && swipeArmClasses.all { it in SWIPE_CLASSES })
        require(verticalStartClasses.isNotEmpty() && verticalStartClasses.all { it in SWIPE_CLASSES })
        require(verticalTransitionGraceFrames >= 0 && verticalTransitionConfirmFrames > 0)
        require(semanticBridgeFrames > 0)
        require(singleZoomBridgeFrames > 0)
        require(noGestureBridgeFrames > 0)
        require(keypointConfidence in 0f..1f && fastReadyScore in classificationScore..1f)
        require(fastReadyFrames >= 2)
        require(startHandoffFrames >= 2 && pointArmGraceFrames > 0)
        require(fastReadyMs > 0 && readyMs > 0 && fastReadyMs < readyMs)
        require(armedTimeoutMs > 0 && actionMs > 0 && zoomActionMs > 0)
        require(readyMaxPath > 0f && readyMaxDrift > 0f)
        require(onsetDistance > 0f && onsetDominance > 0f)
        require(swipeDistance > 0f && swipeDominance > 0f)
        require(swipeEfficiency in 0f..1f && landmarkAgreement in 0f..1f)
        require(confirmFrames > 0 && horizontalCooldownMs >= 0 && returningMinMs > 0 &&
            zoomPoseFrames > 0 && singleZoomPoseFrames > 0)
        require(singleZoomScaleFactor > 1f)
        require(pairScaleDeadZone in 0f..1f)
    }

    companion object {
        val SWIPE_CLASSES = setOf(
            "fist", "grabbing", "grip", "stop", "stop_inverted", "point", "one", "palm", "two_up",
        )
        val UP_TRANSITIONS = setOf(
            "fist" to "stop",
        )
        val DOWN_TRANSITIONS = setOf(
            "stop" to "stop_inverted",
            "point" to "stop_inverted",
            "stop" to "point",
        )
        val VERTICAL_RELEASE_CLASSES = (UP_TRANSITIONS + DOWN_TRANSITIONS)
            .flatMap { listOf(it.first, it.second) }
            .toSet()
    }
}

data class ProductBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    init {
        require(listOf(left, top, right, bottom).all(Float::isFinite))
        require(width > 0f && height > 0f)
    }
}

data class ProductKeypoint(val x: Float, val y: Float, val confidence: Float)

data class ProductHandObservation(
    val trackId: Int,
    val box: ProductBox,
    val score: Float,
    val datasetLabel: String,
    val keypoints: List<ProductKeypoint>,
)

enum class ProductInteractionStatus { IDLE, HOLD_STILL, READY, TRACKING, RETURNING }

enum class ProductScaleStatus { IDLE, READY, ADJUSTING, PAUSED }

data class ProductGestureEvent(
    val gesture: GestureId,
    val confidence: Float,
    val participantTrackIds: Set<Int>,
    val startTimestampMs: Long,
    val confirmedTimestampMs: Long,
    val scaleFactor: Float? = null,
)

data class ProductScalePreview(
    val participantTrackIds: Set<Int>,
    val scaleFactor: Float,
) {
    init {
        require(participantTrackIds.size == 2)
        require(scaleFactor.isFinite() && scaleFactor > 0f)
    }
}

data class ProductDecoderUpdate(
    val events: List<ProductGestureEvent>,
    val statuses: Map<Int, ProductInteractionStatus>,
    val scalePreview: ProductScalePreview? = null,
    val scaleStatus: ProductScaleStatus = ProductScaleStatus.IDLE,
)

class ProductInteractionDecoder(
    private val config: ProductInteractionConfig = ProductInteractionConfig(),
) {
    private data class MotionSample(
        val timestampMs: Long,
        val category: String,
        val centerX: Float,
        val centerY: Float,
        val width: Float,
        val height: Float,
        val palmX: Float,
        val palmY: Float,
        val palmValid: Boolean,
    ) {
        val diagonal: Float get() = hypot(width, height)
    }

    private data class ClassFrame(val timestampMs: Long, val category: String)

    private data class ClassPattern(
        val gesture: GestureId,
        val nodes: List<String>,
    )

    private data class ClassMatch(
        val gesture: GestureId,
        val startTimestampMs: Long,
    )

    private enum class Phase { IDLE, ARMED, ACTIVE, RETURNING }

    private data class TrackRuntime(
        val classHistory: ArrayDeque<Pair<String?, Float>> = ArrayDeque(),
        val restSamples: ArrayDeque<MotionSample> = ArrayDeque(),
        val activeSamples: ArrayDeque<MotionSample> = ArrayDeque(),
        val classFrames: ArrayDeque<ClassFrame> = ArrayDeque(),
        var phase: Phase = Phase.IDLE,
        var stableClass: String? = null,
        var fastArmCount: Int = 0,
        var fastArmClass: String? = null,
        var fastArmGap: Int = 0,
        var poseGap: Int = 0,
        var missingFrames: Int = 0,
        var resumedThisFrame: Boolean = false,
        var armedAnchor: MotionSample? = null,
        var armedClass: String? = null,
        var armedStartMs: Long = -1,
        var armedHandoffClass: String? = null,
        var armedHandoffCount: Int = 0,
        var armedHandoffGap: Int = 0,
        var activeDirection: GestureId? = null,
        var activeStartMs: Long = -1,
        var swipeConfirm: Int = 0,
        var horizontalCooldownUntilMs: Long = Long.MIN_VALUE,
        var pendingVerticalDirection: GestureId? = null,
        var verticalTargetClass: String? = null,
        var verticalTargetCount: Int = 0,
        var verticalTransitionSeen: Boolean = false,
        var verticalTransitionGap: Int = 0,
        var verticalBridgeFrames: Int = 0,
        var returningStartMs: Long = -1,
        var returningGapFrames: Int = 0,
        var zoomStartCandidateClass: String? = null,
        var zoomStartCount: Int = 0,
        var zoomStartClass: String? = null,
        var zoomStartMs: Long = -1,
        var zoomPausedSinceMs: Long = -1,
        var zoomTargetClass: String? = null,
        var zoomTargetCount: Int = 0,
        var zoomBridgeFrames: Int = 0,
    )

    private data class PairSample(
        val timestampMs: Long,
        val firstX: Float,
        val firstY: Float,
        val secondX: Float,
        val secondY: Float,
        val midpointX: Float,
        val midpointY: Float,
        val averageDiagonal: Float,
        val separation: Float,
    )

    private enum class PairPhase { WAITING_FOR_PINCH, WAITING_FOR_FISTS, ACTIVE }

    private data class PairRuntime(
        var phase: PairPhase = PairPhase.WAITING_FOR_PINCH,
        var pinchFrames: Int = 0,
        var fistFrames: Int = 0,
        var transitionGap: Int = 0,
        var baseline: PairSample? = null,
        var activeStartMs: Long = -1,
        var scaleFactor: Float = 1f,
        var scaleAtBaseline: Float = 1f,
        var adjustmentActive: Boolean = false,
        var exitFrames: Int = 0,
    )

    private val tracks = mutableMapOf<Int, TrackRuntime>()
    private val pairRuntime = PairRuntime()
    private var pairIds: Pair<Int, Int>? = null
    private var lastTimestampMs = -1L

    fun reset() {
        tracks.clear()
        resetPairSession()
        lastTimestampMs = -1L
    }

    fun update(timestampMs: Long, observations: List<ProductHandObservation>): ProductDecoderUpdate {
        require(timestampMs > lastTimestampMs) { "timestamps must be strictly increasing" }
        require(observations.map { it.trackId }.distinct().size == observations.size) {
            "a frame cannot contain duplicate track IDs"
        }
        if (lastTimestampMs >= 0 && timestampMs - lastTimestampMs > GAP_RESET_MS) resetRuntimeOnly()
        lastTimestampMs = timestampMs

        val observedIds = observations.mapTo(mutableSetOf()) { it.trackId }
        tracks.values.forEach { it.resumedThisFrame = false }
        tracks.forEach { (trackId, runtime) ->
            if (trackId !in observedIds) {
                runtime.missingFrames++
                runtime.poseGap++
                runtime.fastArmCount = 0
                if (runtime.missingFrames >= config.missingReleaseFrames) {
                    runtime.classHistory.clear()
                    runtime.stableClass = null
                    resetTrack(runtime)
                }
            }
        }

        val samples = linkedMapOf<Int, MotionSample>()
        observations.forEach { observation ->
            val runtime = tracks.getOrPut(observation.trackId) { TrackRuntime() }
            val resumed = runtime.missingFrames > 0
            runtime.missingFrames = 0
            val raw = observation.datasetLabel.takeIf { observation.score >= config.classificationScore }
            val fastArmEligible = isFastArmEligible(raw, observation.score)
            when {
                fastArmEligible -> {
                    if (runtime.fastArmClass != raw) runtime.fastArmCount = 0
                    runtime.fastArmClass = raw
                    runtime.fastArmCount++
                    runtime.fastArmGap = 0
                }
                runtime.fastArmClass == POINT_CLASS && runtime.fastArmCount > 0 &&
                    runtime.fastArmGap < config.pointArmGraceFrames -> runtime.fastArmGap++
                else -> {
                    runtime.fastArmCount = 0
                    runtime.fastArmClass = null
                    runtime.fastArmGap = 0
                }
            }
            updateClass(runtime, raw, observation.score)
            val sample = motionSample(timestampMs, observation, runtime.stableClass ?: raw ?: NO_GESTURE)
            if (sample == null) {
                runtime.fastArmCount = 0
                runtime.poseGap++
                if (runtime.poseGap > config.poseGraceFrames && runtime.phase == Phase.ACTIVE) {
                    enterReturning(runtime, timestampMs)
                }
                return@forEach
            }
            samples[observation.trackId] = sample
            if (resumed) {
                runtime.resumedThisFrame = true
                runtime.fastArmCount = 0
                if (runtime.phase == Phase.ACTIVE) {
                    runtime.activeSamples.clear()
                    runtime.activeSamples.add(sample)
                    runtime.swipeConfirm = 0
                }
                clearZoom(runtime)
            }
        }

        val events = mutableListOf<ProductGestureEvent>()
        val pairUpdate = updatePairs(timestampMs, observations, samples, events)
        val singleEligibleIds = pairUpdate.singleEligibleIds
        observations.forEach { observation ->
            val trackId = observation.trackId
            val runtime = tracks.getValue(trackId)
            val raw = observation.datasetLabel.takeIf { observation.score >= config.classificationScore }
            val rawSupported = raw in ProductInteractionConfig.SWIPE_CLASSES
            runtime.poseGap = if (raw == null || raw == NO_GESTURE || !rawSupported) runtime.poseGap + 1 else 0

            if (trackId !in singleEligibleIds) return@forEach
            val sample = samples[trackId] ?: return@forEach
            updateSingleZoom(timestampMs, trackId, runtime, raw)?.let(events::add)
            if (events.lastOrNull()?.participantTrackIds?.contains(trackId) == true) return@forEach
            if (runtime.phase == Phase.RETURNING) {
                val releaseCategory = raw ?: NO_GESTURE
                updateReturning(
                    runtime,
                    sample.copy(category = releaseCategory),
                    readyForRelease = true,
                    rearmSwipe = true,
                    fastVerticalRearm = true,
                )
                return@forEach
            }
            if (runtime.phase == Phase.ARMED || runtime.phase == Phase.ACTIVE) {
                updateSwipe(
                    timestampMs,
                    trackId,
                    runtime,
                    sample,
                    transitionCategory = raw ?: sample.category,
                )?.let(events::add)
                return@forEach
            }
            if (runtime.phase == Phase.IDLE) {
                updateSwipe(
                    timestampMs,
                    trackId,
                    runtime,
                    sample.copy(category = raw ?: NO_GESTURE),
                    transitionCategory = raw ?: NO_GESTURE,
                )?.let(events::add)
                return@forEach
            }
            if (!rawSupported || runtime.stableClass !in ProductInteractionConfig.SWIPE_CLASSES) {
                val preservingPointEvidence = runtime.phase == Phase.IDLE &&
                    runtime.fastArmClass == POINT_CLASS && runtime.fastArmCount > 0 &&
                    runtime.fastArmGap <= config.pointArmGraceFrames
                if (runtime.poseGap > config.poseGraceFrames && !preservingPointEvidence) resetSwipe(runtime)
                return@forEach
            }
            updateSwipe(
                timestampMs,
                trackId,
                runtime,
                sample,
                transitionCategory = raw ?: sample.category,
            )?.let(events::add)
        }

        val statuses = tracks.mapValues { (_, runtime) ->
            when (runtime.phase) {
                Phase.IDLE -> if (runtime.restSamples.isEmpty()) ProductInteractionStatus.IDLE else ProductInteractionStatus.HOLD_STILL
                Phase.ARMED -> when {
                    runtime.pendingVerticalDirection != null || runtime.verticalTargetCount > 0 ->
                        ProductInteractionStatus.TRACKING
                    runtime.zoomTargetCount > 0 -> ProductInteractionStatus.TRACKING
                    timestampMs < runtime.horizontalCooldownUntilMs ->
                        ProductInteractionStatus.HOLD_STILL
                    runtime.armedClass != null && isReadyStartClass(runtime.armedClass) ->
                        ProductInteractionStatus.READY
                    else -> ProductInteractionStatus.HOLD_STILL
                }
                Phase.ACTIVE -> ProductInteractionStatus.TRACKING
                Phase.RETURNING -> ProductInteractionStatus.RETURNING
            }
        }
        return ProductDecoderUpdate(events, statuses, pairUpdate.scalePreview, pairUpdate.scaleStatus)
    }

    private fun updateClass(runtime: TrackRuntime, raw: String?, score: Float) {
        runtime.classHistory.addLast(raw to score)
        while (runtime.classHistory.size > config.classHistoryFrames) runtime.classHistory.removeFirst()
        if (runtime.classHistory.size < config.classHistoryFrames) {
            runtime.stableClass = null
            return
        }
        val grouped = runtime.classHistory
            .filter { it.first != null && it.second >= config.classificationScore }
            .groupBy { requireNotNull(it.first) }
        runtime.stableClass = grouped.entries
            .filter { it.value.size >= config.classVoteFrames }
            .maxWithOrNull(compareBy<Map.Entry<String, List<Pair<String?, Float>>>> { it.value.sumOf { row -> row.second.toDouble() } }
                .thenBy { it.value.size }
                .thenBy { it.key })
            ?.key
    }

    private fun motionSample(timestampMs: Long, observation: ProductHandObservation, category: String): MotionSample? {
        val palm = observation.keypoints.takeIf { it.size == LANDMARK_COUNT }
            ?.let { points -> PALM_POINTS.map { points[it] } }
        val palmValid = palm != null && palm.all { it.confidence >= config.keypointConfidence }
        return MotionSample(
            timestampMs = timestampMs,
            category = category,
            centerX = observation.box.centerX,
            centerY = observation.box.centerY,
            width = observation.box.width,
            height = observation.box.height,
            palmX = if (palmValid) requireNotNull(palm).map { it.x }.average().toFloat() else observation.box.centerX,
            palmY = if (palmValid) requireNotNull(palm).map { it.y }.average().toFloat() else observation.box.centerY,
            palmValid = palmValid,
        )
    }

    private fun updateSwipe(
        timestampMs: Long,
        trackId: Int,
        runtime: TrackRuntime,
        sample: MotionSample,
        transitionCategory: String = sample.category,
    ): ProductGestureEvent? {
        if (runtime.phase == Phase.IDLE) {
            addTrimmed(runtime.restSamples, sample, config.readyMs * 2)
            val fastSamples = runtime.restSamples.toList().takeLast(config.fastReadyFrames)
            val fastReady = fastSamples.size >= config.fastReadyFrames &&
                runtime.fastArmCount >= config.fastReadyFrames &&
                runtime.fastArmClass == fastSamples.lastOrNull()?.category &&
                stationary(fastSamples, config.fastReadyMs, includePalm = true)
            val safeReady = isReadyStartClass(runtime.stableClass) &&
                stationary(runtime.restSamples, config.readyMs, includePalm = true)
            if (fastReady || safeReady) {
                val readySamples = if (fastReady) fastSamples else runtime.restSamples.toList()
                runtime.phase = Phase.ARMED
                runtime.armedAnchor = anchor(readySamples)
                runtime.armedClass = runtime.armedAnchor?.category
                runtime.armedStartMs = timestampMs
                runtime.fastArmCount = 0
                runtime.fastArmClass = null
                runtime.classFrames.clear()
                readySamples.forEach { runtime.classFrames.addLast(ClassFrame(it.timestampMs, it.category)) }
            }
            return null
        }
        if (runtime.phase == Phase.ARMED) {
            val anchor = runtime.armedAnchor ?: run {
                resetSwipe(runtime)
                return null
            }
            runtime.activeSamples.clear()
            runtime.activeSamples.add(anchor)
            runtime.activeSamples.add(sample)
            updateArmedVerticalTransition(runtime, transitionCategory)?.let { direction ->
                enterReturning(runtime, timestampMs)
                return ProductGestureEvent(
                    direction,
                    1f,
                    setOf(trackId),
                    anchor.timestampMs,
                    timestampMs,
                )
            }
            updateArmedStartHandoff(runtime, sample, transitionCategory)
            // A pending vertical route owns the transition. Do not let the
            // generic horizontal handoff rewrite its armed class mid-bridge.
            if (runtime.pendingVerticalDirection != null) return null
            if (timestampMs < runtime.horizontalCooldownUntilMs) {
                runtime.armedAnchor = sample
                runtime.armedStartMs = timestampMs
                runtime.restSamples.clear()
                runtime.restSamples.add(sample)
                return null
            }
            updateHorizontalStartClass(runtime, anchor, sample, transitionCategory)
            val direction = selectHorizontalDirection(runtime.activeSamples, runtime.armedClass) ?: run {
                addTrimmed(runtime.restSamples, sample, config.readyMs * 2)
                if (stationary(runtime.restSamples, config.readyMs, includePalm = false)) {
                    runtime.armedAnchor = anchor(runtime.restSamples)
                    runtime.armedStartMs = timestampMs
                } else if (hasMeaningfulDisplacement(anchor, sample) &&
                    timestampMs - runtime.armedStartMs > config.armedTimeoutMs
                ) {
                    // A real movement that does not satisfy any allowed direction
                    // cancels the candidate. Small tracking jitter must not make a
                    // ready hand fall back to HOLD_STILL after a timeout.
                    resetSwipe(runtime)
                }
                return null
            }
            runtime.phase = Phase.ACTIVE
            runtime.activeDirection = direction
            runtime.activeStartMs = timestampMs
            runtime.swipeConfirm = 0
        } else if (runtime.phase == Phase.ACTIVE) {
            addTrimmed(runtime.activeSamples, sample, config.actionMs + config.readyMs)
        } else {
            return null
        }
        if (timestampMs - runtime.activeStartMs > config.actionMs) {
            enterReturning(runtime, timestampMs)
            return null
        }
        val direction = runtime.activeDirection ?: return null
        if (direction == GestureId.SWIPE_UP || direction == GestureId.SWIPE_DOWN) {
            when (updateVerticalTransition(runtime, transitionCategory, direction)) {
                VerticalTransitionResult.WAITING -> return null
                VerticalTransitionResult.CANCELLED -> {
                    resetSwipe(runtime)
                    return null
                }
                VerticalTransitionResult.CONFIRMED -> Unit
            }
        }
        val geometry = directionMetrics(runtime.activeSamples, direction)
        val passed = geometry.distance >= config.swipeDistance &&
            geometry.dominance >= config.swipeDominance &&
            geometry.efficiency >= config.swipeEfficiency &&
            geometry.agreement >= config.landmarkAgreement
        runtime.swipeConfirm = if (passed) runtime.swipeConfirm + 1 else 0
        if (runtime.swipeConfirm < config.confirmFrames) return null
        val start = runtime.armedAnchor?.timestampMs ?: runtime.activeStartMs
        val confidence = min(
            1f,
            minOf(
                geometry.distance / config.swipeDistance,
                geometry.dominance / config.swipeDominance,
                geometry.efficiency / config.swipeEfficiency,
                geometry.agreement / config.landmarkAgreement,
            ) / 2f,
        )
        if (direction == GestureId.SWIPE_LEFT || direction == GestureId.SWIPE_RIGHT) {
            runtime.horizontalCooldownUntilMs = timestampMs + config.horizontalCooldownMs
        }
        enterReturning(runtime, timestampMs)
        return ProductGestureEvent(direction, confidence, setOf(trackId), start, timestampMs)
    }

    private enum class VerticalTransitionResult { WAITING, CONFIRMED, CANCELLED }

    private fun updateArmedVerticalTransition(
        runtime: TrackRuntime,
        category: String,
    ): GestureId? {
        runtime.pendingVerticalDirection?.let { direction ->
            return when (updateVerticalTransition(runtime, category, direction)) {
                VerticalTransitionResult.CONFIRMED -> direction
                VerticalTransitionResult.WAITING -> null
                VerticalTransitionResult.CANCELLED -> {
                    cancelVerticalTransition(runtime)
                    null
                }
            }
        }
        val start = runtime.armedClass ?: return null
        val direction = when {
            start to category in ProductInteractionConfig.UP_TRANSITIONS -> GestureId.SWIPE_UP
            start to category in ProductInteractionConfig.DOWN_TRANSITIONS -> GestureId.SWIPE_DOWN
            isVerticalBridge(start, category, GestureId.SWIPE_DOWN) -> GestureId.SWIPE_DOWN
            else -> return null
        }
        runtime.pendingVerticalDirection = direction
        return when (updateVerticalTransition(runtime, category, direction)) {
            VerticalTransitionResult.CONFIRMED -> direction
            VerticalTransitionResult.WAITING -> null
            VerticalTransitionResult.CANCELLED -> {
                cancelVerticalTransition(runtime)
                null
            }
        }
    }

    private fun cancelVerticalTransition(runtime: TrackRuntime) {
        runtime.pendingVerticalDirection = null
        runtime.verticalTargetClass = null
        runtime.verticalTargetCount = 0
        runtime.verticalTransitionSeen = false
        runtime.verticalTransitionGap = 0
        runtime.verticalBridgeFrames = 0
    }

    private fun updateVerticalTransition(
        runtime: TrackRuntime,
        category: String,
        direction: GestureId,
    ): VerticalTransitionResult {
        val start = runtime.armedClass ?: return VerticalTransitionResult.CANCELLED
        val targets = verticalTargets(start, direction)
        if (targets.isEmpty()) return VerticalTransitionResult.CANCELLED
        // stop -> point is the least stable transition in practice: the
        // pointing pose often appears only briefly while the user returns
        // from stop. Confirm that route on its first valid frame; keep the
        // stricter two-frame confirmation for every other vertical route.
        val requiredTargetFrames = if (
            direction == GestureId.SWIPE_DOWN &&
            start == "stop" &&
            category == "point"
        ) 1 else config.verticalTransitionConfirmFrames

        if (category in targets) {
            if (runtime.verticalTargetClass == category) runtime.verticalTargetCount++ else {
                runtime.verticalTargetClass = category
                runtime.verticalTargetCount = 1
            }
            runtime.verticalTransitionGap = 0
            runtime.verticalBridgeFrames = 0
            if (runtime.verticalTargetCount >= requiredTargetFrames) {
                runtime.verticalTransitionSeen = true
            }
        } else if (category == start && !runtime.verticalTransitionSeen) {
            runtime.verticalTargetClass = null
            runtime.verticalTargetCount = 0
            runtime.verticalTransitionGap = 0
            runtime.verticalBridgeFrames = 0
        } else if (category == NO_GESTURE) {
            // Tracking can briefly lose the hand while the pose changes. Keep
            // the route and any already-confirmed target frames during this
            // short gap, but never allow an unbounded missing-hand interval.
            runtime.verticalTransitionGap++
            if (runtime.verticalTransitionGap > config.noGestureBridgeFrames) {
                return VerticalTransitionResult.CANCELLED
            }
        } else if (isVerticalBridge(start, category, direction)) {
            runtime.verticalTransitionGap = 0
            runtime.verticalBridgeFrames++
            if (runtime.verticalBridgeFrames > config.semanticBridgeFrames) {
                return VerticalTransitionResult.CANCELLED
            }
        } else {
            runtime.verticalTransitionGap++
            if (runtime.verticalTransitionGap > config.verticalTransitionGraceFrames) {
                return VerticalTransitionResult.CANCELLED
            }
        }
        return if (runtime.verticalTransitionSeen) {
            VerticalTransitionResult.CONFIRMED
        } else {
            VerticalTransitionResult.WAITING
        }
    }

    private fun isSwipeStartClass(category: String): Boolean =
        category in config.swipeArmClasses || category in config.verticalStartClasses

    private fun isReadyStartClass(category: String?): Boolean =
        category != null && isSwipeStartClass(category)

    private fun isFastArmEligible(category: String?, score: Float): Boolean {
        category ?: return false
        if (!isReadyStartClass(category)) return false
        val threshold = if (category == POINT_CLASS) config.classificationScore else config.fastReadyScore
        return score >= threshold
    }

    private fun appendClassFrame(runtime: TrackRuntime, timestampMs: Long, category: String) {
        runtime.classFrames.addLast(ClassFrame(timestampMs, category))
        val maxWindow = max(config.actionMs, config.zoomActionMs)
        while (runtime.classFrames.size > 2 &&
            timestampMs - runtime.classFrames.first().timestampMs > maxWindow
        ) {
            runtime.classFrames.removeFirst()
        }
    }

    private fun matchClassTransition(frames: Collection<ClassFrame>): ClassMatch? {
        val history = frames.toList()
        if (history.size < config.verticalTransitionConfirmFrames * 2) return null
        CLASS_PATTERNS.forEach { pattern ->
            val requiredVotes = if (pattern.gesture == GestureId.ZOOM_IN ||
                pattern.gesture == GestureId.ZOOM_OUT
            ) {
                config.zoomPoseFrames
            } else {
                config.verticalTransitionConfirmFrames
            }
            val maxDuration = if (pattern.gesture == GestureId.ZOOM_IN ||
                pattern.gesture == GestureId.ZOOM_OUT
            ) {
                config.zoomActionMs
            } else {
                config.actionMs
            }
            history.indices.forEach { startIndex ->
                if (history[startIndex].category != pattern.nodes.first()) return@forEach
                var nodeIndex = 0
                var nodeVotes = 0
                var ignoredCategory: String? = null
                var ignoredRunFrames = 0
                var noGestureRunFrames = 0
                for (index in startIndex..history.lastIndex) {
                    val frame = history[index]
                    if (frame.timestampMs - history[startIndex].timestampMs > maxDuration) break
                    val expected = pattern.nodes[nodeIndex]
                    val previous = pattern.nodes.getOrNull(nodeIndex - 1)
                    when {
                        frame.category == expected -> {
                            nodeVotes++
                            ignoredRunFrames = 0
                            noGestureRunFrames = 0
                            if (nodeVotes >= requiredVotes) {
                                nodeIndex++
                                nodeVotes = 0
                                if (nodeIndex == pattern.nodes.size) {
                                    if (index == history.lastIndex) {
                                        return ClassMatch(pattern.gesture, history[startIndex].timestampMs)
                                    }
                                    break
                                }
                            }
                        }
                        previous != null && frame.category == previous -> {
                            ignoredRunFrames = 0
                            noGestureRunFrames = 0
                        }
                        frame.category == NO_GESTURE -> {
                            noGestureRunFrames++
                            ignoredRunFrames = 0
                            if (noGestureRunFrames > config.noGestureBridgeFrames) break
                        }
                        else -> {
                            noGestureRunFrames = 0
                            if (ignoredCategory == null) ignoredCategory = frame.category
                            if (ignoredCategory != frame.category) break
                            ignoredRunFrames++
                            if (ignoredRunFrames > config.verticalTransitionGraceFrames) break
                        }
                    }
                }
            }
        }
        return null
    }

    private fun updateHorizontalStartClass(
        runtime: TrackRuntime,
        anchor: MotionSample,
        sample: MotionSample,
        category: String,
    ) {
        if (category == NO_GESTURE || hasMeaningfulBoxDisplacement(anchor, sample)) return
        if (runtime.armedClass == category) {
            clearArmedHandoff(runtime)
            return
        }
        if (runtime.armedHandoffClass == category) {
            runtime.armedHandoffCount++
        } else {
            runtime.armedHandoffClass = category
            runtime.armedHandoffCount = 1
        }
        if (runtime.armedHandoffCount >= config.startHandoffFrames) {
            runtime.armedClass = category
            runtime.armedAnchor = sample.copy(category = category)
            runtime.armedStartMs = sample.timestampMs
            runtime.restSamples.clear()
            runtime.restSamples.add(sample.copy(category = category))
            clearArmedHandoff(runtime)
        }
    }

    private fun hasMeaningfulBoxDisplacement(from: MotionSample, to: MotionSample): Boolean {
        val dx = abs(to.centerX - from.centerX) / max((from.width + to.width) / 2f, 1e-6f)
        val dy = abs(to.centerY - from.centerY) / max((from.height + to.height) / 2f, 1e-6f)
        return max(dx, dy) >= config.onsetDistance
    }

    private fun updateArmedStartHandoff(
        runtime: TrackRuntime,
        sample: MotionSample,
        category: String,
    ): Boolean {
        if (runtime.pendingVerticalDirection != null || runtime.zoomStartClass != null) return true
        val current = runtime.armedClass ?: return false
        if (category == current) {
            clearArmedHandoff(runtime)
            return false
        }
        if (!isSwipeStartClass(category)) {
            if (runtime.armedHandoffClass == POINT_CLASS &&
                runtime.armedHandoffGap < config.pointArmGraceFrames
            ) {
                runtime.armedHandoffGap++
                return true
            }
            clearArmedHandoff(runtime)
            return false
        }
        if (runtime.armedHandoffClass == category) {
            runtime.armedHandoffCount++
        } else {
            runtime.armedHandoffClass = category
            runtime.armedHandoffCount = 1
        }
        runtime.armedHandoffGap = 0
        if (runtime.armedHandoffCount >= config.startHandoffFrames) {
            if (runtime.armedClass == POINT_CLASS && category == "grabbing" &&
                runtime.pendingVerticalDirection == GestureId.SWIPE_UP
            ) {
                return true
            }
            runtime.armedClass = category
            runtime.armedAnchor = sample.copy(category = category)
            runtime.armedStartMs = sample.timestampMs
            runtime.restSamples.clear()
            runtime.restSamples.add(sample.copy(category = category))
            cancelVerticalTransition(runtime)
            clearArmedHandoff(runtime)
        }
        return true
    }

    private fun clearArmedHandoff(runtime: TrackRuntime) {
        runtime.armedHandoffClass = null
        runtime.armedHandoffCount = 0
        runtime.armedHandoffGap = 0
    }

    private fun isVerticalBridge(start: String, category: String, direction: GestureId): Boolean =
        category == "grabbing" && when (direction) {
            GestureId.SWIPE_DOWN -> start == "stop" || start == "point"
            else -> false
        }

    private fun verticalTargets(start: String, direction: GestureId): Set<String> {
        val transitions = when (direction) {
            GestureId.SWIPE_UP -> ProductInteractionConfig.UP_TRANSITIONS
            GestureId.SWIPE_DOWN -> ProductInteractionConfig.DOWN_TRANSITIONS
            else -> return emptySet()
        }
        return transitions.filter { it.first == start }.mapTo(mutableSetOf()) { it.second }
    }

    private fun updateSingleZoom(
        timestampMs: Long,
        trackId: Int,
        runtime: TrackRuntime,
        raw: String?,
    ): ProductGestureEvent? {
        if (runtime.phase == Phase.RETURNING) return null
        if (raw == null || raw == NO_GESTURE) {
            // palm -> fist often passes through grabbing/grip or temporarily drops
            // classification entirely. Preserve the endpoint candidate for the
            // normal two-second action window; neither bridge needs frame votes.
            if (runtime.zoomStartClass != null &&
                timestampMs - runtime.zoomStartMs > config.zoomActionMs
            ) {
                clearZoom(runtime)
            }
            return null
        }
        val category = raw
        val startEligible = category == FIST_CLASS || category == SINGLE_ZOOM_SHRINK_START_CLASS
        if (runtime.zoomStartClass == null) {
            if (startEligible && (runtime.phase == Phase.IDLE || runtime.phase == Phase.ARMED)) {
                runtime.zoomBridgeFrames = 0
                if (runtime.zoomStartCandidateClass == category) {
                    runtime.zoomStartCount++
                } else {
                    runtime.zoomStartCandidateClass = category
                    runtime.zoomStartCount = 1
                }
                if (runtime.zoomStartCount >= config.singleZoomPoseFrames) {
                    runtime.zoomStartClass = category
                    runtime.zoomStartMs = timestampMs
                    runtime.zoomBridgeFrames = 0
                }
            } else {
                runtime.zoomStartCandidateClass = null
                runtime.zoomStartCount = 0
                runtime.zoomBridgeFrames = 0
            }
            return null
        }
        val startClass = requireNotNull(runtime.zoomStartClass)
        if (runtime.zoomPausedSinceMs >= 0L) {
            runtime.zoomStartMs += (timestampMs - runtime.zoomPausedSinceMs).coerceAtLeast(0L)
            runtime.zoomPausedSinceMs = -1L
        }
        if (timestampMs - runtime.zoomStartMs > config.zoomActionMs) {
            clearZoom(runtime)
            return null
        }
        val targetValid = when {
            startClass == FIST_CLASS -> category == SINGLE_PINCH_CLASS
            startClass == SINGLE_ZOOM_SHRINK_START_CLASS -> category == FIST_CLASS
            else -> false
        }
        if (!targetValid) {
            when {
                category == startClass -> {
                    runtime.zoomTargetClass = null
                    runtime.zoomTargetCount = 0
                    runtime.zoomBridgeFrames = 0
                }
                // Any static one-hand pose can appear while the user changes
                // between palm/fist and pinch. Keep the candidate alive and
                // wait for the endpoint instead of cancelling on a transient
                // classification. Pair and dynamic labels are excluded.
                category in SINGLE_ZOOM_BRIDGE_CLASSES -> {
                    runtime.zoomBridgeFrames++
                    if (runtime.zoomBridgeFrames > config.singleZoomBridgeFrames) {
                        clearZoom(runtime)
                    }
                }
                else -> clearZoom(runtime)
            }
            return null
        }
        runtime.zoomBridgeFrames = 0
        if (runtime.zoomTargetClass == category) runtime.zoomTargetCount++ else {
            runtime.zoomTargetClass = category
            runtime.zoomTargetCount = 1
        }
        // Endpoint confirmation is intentionally one frame. The start pose
        // remains two-frame confirmed; only the newly reached pose is made
        // responsive to avoid making the user hold it.
        if (runtime.zoomTargetCount < config.singleZoomPoseFrames) return null
        val gesture = if (startClass == FIST_CLASS) GestureId.ZOOM_IN else GestureId.ZOOM_OUT
        val scaleFactor = if (gesture == GestureId.ZOOM_IN) {
            config.singleZoomScaleFactor
        } else {
            1f / config.singleZoomScaleFactor
        }
        val event = ProductGestureEvent(
            gesture,
            1f,
            setOf(trackId),
            runtime.zoomStartMs,
            timestampMs,
            scaleFactor,
        )
        enterReturning(runtime, timestampMs)
        return event
    }

    private data class PairUpdate(
        val singleEligibleIds: Set<Int>,
        val scalePreview: ProductScalePreview?,
        val scaleStatus: ProductScaleStatus,
    )

    private fun updatePairs(
        timestampMs: Long,
        observations: List<ProductHandObservation>,
        samples: Map<Int, MotionSample>,
        events: MutableList<ProductGestureEvent>,
    ): PairUpdate {
        val ranked = observations.sortedByDescending { it.box.width * it.box.height }
        val largestTrackId = ranked.firstOrNull()?.trackId
        val visiblePair = ranked.take(2).takeIf { it.size == 2 }
        val comparablePairIds = visiblePair?.takeIf { handsComparable(it[0], it[1]) }
            ?.map { it.trackId }
            ?.sorted()
            ?.let { it[0] to it[1] }

        if (pairRuntime.phase == PairPhase.ACTIVE) {
            var ids = pairIds
            var currentSamples = ids?.let { currentPairSamples(it, samples) }
            if (currentSamples == null && comparablePairIds != null) {
                val candidateSamples = currentPairSamples(comparablePairIds, samples)
                val candidateClasses = pairClasses(comparablePairIds)
                val candidateStable = candidateClasses.first != null && candidateClasses.second != null &&
                    candidateClasses.first != NO_GESTURE && candidateClasses.second != NO_GESTURE
                if (candidateSamples != null && candidateStable) {
                    pairIds = comparablePairIds
                    ids = comparablePairIds
                    currentSamples = candidateSamples
                    if (candidateClasses.first == FIST_CLASS && candidateClasses.second == FIST_CLASS) {
                        pairRuntime.baseline = pairSample(timestampMs, candidateSamples.first, candidateSamples.second)
                        pairRuntime.scaleAtBaseline = pairRuntime.scaleFactor
                        pairRuntime.adjustmentActive = true
                    } else {
                        pairRuntime.baseline = null
                        pairRuntime.adjustmentActive = false
                    }
                }
            }
            val participantIds = ids?.let { setOf(it.first, it.second) }.orEmpty()
            if (ids == null || participantIds.size != 2) {
                resetPairSession()
                return PairUpdate(emptySet(), null, ProductScaleStatus.IDLE)
            }
            val classes = pairClasses(ids)
            val bothFists = classes.first == FIST_CLASS && classes.second == FIST_CLASS
            val pairReattached = ids.let { (first, second) ->
                tracks[first]?.resumedThisFrame == true || tracks[second]?.resumedThisFrame == true
            }
            val bothExited = currentSamples != null &&
                classes.first != null && classes.second != null &&
                classes.first != NO_GESTURE && classes.second != NO_GESTURE &&
                classes.first != FIST_CLASS && classes.second != FIST_CLASS
            val bothMissing = observations.none { it.trackId == ids.first || it.trackId == ids.second }

            // A hand that disappeared and came back must establish a fresh
            // distance baseline. Otherwise its old pair baseline is applied to
            // the new geometry and the injected pinch keeps drifting.
            if (pairReattached && currentSamples != null) {
                pairRuntime.baseline = pairSample(timestampMs, currentSamples.first, currentSamples.second)
                pairRuntime.scaleAtBaseline = pairRuntime.scaleFactor
                pairRuntime.adjustmentActive = false
                pairRuntime.exitFrames = 0
            }

            if (currentSamples != null && bothFists) {
                val sample = pairSample(timestampMs, currentSamples.first, currentSamples.second)
                if (!pairRuntime.adjustmentActive) {
                    pairRuntime.baseline = sample
                    pairRuntime.scaleAtBaseline = pairRuntime.scaleFactor
                    pairRuntime.adjustmentActive = true
                }
                val baseline = pairRuntime.baseline ?: sample
                pairRuntime.scaleFactor = (pairRuntime.scaleAtBaseline *
                    sample.separation / max(baseline.separation, 1e-6f))
                    .coerceIn(MIN_SCALE_FACTOR, MAX_SCALE_FACTOR)
                pairRuntime.exitFrames = 0
            } else {
                pairRuntime.adjustmentActive = false
                pairRuntime.baseline = null
                pairRuntime.exitFrames = if (bothExited || bothMissing) pairRuntime.exitFrames + 1 else 0
            }

            if (pairRuntime.exitFrames >= config.verticalTransitionConfirmFrames) {
                val scale = pairRuntime.scaleFactor
                if (abs(scale - 1f) >= config.pairScaleDeadZone) {
                    events += ProductGestureEvent(
                        gesture = GestureId.TWO_HAND_ZOOM,
                        confidence = min(1f, abs(scale - 1f) / config.pairScaleDeadZone),
                        participantTrackIds = participantIds,
                        startTimestampMs = pairRuntime.activeStartMs,
                        confirmedTimestampMs = timestampMs,
                        scaleFactor = scale,
                    )
                }
                resetPairSession()
                return PairUpdate(emptySet(), null, ProductScaleStatus.IDLE)
            }
            return PairUpdate(
                singleEligibleIds = emptySet(),
                scalePreview = ProductScalePreview(participantIds, pairRuntime.scaleFactor),
                scaleStatus = if (currentSamples != null && bothFists) {
                    ProductScaleStatus.ADJUSTING
                } else {
                    ProductScaleStatus.PAUSED
                },
            )
        }

        if (comparablePairIds == null) {
            resetPairSession()
            return PairUpdate(largestTrackId?.let(::setOf).orEmpty(), null, ProductScaleStatus.IDLE)
        }
        if (pairIds != comparablePairIds) {
            resetPair(pairRuntime)
            pairIds = comparablePairIds
        }
        val currentSamples = currentPairSamples(comparablePairIds, samples)
            ?: return PairUpdate(emptySet(), null, ProductScaleStatus.IDLE)
        val sample = pairSample(timestampMs, currentSamples.first, currentSamples.second)
        val classes = pairClasses(comparablePairIds)
        val bothPinched = classes.first in PAIR_PINCH_CLASSES && classes.second in PAIR_PINCH_CLASSES
        val bothFists = classes.first == FIST_CLASS && classes.second == FIST_CLASS
        val participantIds = setOf(comparablePairIds.first, comparablePairIds.second)

        when (pairRuntime.phase) {
            PairPhase.WAITING_FOR_PINCH -> {
                pairRuntime.pinchFrames = if (bothPinched) pairRuntime.pinchFrames + 1 else 0
                if (pairRuntime.pinchFrames >= config.zoomPoseFrames) {
                    pairRuntime.phase = PairPhase.WAITING_FOR_FISTS
                    pairRuntime.transitionGap = 0
                }
            }
            PairPhase.WAITING_FOR_FISTS -> when {
                bothFists -> {
                    pairRuntime.fistFrames++
                    pairRuntime.transitionGap = 0
                    if (pairRuntime.fistFrames >= config.zoomPoseFrames) {
                        pairRuntime.phase = PairPhase.ACTIVE
                        pairRuntime.baseline = sample
                        pairRuntime.activeStartMs = timestampMs
                        pairRuntime.scaleFactor = 1f
                        pairRuntime.scaleAtBaseline = 1f
                        pairRuntime.adjustmentActive = true
                    }
                }
                bothPinched -> {
                    pairRuntime.fistFrames = 0
                    pairRuntime.transitionGap = 0
                }
                else -> {
                    pairRuntime.fistFrames = 0
                    pairRuntime.transitionGap++
                    if (pairRuntime.transitionGap > config.verticalTransitionGraceFrames) {
                        resetPair(pairRuntime)
                    }
                }
            }
            PairPhase.ACTIVE -> error("active pair must be handled before arbitration")
        }
        val preview = pairRuntime.scaleFactor.takeIf { pairRuntime.phase == PairPhase.ACTIVE }
            ?.let { ProductScalePreview(participantIds, it) }
        val scaleStatus = when (pairRuntime.phase) {
            PairPhase.WAITING_FOR_PINCH -> ProductScaleStatus.IDLE
            PairPhase.WAITING_FOR_FISTS -> ProductScaleStatus.READY
            PairPhase.ACTIVE -> ProductScaleStatus.ADJUSTING
        }
        return PairUpdate(emptySet(), preview, scaleStatus)
    }

    private fun handsComparable(first: ProductHandObservation, second: ProductHandObservation): Boolean {
        val firstArea = first.box.width * first.box.height
        val secondArea = second.box.width * second.box.height
        return max(firstArea, secondArea) / max(min(firstArea, secondArea), 1e-6f) <= MAX_PAIR_AREA_RATIO
    }

    private fun currentPairSamples(
        ids: Pair<Int, Int>,
        samples: Map<Int, MotionSample>,
    ): Pair<MotionSample, MotionSample>? {
        val first = samples[ids.first] ?: return null
        val second = samples[ids.second] ?: return null
        return first to second
    }

    private fun pairClasses(ids: Pair<Int, Int>): Pair<String?, String?> =
        tracks[ids.first]?.stableClass to tracks[ids.second]?.stableClass

    private fun resetPair(runtime: PairRuntime) {
        runtime.phase = PairPhase.WAITING_FOR_PINCH
        runtime.pinchFrames = 0
        runtime.fistFrames = 0
        runtime.transitionGap = 0
        runtime.baseline = null
        runtime.activeStartMs = -1
        runtime.scaleFactor = 1f
        runtime.scaleAtBaseline = 1f
        runtime.adjustmentActive = false
        runtime.exitFrames = 0
    }

    private fun resetPairSession() {
        resetPair(pairRuntime)
        pairIds = null
    }

    private data class DirectionGeometry(
        val distance: Float,
        val dominance: Float,
        val efficiency: Float,
        val agreement: Float,
    )

    private fun directionMetrics(samples: Collection<MotionSample>, gesture: GestureId): DirectionGeometry {
        val ordered = samples.toList()
        if (ordered.size < 2 || ordered.any { !it.palmValid }) {
            return DirectionGeometry(0f, 0f, 0f, 0f)
        }
        val boxDx = mutableListOf<Float>()
        val boxDy = mutableListOf<Float>()
        val palmDx = mutableListOf<Float>()
        val palmDy = mutableListOf<Float>()
        ordered.zipWithNext().forEach { (previous, current) ->
            val width = max(1e-6f, (previous.width + current.width) / 2f)
            val height = max(1e-6f, (previous.height + current.height) / 2f)
            boxDx += (current.centerX - previous.centerX) / width
            boxDy += (current.centerY - previous.centerY) / height
            palmDx += (current.palmX - previous.palmX) / width
            palmDy += (current.palmY - previous.palmY) / height
        }
        val horizontal = gesture == GestureId.SWIPE_LEFT || gesture == GestureId.SWIPE_RIGHT
        val sign = if (gesture == GestureId.SWIPE_LEFT || gesture == GestureId.SWIPE_UP) -1f else 1f
        val boxMain = if (horizontal) boxDx else boxDy
        val boxCross = if (horizontal) boxDy else boxDx
        val palmMain = if (horizontal) palmDx else palmDy
        val palmCross = if (horizontal) palmDy else palmDx
        val boxDistance = sign * boxMain.sum()
        val palmDistance = sign * palmMain.sum()
        val dominance = min(
            boxDistance / max(abs(boxCross.sum()), 1e-6f),
            palmDistance / max(abs(palmCross.sum()), 1e-6f),
        )
        val agreement = boxMain.indices.count { sign * boxMain[it] > 0f && sign * palmMain[it] > 0f }
            .toFloat() / max(1, boxMain.size)
        return DirectionGeometry(
            min(boxDistance, palmDistance),
            dominance,
            min(pathEfficiency(boxDx, boxDy), pathEfficiency(palmDx, palmDy)),
            agreement,
        )
    }

    private fun selectHorizontalDirection(samples: Collection<MotionSample>, startClass: String?): GestureId? = listOf(
        GestureId.SWIPE_LEFT,
        GestureId.SWIPE_RIGHT,
    ).map { it to directionMetrics(samples, it) }
        .filter { directionAllowed(startClass, it.first) }
        .filter { it.second.distance >= config.onsetDistance && it.second.dominance >= config.onsetDominance }
        .maxByOrNull { it.second.distance }
        ?.first

    private fun directionAllowed(startClass: String?, direction: GestureId): Boolean {
        startClass ?: return false
        return when (direction) {
            GestureId.SWIPE_LEFT, GestureId.SWIPE_RIGHT -> startClass in config.swipeArmClasses
            GestureId.SWIPE_UP -> verticalTargets(startClass, direction).isNotEmpty()
            GestureId.SWIPE_DOWN -> verticalTargets(startClass, direction).isNotEmpty()
            else -> false
        }
    }

    private fun pairSample(timestampMs: Long, first: MotionSample, second: MotionSample): PairSample {
        val averageDiagonal = max((first.diagonal + second.diagonal) / 2f, 1e-6f)
        return PairSample(
            timestampMs,
            first.centerX,
            first.centerY,
            second.centerX,
            second.centerY,
            (first.centerX + second.centerX) / 2f,
            (first.centerY + second.centerY) / 2f,
            averageDiagonal,
            hypot(first.centerX - second.centerX, first.centerY - second.centerY) / averageDiagonal,
        )
    }

    private fun stationary(
        samples: Collection<MotionSample>,
        durationMs: Long,
        includePalm: Boolean = true,
    ): Boolean {
        if (samples.size < 2) return false
        val ordered = samples.toList()
        if (ordered.last().timestampMs - ordered.first().timestampMs < durationMs) return false
        if (includePalm && ordered.any { !it.palmValid }) return false
        val box = normalizedSteps(ordered, palm = false)
        val palm = normalizedSteps(ordered, palm = true)
        val boxPath = box.sumOf { hypot(it.first, it.second).toDouble() }.toFloat()
        val palmPath = palm.sumOf { hypot(it.first, it.second).toDouble() }.toFloat()
        val boxDrift = hypot(box.sumOf { it.first.toDouble() }.toFloat(), box.sumOf { it.second.toDouble() }.toFloat())
        val palmDrift = hypot(palm.sumOf { it.first.toDouble() }.toFloat(), palm.sumOf { it.second.toDouble() }.toFloat())
        return if (includePalm) {
            max(boxPath, palmPath) <= config.readyMaxPath && max(boxDrift, palmDrift) <= config.readyMaxDrift
        } else {
            boxPath <= config.readyMaxPath && boxDrift <= config.readyMaxDrift
        }
    }

    private fun hasMeaningfulDisplacement(from: MotionSample, to: MotionSample): Boolean {
        val dx = abs(to.centerX - from.centerX) / max((from.width + to.width) / 2f, 1e-6f)
        val dy = abs(to.centerY - from.centerY) / max((from.height + to.height) / 2f, 1e-6f)
        val palmDx = abs(to.palmX - from.palmX) / max((from.width + to.width) / 2f, 1e-6f)
        val palmDy = abs(to.palmY - from.palmY) / max((from.height + to.height) / 2f, 1e-6f)
        return max(max(dx, dy), max(palmDx, palmDy)) >= config.onsetDistance
    }

    private fun normalizedSteps(samples: List<MotionSample>, palm: Boolean): List<Pair<Float, Float>> =
        samples.zipWithNext().map { (previous, current) ->
            val previousX = if (palm) previous.palmX else previous.centerX
            val previousY = if (palm) previous.palmY else previous.centerY
            val currentX = if (palm) current.palmX else current.centerX
            val currentY = if (palm) current.palmY else current.centerY
            (currentX - previousX) / max((previous.width + current.width) / 2f, 1e-6f) to
                (currentY - previousY) / max((previous.height + current.height) / 2f, 1e-6f)
        }

    private fun anchor(samples: Collection<MotionSample>): MotionSample {
        val items = samples.toList()
        return MotionSample(
            items.last().timestampMs,
            items.last().category,
            median(items.map { it.centerX }),
            median(items.map { it.centerY }),
            median(items.map { it.width }),
            median(items.map { it.height }),
            median(items.map { it.palmX }),
            median(items.map { it.palmY }),
            items.all { it.palmValid },
        )
    }

    private fun updateReturning(
        runtime: TrackRuntime,
        sample: MotionSample,
        readyForRelease: Boolean,
        rearmSwipe: Boolean,
        fastVerticalRearm: Boolean,
    ) {
        if (!readyForRelease) {
            if (runtime.restSamples.lastOrNull()?.category == POINT_CLASS &&
                runtime.returningGapFrames < config.pointArmGraceFrames
            ) {
                runtime.returningGapFrames++
                return
            }
            runtime.restSamples.clear()
            runtime.returningGapFrames = 0
            return
        }
        runtime.returningGapFrames = 0
        addTrimmed(runtime.restSamples, sample, config.readyMs * 2)
        val fastSamples = runtime.restSamples.toList().takeLast(config.fastReadyFrames)
        val fastReady = fastVerticalRearm && fastSamples.size >= config.fastReadyFrames &&
            runtime.fastArmCount >= config.fastReadyFrames &&
            runtime.fastArmClass == fastSamples.lastOrNull()?.category &&
            stationary(fastSamples, config.fastReadyMs, includePalm = true)
        val safeReady = sample.timestampMs - runtime.returningStartMs >= config.returningMinMs &&
            isReadyStartClass(runtime.stableClass) &&
            stationary(runtime.restSamples, config.readyMs, includePalm = true)
        if (fastReady || safeReady) {
            if (rearmSwipe) {
                runtime.phase = Phase.ARMED
                runtime.armedAnchor = anchor(if (fastReady) fastSamples else runtime.restSamples)
                runtime.armedClass = runtime.armedAnchor?.category
                runtime.armedStartMs = sample.timestampMs
                runtime.returningStartMs = -1
                runtime.returningGapFrames = 0
                clearArmedHandoff(runtime)
                runtime.classFrames.clear()
                val readySamples = if (fastReady) fastSamples else runtime.restSamples.toList()
                readySamples.forEach { runtime.classFrames.addLast(ClassFrame(it.timestampMs, it.category)) }
            } else resetTrack(runtime)
        }
    }

    private fun enterReturning(runtime: TrackRuntime, timestampMs: Long) {
        runtime.phase = Phase.RETURNING
        runtime.returningStartMs = timestampMs
        runtime.returningGapFrames = 0
        runtime.restSamples.clear()
        runtime.armedAnchor = null
        runtime.armedClass = null
        runtime.armedStartMs = -1
        clearMotion(runtime)
        clearZoom(runtime)
    }

    private fun resetTrack(runtime: TrackRuntime) {
        resetSwipe(runtime)
        clearZoom(runtime)
    }

    private fun resetSwipe(runtime: TrackRuntime) {
        runtime.phase = Phase.IDLE
        runtime.fastArmCount = 0
        runtime.fastArmClass = null
        runtime.fastArmGap = 0
        runtime.returningStartMs = -1
        runtime.returningGapFrames = 0
        runtime.restSamples.clear()
        runtime.armedAnchor = null
        runtime.armedClass = null
        runtime.armedStartMs = -1
        clearMotion(runtime)
    }

    private fun clearMotion(runtime: TrackRuntime) {
        clearArmedHandoff(runtime)
        runtime.classFrames.clear()
        runtime.activeSamples.clear()
        runtime.activeDirection = null
        runtime.activeStartMs = -1
        runtime.swipeConfirm = 0
        runtime.pendingVerticalDirection = null
        runtime.verticalTargetClass = null
        runtime.verticalTargetCount = 0
        runtime.verticalTransitionSeen = false
        runtime.verticalTransitionGap = 0
        runtime.verticalBridgeFrames = 0
    }

    private fun clearZoom(runtime: TrackRuntime) {
        runtime.zoomStartCandidateClass = null
        runtime.zoomStartCount = 0
        runtime.zoomStartClass = null
        runtime.zoomStartMs = -1
        runtime.zoomPausedSinceMs = -1
        runtime.zoomTargetClass = null
        runtime.zoomTargetCount = 0
        runtime.zoomBridgeFrames = 0
    }

    private fun resetRuntimeOnly() {
        tracks.values.forEach {
            it.classHistory.clear()
            it.stableClass = null
            resetTrack(it)
        }
        resetPairSession()
    }

    private fun <T> addTrimmed(queue: ArrayDeque<T>, item: T, durationMs: Long) where T : Any {
        queue.addLast(item)
        val timestamp = when (item) {
            is MotionSample -> item.timestampMs
            is PairSample -> item.timestampMs
            else -> return
        }
        while (queue.size > 2) {
            val firstTimestamp = when (val first = queue.first()) {
                is MotionSample -> first.timestampMs
                is PairSample -> first.timestampMs
                else -> timestamp
            }
            if (timestamp - firstTimestamp <= durationMs) break
            queue.removeFirst()
        }
    }

    private fun pathEfficiency(dx: List<Float>, dy: List<Float>): Float {
        val path = dx.indices.sumOf { hypot(dx[it], dy[it]).toDouble() }.toFloat()
        return if (path <= 1e-6f) 0f else hypot(dx.sum(), dy.sum()) / path
    }

    private fun median(values: List<Float>): Float {
        val ordered = values.sorted()
        val middle = ordered.size / 2
        return if (ordered.size % 2 == 1) ordered[middle] else (ordered[middle - 1] + ordered[middle]) / 2f
    }

    private companion object {
        const val LANDMARK_COUNT = 21
        const val NO_GESTURE = "no_gesture"
        const val FIST_CLASS = "fist"
        const val POINT_CLASS = "point"
        const val SINGLE_PINCH_CLASS = "thumb_index"
        const val SINGLE_ZOOM_SHRINK_START_CLASS = "palm"
        const val GAP_RESET_MS = 500L
        const val MAX_PAIR_AREA_RATIO = 2f
        const val MIN_SCALE_FACTOR = 0.1f
        const val MAX_SCALE_FACTOR = 10f
        val CLASS_PATTERNS = listOf(
            ClassPattern(GestureId.SWIPE_UP, listOf("fist", "stop")),
            ClassPattern(GestureId.SWIPE_DOWN, listOf("stop", "stop_inverted")),
            ClassPattern(GestureId.SWIPE_DOWN, listOf("point", "stop_inverted")),
            ClassPattern(GestureId.SWIPE_DOWN, listOf("stop", "point")),
            ClassPattern(GestureId.ZOOM_IN, listOf("fist", "thumb_index")),
            ClassPattern(GestureId.ZOOM_OUT, listOf("palm", "fist")),
        )
        val PALM_POINTS = intArrayOf(0, 5, 9, 13, 17)
        val PAIR_PINCH_CLASSES = setOf("thumb_index", "thumb_index2")
        val SINGLE_ZOOM_BRIDGE_CLASSES = GestureId.entries
            .filter { it.kind == GestureKind.STATIC_SINGLE }
            .mapTo(mutableSetOf()) { it.datasetLabel }
    }
}
