package com.oppovisual.app

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ManifestOrientationPolicyTest {
    @Test
    fun `main activity leaves orientation under system control`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val activity = Regex(
            """<activity\s+[\s\S]*?android:name="\.MainActivity"[\s\S]*?>""",
        ).find(manifest)?.value

        assertTrue(activity != null)
        assertTrue("screenOrientation" !in activity.orEmpty())
    }
}
