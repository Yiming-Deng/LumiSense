package com.oppovisual.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GestureIdTest {
    @Test
    fun v2CapabilitySetIsStableAndUnique() {
        assertEquals(45, GestureId.entries.size)
        assertEquals(33, STATIC_GESTURES.size)
        assertEquals(12, DYNAMIC_GESTURES.size)
        assertEquals(4, INTERACTION_STATIC_GESTURES.size)
        assertEquals(7, INTERACTION_DYNAMIC_GESTURES.size)
        assertTrue(
            setOf(
                GestureId.CLICK_ONE,
                GestureId.CLICK_TWO,
                GestureId.OPEN_TWICE,
                GestureId.DOUBLE_CLICK_ONE,
                GestureId.DOUBLE_CLICK_TWO,
            ).none { it in INTERACTION_DYNAMIC_GESTURES },
        )
        assertEquals(8, PAIR_GESTURES.size)
        assertEquals(GestureId.entries.size, GestureId.entries.map { it.wireName }.toSet().size)
        assertTrue(GestureId.entries.all { it.wireName.startsWith("hand.") })
        assertEquals(2, GestureId.HAND_HEART.requiredHands)
        assertEquals(2, GestureId.TWO_HAND_ZOOM.requiredHands)
        assertEquals("双手放缩", GestureId.TWO_HAND_ZOOM.displayName)
        assertEquals("G11", GestureId.ZOOM_OUT.datasetLabel)
    }

    @Test
    fun productDisplayNamesMergeEquivalentGestureVariants() {
        assertEquals("数字四", GestureId.FOUR.displayName)
        assertEquals("胜利", GestureId.VICTORY.displayName)
        assertEquals("胜利", GestureId.PEACE_INVERTED.displayName)
        assertEquals("停止", GestureId.STOP.displayName)
        assertEquals("停止", GestureId.STOP_INVERTED.displayName)
        assertEquals(setOf("数字三"), setOf(
            GestureId.THREE.displayName,
            GestureId.THREE_VARIANT_2.displayName,
            GestureId.THREE_VARIANT_3.displayName,
        ))
        assertEquals("捏住", GestureId.THUMB_INDEX.displayName)
        assertEquals("放缩", GestureId.THUMB_INDEX_PAIR.displayName)
        assertEquals("两指", GestureId.TWO_UP.displayName)
        assertEquals("两指", GestureId.TWO_UP_INVERTED.displayName)
    }
}
