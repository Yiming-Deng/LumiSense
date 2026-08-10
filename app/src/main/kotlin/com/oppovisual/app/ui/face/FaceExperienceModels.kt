package com.oppovisual.app.ui.face

import com.oppovisual.core.ExpressionId
import com.oppovisual.core.HeadMotionId

enum class FaceExperienceMode(val displayName: String) {
    FREE("自由"),
    CHALLENGE("挑战"),
}

enum class HeadgearId(val wireName: String, val contentDescription: String) {
    OFF("off", "关闭虚拟人脸"),
    CAT("cat", "虚拟人脸"),
    ;

    companion object {
        fun fromWireName(value: String?): HeadgearId = when (value) {
            "cat" -> CAT
            else -> OFF
        }
    }
}

enum class ChallengePhase {
    READY,
    COUNTDOWN,
    PROMPT,
    SUCCESS,
    TIMEOUT,
    PAUSED,
    RESULT,
}

data class ChallengeAttempt(
    val target: ChallengeTarget,
    val success: Boolean,
    val responseMs: Long?,
    val points: Int,
)

sealed interface ChallengeTarget {
    val displayName: String

    data class Expression(val expression: ExpressionId) : ChallengeTarget {
        override val displayName: String get() = expression.displayName
    }

    data class HeadMotion(val motion: HeadMotionId) : ChallengeTarget {
        override val displayName: String get() = motion.displayName
    }
}

data class ChallengeUiState(
    val phase: ChallengePhase = ChallengePhase.READY,
    val sequence: List<ChallengeTarget> = emptyList(),
    val roundIndex: Int = 0,
    val remainingMs: Long = 0,
    val score: Int = 0,
    val bestScore: Int = 0,
    val combo: Int = 0,
    val bestCombo: Int = 0,
    val attempts: List<ChallengeAttempt> = emptyList(),
    val pausedFrom: ChallengePhase? = null,
) {
    val target: ChallengeTarget?
        get() = sequence.getOrNull(roundIndex)

    val completedRounds: Int
        get() = attempts.size
}

val FACE_EFFECT_EXPRESSIONS = setOf(
    ExpressionId.SMILE,
    ExpressionId.MOUTH_OPEN,
    ExpressionId.MOUTH_PUCKER,
    ExpressionId.BROW_RAISE,
)
