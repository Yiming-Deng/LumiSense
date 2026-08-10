package com.oppovisual.core

import java.util.ArrayDeque
import kotlin.math.abs

data class HeadMotionConfig(
    val calibrationSamples: Int = 8,
    val calibrationYawLimitDegrees: Float = 18f,
    val calibrationPitchLimitDegrees: Float = 18f,
    val calibrationMaxSpanDegrees: Float = 4f,
    val directionEnterDegrees: Float = 17f,
    val directionExitDegrees: Float = 9f,
    val turnHoldMs: Long = 650,
    val nodExcursionDegrees: Float = 13f,
    val nodReturnDegrees: Float = 6f,
    val nodWindowMs: Long = 1_200,
    val shakeExcursionDegrees: Float = 17f,
    val shakeReturnDegrees: Float = 7f,
    val shakeWindowMs: Long = 1_500,
    val eventCooldownMs: Long = 350,
    val neutralAdaptation: Float = 0.015f,
    val neutralAdaptationLimitDegrees: Float = 6f,
) {
    init {
        require(calibrationSamples > 0)
        require(calibrationYawLimitDegrees > 0f && calibrationPitchLimitDegrees > 0f)
        require(calibrationMaxSpanDegrees > 0f)
        require(directionEnterDegrees > directionExitDegrees && directionExitDegrees >= 0f)
        require(nodExcursionDegrees > nodReturnDegrees && nodReturnDegrees >= 0f)
        require(shakeExcursionDegrees > shakeReturnDegrees && shakeReturnDegrees >= 0f)
        require(turnHoldMs >= 0 && nodWindowMs > 0 && shakeWindowMs > 0 && eventCooldownMs >= 0)
        require(neutralAdaptation in 0f..1f)
        require(neutralAdaptationLimitDegrees > 0f)
    }
}

class HeadMotionRecognizer(
    private val config: HeadMotionConfig = HeadMotionConfig(),
) {
    private val calibrationWindow = ArrayDeque<HeadPose>()
    private var calibrated = false
    private var neutralYaw = 0f
    private var neutralPitch = 0f
    private var lastTimestampMs: Long? = null
    private var direction = HeadDirection.CENTER
    private var turnSinceMs: Long? = null
    private var turnEmitted = false
    private var shakeFirstSide = 0
    private var shakeStartedMs = 0L
    private var shakeOppositeSeen = false
    private var nodSide = 0
    private var nodStartedMs = 0L
    private var cooldownUntilMs = 0L

    fun update(timestampMs: Long, absolutePose: HeadPose): HeadMotionUpdate {
        require(lastTimestampMs == null || timestampMs >= lastTimestampMs!!) {
            "Head-pose timestamps must be monotonic"
        }
        lastTimestampMs = timestampMs

        if (!calibrated && !updateCalibration(absolutePose)) {
            // Keep mocap responsive while head-motion events wait for a valid
            // neutral pose. Returning zero here made the avatar freeze during
            // every calibration or brief reacquisition.
            return HeadMotionUpdate(absolutePose, HeadDirection.CENTER, calibrated = false)
        }

        val pose = HeadPose(
            yawDegrees = absolutePose.yawDegrees - neutralYaw,
            pitchDegrees = absolutePose.pitchDegrees - neutralPitch,
            rollDegrees = absolutePose.rollDegrees,
        )
        updateDirection(pose.yawDegrees, timestampMs)

        if (timestampMs < cooldownUntilMs) return HeadMotionUpdate(pose, direction, calibrated = true)

        val shakeEvent = updateShake(pose.yawDegrees, timestampMs)
        if (shakeEvent != null) return eventUpdate(pose, shakeEvent, timestampMs)

        val nodEvent = updateNod(pose.pitchDegrees, timestampMs)
        if (nodEvent != null) return eventUpdate(pose, nodEvent, timestampMs)

        val turnEvent = updateTurn(timestampMs)
        if (turnEvent != null) return eventUpdate(pose, turnEvent, timestampMs)

        if (
            direction == HeadDirection.CENTER && shakeFirstSide == 0 && nodSide == 0 &&
            abs(pose.yawDegrees) <= config.neutralAdaptationLimitDegrees &&
            abs(pose.pitchDegrees) <= config.neutralAdaptationLimitDegrees
        ) {
            neutralYaw += pose.yawDegrees * config.neutralAdaptation
            neutralPitch += pose.pitchDegrees * config.neutralAdaptation
        }
        return HeadMotionUpdate(pose, direction, calibrated = true)
    }

    fun reset() {
        calibrationWindow.clear()
        calibrated = false
        neutralYaw = 0f
        neutralPitch = 0f
        lastTimestampMs = null
        direction = HeadDirection.CENTER
        resetTurn()
        resetShake()
        resetNod()
        cooldownUntilMs = 0L
    }

    private fun updateCalibration(pose: HeadPose): Boolean {
        val nearFrontal = abs(pose.yawDegrees) <= config.calibrationYawLimitDegrees &&
            abs(pose.pitchDegrees) <= config.calibrationPitchLimitDegrees
        if (!nearFrontal) {
            calibrationWindow.clear()
            return false
        }

        calibrationWindow.addLast(pose)
        while (calibrationWindow.size > config.calibrationSamples) calibrationWindow.removeFirst()
        val yawSpan = calibrationWindow.maxOf { it.yawDegrees } - calibrationWindow.minOf { it.yawDegrees }
        val pitchSpan = calibrationWindow.maxOf { it.pitchDegrees } - calibrationWindow.minOf { it.pitchDegrees }
        if (yawSpan > config.calibrationMaxSpanDegrees || pitchSpan > config.calibrationMaxSpanDegrees) {
            calibrationWindow.clear()
            calibrationWindow.addLast(pose)
            return false
        }
        if (calibrationWindow.size < config.calibrationSamples) return false

        neutralYaw = calibrationWindow.map { it.yawDegrees }.median()
        neutralPitch = calibrationWindow.map { it.pitchDegrees }.median()
        calibrationWindow.clear()
        calibrated = true
        return true
    }

    private fun updateDirection(yaw: Float, timestampMs: Long) {
        val next = when (direction) {
            HeadDirection.CENTER -> when {
                yaw >= config.directionEnterDegrees -> HeadDirection.RIGHT
                yaw <= -config.directionEnterDegrees -> HeadDirection.LEFT
                else -> HeadDirection.CENTER
            }
            HeadDirection.LEFT -> if (yaw >= -config.directionExitDegrees) HeadDirection.CENTER else HeadDirection.LEFT
            HeadDirection.RIGHT -> if (yaw <= config.directionExitDegrees) HeadDirection.CENTER else HeadDirection.RIGHT
        }
        if (next != direction) {
            direction = next
            if (next == HeadDirection.CENTER) resetTurn() else {
                turnSinceMs = timestampMs
                turnEmitted = false
            }
        }
    }

    private fun updateShake(yaw: Float, timestampMs: Long): HeadMotionId? {
        if (shakeFirstSide != 0 && timestampMs - shakeStartedMs > config.shakeWindowMs) resetShake()
        val side = when {
            yaw >= config.shakeExcursionDegrees -> 1
            yaw <= -config.shakeExcursionDegrees -> -1
            else -> 0
        }
        if (shakeFirstSide == 0 && side != 0) {
            shakeFirstSide = side
            shakeStartedMs = timestampMs
        } else if (shakeFirstSide != 0 && side == -shakeFirstSide) {
            shakeOppositeSeen = true
        }
        if (shakeOppositeSeen && abs(yaw) <= config.shakeReturnDegrees) {
            resetShake()
            resetTurn()
            return HeadMotionId.SHAKE
        }
        return null
    }

    private fun updateNod(pitch: Float, timestampMs: Long): HeadMotionId? {
        if (nodSide != 0 && timestampMs - nodStartedMs > config.nodWindowMs) resetNod()
        val side = when {
            pitch >= config.nodExcursionDegrees -> 1
            pitch <= -config.nodExcursionDegrees -> -1
            else -> 0
        }
        if (nodSide == 0 && side != 0) {
            nodSide = side
            nodStartedMs = timestampMs
        }
        if (nodSide != 0 && abs(pitch) <= config.nodReturnDegrees) {
            resetNod()
            return HeadMotionId.NOD
        }
        return null
    }

    private fun updateTurn(timestampMs: Long): HeadMotionId? {
        val since = turnSinceMs ?: return null
        if (turnEmitted || shakeOppositeSeen || timestampMs - since < config.turnHoldMs) return null
        turnEmitted = true
        resetShake()
        return when (direction) {
            HeadDirection.LEFT -> HeadMotionId.TURN_LEFT
            HeadDirection.RIGHT -> HeadMotionId.TURN_RIGHT
            HeadDirection.CENTER -> null
        }
    }

    private fun eventUpdate(pose: HeadPose, event: HeadMotionId, timestampMs: Long): HeadMotionUpdate {
        cooldownUntilMs = timestampMs + config.eventCooldownMs
        resetNod()
        return HeadMotionUpdate(pose, direction, event, calibrated = true)
    }

    private fun resetTurn() {
        turnSinceMs = null
        turnEmitted = false
    }

    private fun resetShake() {
        shakeFirstSide = 0
        shakeStartedMs = 0L
        shakeOppositeSeen = false
    }

    private fun resetNod() {
        nodSide = 0
        nodStartedMs = 0L
    }

    private fun List<Float>.median(): Float {
        val ordered = sorted()
        val middle = ordered.size / 2
        return if (ordered.size % 2 == 1) ordered[middle] else (ordered[middle - 1] + ordered[middle]) / 2f
    }
}
