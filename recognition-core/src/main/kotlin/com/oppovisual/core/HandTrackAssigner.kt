package com.oppovisual.core

import kotlin.math.hypot

data class HandTrackConfig(
    val timeoutMs: Long = 1_000L,
    val maximumDistance: Float = 0.35f,
    val handednessMismatchPenalty: Float = 0.5f,
)

data class HandTrackObservation(
    val centerX: Float,
    val centerY: Float,
    val handedness: String?,
)

data class HandTrackAssignment(
    val observationIndex: Int,
    val trackId: Int,
)

class HandTrackAssigner(
    private val config: HandTrackConfig = HandTrackConfig(),
) {
    private val tracks = mutableMapOf<Int, Track>()
    private var nextTrackId = 1

    init {
        require(config.timeoutMs >= 0)
        require(config.maximumDistance > 0f)
        require(config.handednessMismatchPenalty >= 0f)
    }

    fun assign(timestampMs: Long, observations: List<HandTrackObservation>): List<HandTrackAssignment> {
        tracks.entries.removeAll { timestampMs - it.value.lastSeenMs > config.timeoutMs }
        val candidates = buildList {
            observations.forEachIndexed { observationIndex, observation ->
                tracks.values.forEach { track ->
                    val handednessPenalty = if (
                        observation.handedness != null &&
                        track.handedness != null &&
                        observation.handedness != track.handedness
                    ) config.handednessMismatchPenalty else 0f
                    val distance = hypot(observation.centerX - track.centerX, observation.centerY - track.centerY)
                    add(Candidate(observationIndex, track.id, distance + handednessPenalty))
                }
            }
        }.sortedBy { it.cost }

        val assignedObservations = mutableSetOf<Int>()
        val assignedTracks = mutableSetOf<Int>()
        val assignments = mutableMapOf<Int, Track>()
        candidates.forEach { candidate ->
            if (
                candidate.cost <= config.maximumDistance &&
                candidate.observationIndex !in assignedObservations &&
                candidate.trackId !in assignedTracks
            ) {
                assignedObservations.add(candidate.observationIndex)
                assignedTracks.add(candidate.trackId)
                assignments[candidate.observationIndex] = tracks.getValue(candidate.trackId)
            }
        }

        return observations.mapIndexed { index, observation ->
            val track = assignments[index] ?: Track(nextTrackId++).also { tracks[it.id] = it }
            track.centerX = observation.centerX
            track.centerY = observation.centerY
            track.handedness = observation.handedness
            track.lastSeenMs = timestampMs
            HandTrackAssignment(index, track.id)
        }
    }

    fun activeTrackIds(): Set<Int> = tracks.keys.toSet()

    fun reset() {
        tracks.clear()
        nextTrackId = 1
    }

    private data class Track(
        val id: Int,
        var centerX: Float = 0f,
        var centerY: Float = 0f,
        var handedness: String? = null,
        var lastSeenMs: Long = Long.MIN_VALUE,
    )

    private data class Candidate(
        val observationIndex: Int,
        val trackId: Int,
        val cost: Float,
    )
}
