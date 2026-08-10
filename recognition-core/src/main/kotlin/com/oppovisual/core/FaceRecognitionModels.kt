package com.oppovisual.core

enum class ExpressionId(val wireName: String, val displayName: String) {
    NONE("none", "无明确表情"),
    SMILE("smile", "微笑"),
    MOUTH_OPEN("mouth_open", "张嘴"),
    BROW_RAISE("brow_raise", "抬眉"),
    MOUTH_PUCKER("mouth_pucker", "嘟嘴"),
    LEFT_WINK("left_wink", "左眼闭合"),
    RIGHT_WINK("right_wink", "右眼闭合"),
    BOTH_EYES_BLINK("both_eyes_blink", "双眼眨眼"),
}

enum class HeadDirection(val wireName: String, val displayName: String) {
    CENTER("center", "正中"),
    LEFT("left", "向左"),
    RIGHT("right", "向右"),
}

enum class HeadMotionId(val wireName: String, val displayName: String) {
    TURN_LEFT("turn_left", "向左转头"),
    TURN_RIGHT("turn_right", "向右转头"),
    NOD("nod", "点头"),
    SHAKE("shake", "摇头"),
}

data class HeadPose(
    val yawDegrees: Float,
    val pitchDegrees: Float,
    val rollDegrees: Float,
)

data class ExpressionDecision(
    val expression: ExpressionId,
    val confidence: Float,
    val isNewEvent: Boolean,
    val scores: Map<ExpressionId, Float>,
)

data class HeadMotionUpdate(
    val pose: HeadPose,
    val direction: HeadDirection,
    val event: HeadMotionId? = null,
    val calibrated: Boolean,
)
