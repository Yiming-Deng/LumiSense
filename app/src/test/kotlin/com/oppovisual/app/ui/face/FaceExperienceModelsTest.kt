package com.oppovisual.app.ui.face

import org.junit.Assert.assertEquals
import org.junit.Test

class FaceExperienceModelsTest {
    @Test
    fun `only current avatar selection is restored`() {
        assertEquals(HeadgearId.CAT, HeadgearId.fromWireName("cat"))
    }

    @Test
    fun `removed and unknown selections migrate to off`() {
        listOf(null, "mecha", "bilibili_tv", "vtuber", "unknown").forEach { value ->
            assertEquals(HeadgearId.OFF, HeadgearId.fromWireName(value))
        }
    }
}
