package com.oppovisual.core

enum class GestureKind {
    STATIC_SINGLE,
    STATIC_PAIR,
    DYNAMIC_SINGLE,
    DYNAMIC_PAIR,
}

enum class RecognitionMode {
    DISPLAY,
    INTERACTION,
}

enum class GestureId(
    val wireName: String,
    val displayName: String,
    val kind: GestureKind,
    val datasetLabel: String,
) {
    CALL("hand.call", "打电话", GestureKind.STATIC_SINGLE, "call"),
    THUMB_DOWN("hand.thumb_down", "点踩", GestureKind.STATIC_SINGLE, "dislike"),
    CLOSED_FIST("hand.closed_fist", "握拳", GestureKind.STATIC_SINGLE, "fist"),
    FOUR("hand.four", "数字四", GestureKind.STATIC_SINGLE, "four"),
    GRABBING("hand.grabbing", "抓取", GestureKind.STATIC_SINGLE, "grabbing"),
    GRIP("hand.grip", "紧握", GestureKind.STATIC_SINGLE, "grip"),
    HAND_HEART("hand.heart", "双手比心", GestureKind.STATIC_PAIR, "hand_heart"),
    HAND_HEART_ALT("hand.heart_alt", "双手比心二", GestureKind.STATIC_PAIR, "hand_heart2"),
    HOLY("hand.holy", "双手合十", GestureKind.STATIC_PAIR, "holy"),
    THUMB_UP("hand.thumb_up", "点赞", GestureKind.STATIC_SINGLE, "like"),
    LITTLE_FINGER("hand.little_finger", "小拇指", GestureKind.STATIC_SINGLE, "little_finger"),
    MIDDLE_FINGER("hand.middle_finger", "中指", GestureKind.STATIC_SINGLE, "middle_finger"),
    MUTE("hand.mute", "静音", GestureKind.STATIC_SINGLE, "mute"),
    OK("hand.ok", "OK", GestureKind.STATIC_SINGLE, "ok"),
    POINTING_UP("hand.pointing_up", "向上指", GestureKind.STATIC_SINGLE, "one"),
    OPEN_PALM("hand.open_palm", "张开手掌", GestureKind.STATIC_SINGLE, "palm"),
    VICTORY("hand.victory", "胜利", GestureKind.STATIC_SINGLE, "peace"),
    PEACE_INVERTED("hand.peace_inverted", "胜利", GestureKind.STATIC_SINGLE, "peace_inverted"),
    POINT("hand.point", "指向", GestureKind.STATIC_SINGLE, "point"),
    ROCK("hand.rock", "摇滚", GestureKind.STATIC_SINGLE, "rock"),
    STOP("hand.stop", "停止", GestureKind.STATIC_SINGLE, "stop"),
    STOP_INVERTED("hand.stop_inverted", "停止", GestureKind.STATIC_SINGLE, "stop_inverted"),
    TAKE_PICTURE("hand.take_picture", "拍照", GestureKind.STATIC_PAIR, "take_picture"),
    THREE("hand.three", "数字三", GestureKind.STATIC_SINGLE, "three"),
    THREE_VARIANT_2("hand.three.variant2", "数字三", GestureKind.STATIC_SINGLE, "three2"),
    THREE_VARIANT_3("hand.three.variant3", "数字三", GestureKind.STATIC_SINGLE, "three3"),
    THREE_GUN("hand.three_gun", "三指手枪", GestureKind.STATIC_SINGLE, "three_gun"),
    THUMB_INDEX("hand.thumb_index", "捏住", GestureKind.STATIC_SINGLE, "thumb_index"),
    THUMB_INDEX_PAIR("hand.thumb_index.pair", "放缩", GestureKind.STATIC_PAIR, "thumb_index2"),
    TIMEOUT("hand.timeout", "暂停", GestureKind.STATIC_PAIR, "timeout"),
    TWO_UP("hand.two_up", "两指", GestureKind.STATIC_SINGLE, "two_up"),
    TWO_UP_INVERTED("hand.two_up_inverted", "两指", GestureKind.STATIC_SINGLE, "two_up_inverted"),
    XSIGN("hand.xsign", "双手交叉", GestureKind.STATIC_PAIR, "xsign"),
    CLICK_ONE("hand.click.one_finger", "单指点击", GestureKind.DYNAMIC_SINGLE, "G01"),
    CLICK_TWO("hand.click.two_fingers", "双指点击", GestureKind.DYNAMIC_SINGLE, "G02"),
    SWIPE_UP("hand.swipe.up", "向上滑动", GestureKind.DYNAMIC_SINGLE, "G03"),
    SWIPE_DOWN("hand.swipe.down", "向下滑动", GestureKind.DYNAMIC_SINGLE, "G04"),
    SWIPE_LEFT("hand.swipe.left", "向左滑动", GestureKind.DYNAMIC_SINGLE, "G05"),
    SWIPE_RIGHT("hand.swipe.right", "向右滑动", GestureKind.DYNAMIC_SINGLE, "G06"),
    OPEN_TWICE("hand.open.twice", "张手两次", GestureKind.DYNAMIC_SINGLE, "G07"),
    DOUBLE_CLICK_ONE("hand.double_click.one_finger", "单指双击", GestureKind.DYNAMIC_SINGLE, "G08"),
    DOUBLE_CLICK_TWO("hand.double_click.two_fingers", "双指双击", GestureKind.DYNAMIC_SINGLE, "G09"),
    ZOOM_IN("hand.zoom.in", "放大", GestureKind.DYNAMIC_SINGLE, "G10"),
    ZOOM_OUT("hand.zoom.out", "缩小", GestureKind.DYNAMIC_SINGLE, "G11"),
    TWO_HAND_ZOOM("hand.zoom.two_hand", "双手放缩", GestureKind.DYNAMIC_PAIR, "PRODUCT_PAIR_ZOOM"),
    ;

    val isDynamic: Boolean get() = kind == GestureKind.DYNAMIC_SINGLE || kind == GestureKind.DYNAMIC_PAIR
    val requiredHands: Int get() = if (kind == GestureKind.STATIC_PAIR || kind == GestureKind.DYNAMIC_PAIR) 2 else 1
}

val STATIC_GESTURES: Set<GestureId> = GestureId.entries.filterNot { it.isDynamic }.toSet()
val DYNAMIC_GESTURES: Set<GestureId> = GestureId.entries.filter { it.isDynamic }.toSet()
val PAIR_GESTURES: Set<GestureId> = GestureId.entries.filter { it.requiredHands == 2 }.toSet()
val INTERACTION_STATIC_GESTURES: Set<GestureId> = setOf(
    GestureId.OPEN_PALM,
    GestureId.CLOSED_FIST,
    GestureId.THUMB_UP,
    GestureId.THUMB_DOWN,
)
val INTERACTION_DYNAMIC_GESTURES: Set<GestureId> = setOf(
    GestureId.SWIPE_UP,
    GestureId.SWIPE_DOWN,
    GestureId.SWIPE_LEFT,
    GestureId.SWIPE_RIGHT,
    GestureId.ZOOM_IN,
    GestureId.ZOOM_OUT,
    GestureId.TWO_HAND_ZOOM,
)
