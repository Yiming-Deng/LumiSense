package com.oppovisual.app.background

import com.oppovisual.core.GestureId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackgroundGestureMappingTest {
    @Test
    fun mapsOnlyProductDynamicEvents() {
        assertEquals(BackgroundGestureOperation.SWIPE_LEFT, backgroundOperationFor(GestureId.SWIPE_LEFT))
        assertEquals(BackgroundGestureOperation.SWIPE_RIGHT, backgroundOperationFor(GestureId.SWIPE_RIGHT))
        assertEquals(BackgroundGestureOperation.SWIPE_UP, backgroundOperationFor(GestureId.SWIPE_UP))
        assertEquals(BackgroundGestureOperation.SWIPE_DOWN, backgroundOperationFor(GestureId.SWIPE_DOWN))
        assertEquals(BackgroundGestureOperation.ZOOM_IN, backgroundOperationFor(GestureId.ZOOM_IN))
        assertEquals(BackgroundGestureOperation.ZOOM_OUT, backgroundOperationFor(GestureId.ZOOM_OUT))
        assertEquals(BackgroundGestureOperation.TWO_HAND_PINCH, backgroundOperationFor(GestureId.TWO_HAND_ZOOM))
    }

    @Test
    fun rejectsStaticAndUnsupportedDynamicEvents() {
        assertNull(backgroundOperationFor(GestureId.OPEN_PALM))
        assertNull(backgroundOperationFor(GestureId.CLICK_ONE))
        assertNull(backgroundOperationFor(GestureId.DOUBLE_CLICK_TWO))
    }
}
