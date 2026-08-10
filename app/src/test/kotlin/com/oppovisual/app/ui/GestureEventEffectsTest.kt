package com.oppovisual.app.ui

import com.oppovisual.core.GestureId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureEventEffectsTest {
    @Test
    fun confirmedEmojiMappingsUseProductContract() {
        assertEquals("4️⃣", gestureEffectEmoji(GestureId.FOUR))
        assertEquals("🖕", gestureEffectEmoji(GestureId.MIDDLE_FINGER))
        assertEquals("✌️", gestureEffectEmoji(GestureId.PEACE_INVERTED))
        assertEquals("🤚", gestureEffectEmoji(GestureId.STOP))
        assertEquals("🤚", gestureEffectEmoji(GestureId.STOP_INVERTED))
        assertEquals("3️⃣", gestureEffectEmoji(GestureId.THREE))
        assertEquals("3️⃣", gestureEffectEmoji(GestureId.THREE_VARIANT_2))
        assertEquals("3️⃣", gestureEffectEmoji(GestureId.THREE_VARIANT_3))
    }

    @Test
    fun geometricOnlyGesturesHaveNoEmoji() {
        listOf(
            GestureId.LITTLE_FINGER,
            GestureId.TWO_UP,
            GestureId.TWO_UP_INVERTED,
        ).forEach { gesture -> assertNull(gestureEffectEmoji(gesture)) }
    }

    @Test
    fun previousEmojiContractIsPreserved() {
        assertEquals("📞", gestureEffectEmoji(GestureId.CALL))
        assertEquals("🫶", gestureEffectEmoji(GestureId.HAND_HEART))
        assertEquals("🖐", gestureEffectEmoji(GestureId.OPEN_PALM))
        assertEquals("🫵", gestureEffectEmoji(GestureId.POINT))
        assertEquals("🤌", gestureEffectEmoji(GestureId.GRIP))
        assertEquals("🤚", gestureEffectEmoji(GestureId.STOP))
        assertEquals("🤚", gestureEffectEmoji(GestureId.STOP_INVERTED))
        assertEquals("🔫", gestureEffectEmoji(GestureId.THREE_GUN))
        assertEquals("🤏", gestureEffectEmoji(GestureId.THUMB_INDEX))
        assertEquals("🤏🤏", gestureEffectEmoji(GestureId.THUMB_INDEX_PAIR))
    }

    @Test
    fun emojiParticleAlphaFadesAtBothEnds() {
        assertEquals(0f, emojiParticleAlpha(0f), 0.001f)
        assertTrue(emojiParticleAlpha(0.12f) in 0.0f..0.64f)
        assertTrue(emojiParticleAlpha(0.5f) > emojiParticleAlpha(0.12f))
        assertTrue(emojiParticleAlpha(0.99f) < emojiParticleAlpha(0.5f))
        assertEquals(0f, emojiParticleAlpha(1f), 0.001f)
    }
}
