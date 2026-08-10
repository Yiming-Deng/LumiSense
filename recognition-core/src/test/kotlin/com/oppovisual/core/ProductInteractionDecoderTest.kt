package com.oppovisual.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ProductInteractionDecoderTest {
    @Test
    fun `two high confidence palm frames use the fast ready path`() {
        val decoder = ProductInteractionDecoder()

        assertEquals(
            ProductInteractionStatus.HOLD_STILL,
            decoder.update(0L, listOf(hand(x = 0.30f, label = "palm"))).statuses[1],
        )
        assertEquals(
            ProductInteractionStatus.READY,
            decoder.update(34L, listOf(hand(x = 0.30f, label = "palm"))).statuses[1],
        )
    }

    @Test
    fun `vertical transition start poses use the same fast ready path`() {
        listOf("point", "fist", "stop").forEach { label ->
            val decoder = ProductInteractionDecoder()
            decoder.update(0L, listOf(hand(label = label)))
            assertEquals(
                ProductInteractionStatus.READY,
                decoder.update(34L, listOf(hand(label = label))).statuses[1],
                label,
            )
        }
    }

    @Test
    fun `single hand pinch is no longer a ready start pose`() {
        val decoder = ProductInteractionDecoder()

        repeat(12) { frame ->
            assertNotEquals(
                ProductInteractionStatus.READY,
                decoder.update(frame * 34L, listOf(hand(label = "thumb_index"))).statuses[1],
            )
        }
    }

    @Test
    fun `a stationary armed hand remains ready without oscillating`() {
        listOf("palm", "point", "fist", "stop").forEach { label ->
            val decoder = ProductInteractionDecoder()
            repeat(40) { frame ->
                val status = decoder.update(frame * 34L, listOf(hand(label = label))).statuses[1]
                if (frame >= 1) assertEquals(ProductInteractionStatus.READY, status, "$label frame=$frame")
            }
        }
    }

    @Test
    fun `small tracking jitter does not demote ready to hold steady`() {
        val decoder = ProductInteractionDecoder()
        repeat(80) { frame ->
            val jitter = if (frame % 2 == 0) 0.002f else -0.002f
            val status = decoder.update(
                frame * 34L,
                listOf(hand(x = 0.30f + jitter, y = 0.50f - jitter, label = "palm")),
            ).statuses[1]
            if (frame >= 1) assertEquals(ProductInteractionStatus.READY, status, "frame=$frame")
        }
    }

    @Test
    fun `fast ready evidence must be consecutive and high confidence`() {
        val decoder = ProductInteractionDecoder()

        decoder.update(0L, listOf(hand(label = "palm")))
        decoder.update(34L, listOf(hand(label = "point")))
        assertEquals(
            ProductInteractionStatus.HOLD_STILL,
            decoder.update(68L, listOf(hand(label = "palm"))).statuses[1],
        )

        val lowConfidence = ProductInteractionDecoder()
        repeat(3) { frame ->
            lowConfidence.update(frame * 34L, listOf(hand(label = "palm", score = 0.25f)))
        }
        assertNotEquals(
            ProductInteractionStatus.READY,
            lowConfidence.update(102L, listOf(hand(label = "palm", score = 0.25f))).statuses[1],
        )
    }

    @Test
    fun `missing frames and invalid landmarks reset fast ready evidence`() {
        val missing = ProductInteractionDecoder()
        missing.update(0L, listOf(hand(label = "palm")))
        missing.update(34L, emptyList())
        missing.update(68L, listOf(hand(label = "palm")))
        assertNotEquals(
            ProductInteractionStatus.READY,
            missing.update(102L, emptyList()).statuses[1],
        )

        val invalidLandmarks = ProductInteractionDecoder()
        invalidLandmarks.update(0L, listOf(hand(label = "palm")))
        invalidLandmarks.update(34L, listOf(hand(label = "palm", keypointScore = 0.1f)))
        invalidLandmarks.update(68L, listOf(hand(label = "palm")))
        assertNotEquals(
            ProductInteractionStatus.READY,
            invalidLandmarks.update(102L, listOf(hand(label = "palm", keypointScore = 0.1f))).statuses[1],
        )
    }

    @Test
    fun `point fast ready tolerates short classification gaps`() {
        listOf(
            hand(label = "no_gesture"),
            hand(label = "one", score = 0.20f),
        ).forEach { interference ->
            val decoder = ProductInteractionDecoder()
            decoder.update(0L, listOf(hand(label = "point", score = 0.20f)))
            decoder.update(34L, listOf(interference))
            assertEquals(
                ProductInteractionStatus.READY,
                decoder.update(68L, listOf(hand(label = "point", score = 0.20f))).statuses[1],
                interference.datasetLabel,
            )
        }
    }

    @Test
    fun `ready start pose hands off without leaving ready`() {
        val decoder = ProductInteractionDecoder()
        decoder.update(0L, listOf(hand(label = "stop")))
        assertEquals(ProductInteractionStatus.READY, decoder.update(34L, listOf(hand(label = "stop"))).statuses[1])
        assertEquals(ProductInteractionStatus.READY, decoder.update(68L, listOf(hand(label = "fist"))).statuses[1])
        assertEquals(ProductInteractionStatus.READY, decoder.update(102L, listOf(hand(label = "fist"))).statuses[1])
        decoder.update(136L, listOf(hand(label = "stop")))
        assertEquals(
            GestureId.SWIPE_UP,
            decoder.update(170L, listOf(hand(label = "stop"))).events.single().gesture,
        )
    }

    @Test
    fun `fist to stop can repeat after a fast fist return`() {
        val decoder = ProductInteractionDecoder()
        val events = mutableListOf<ProductGestureEvent>()
        var timestamp = 0L

        fun show(label: String, frames: Int) {
            repeat(frames) {
                events += decoder.update(timestamp, listOf(hand(label = label))).events
                timestamp += 34
            }
        }

        show("fist", 8)
        show("stop", 8)
        show("fist", 8)
        show("stop", 8)

        assertEquals(listOf(GestureId.SWIPE_UP, GestureId.SWIPE_UP), events.map { it.gesture })
    }

    @Test
    fun `horizontal waves keep the existing palm interaction`() {
        val cases = listOf(
            GestureId.SWIPE_LEFT to (-0.035f to 0f),
            GestureId.SWIPE_RIGHT to (0.035f to 0f),
        )
        cases.forEach { (expected, delta) ->
            val decoder = ProductInteractionDecoder()
            var timestamp = 0L
            repeat(16) {
                decoder.update(timestamp, listOf(hand(x = 0.5f, y = 0.5f, label = "palm")))
                timestamp += 34
            }
            val events = mutableListOf<ProductGestureEvent>()
            repeat(10) { step ->
                events += decoder.update(
                    timestamp,
                    listOf(hand(x = 0.5f + delta.first * (step + 1), y = 0.5f, label = "palm")),
                ).events
                timestamp += 34
            }
            assertEquals(listOf(expected), events.map { it.gesture }, expected.name)
        }
    }

    @Test
    fun `vertical swipes require the configured gesture transitions`() {
        val cases = listOf(
            Triple(GestureId.SWIPE_UP, "fist", "stop"),
            Triple(GestureId.SWIPE_DOWN, "stop", "stop_inverted"),
            Triple(GestureId.SWIPE_DOWN, "point", "stop_inverted"),
            Triple(GestureId.SWIPE_DOWN, "stop", "point"),
        )
        cases.forEach { (expected, start, end) ->
            assertEquals(expected, runVerticalSwipe(expected, start, end).single().gesture, "$start -> $end")
        }
    }

    @Test
    fun `unconfigured vertical transitions are rejected`() {
        assertTrue(runVerticalSwipe(GestureId.SWIPE_UP, "point", "stop").isEmpty())
        assertTrue(runVerticalSwipe(GestureId.SWIPE_UP, "grabbing", "stop").isEmpty())
        assertTrue(runVerticalSwipe(GestureId.SWIPE_UP, "stop_inverted", "stop").isEmpty())
    }

    @Test
    fun `vertical direction is determined by the gesture transition`() {
        val events = runVerticalSwipe(GestureId.SWIPE_DOWN, "point", "stop_inverted")
        assertEquals(GestureId.SWIPE_DOWN, events.single().gesture)
    }

    @Test
    fun `stop to point downward transition confirms on the first point frame`() {
        val decoder = ProductInteractionDecoder()
        val events = mutableListOf<ProductGestureEvent>()
        var timestamp = 0L

        fun show(label: String, frames: Int) {
            repeat(frames) {
                events += decoder.update(timestamp, listOf(hand(label = label))).events
                timestamp += 34
            }
        }

        show("stop", 4)
        show("point", 1)

        assertEquals(listOf(GestureId.SWIPE_DOWN), events.map { it.gesture })
    }

    @Test
    fun `vertical transition tolerates a short no gesture gap`() {
        val events = runVerticalSwipe(
            GestureId.SWIPE_UP,
            "fist",
            "stop",
            intermediateLabels = listOf("no_gesture", "no_gesture"),
        )
        assertEquals(GestureId.SWIPE_UP, events.single().gesture)
    }

    @Test
    fun `point to stop is no longer an upward route`() {
        val decoder = ProductInteractionDecoder()
        val events = mutableListOf<ProductGestureEvent>()
        var timestamp = 0L

        fun show(label: String, frames: Int) {
            repeat(frames) {
                events += decoder.update(timestamp, listOf(hand(label = label))).events
                timestamp += 34
            }
        }

        show("point", 4)
        show("stop", 6)

        assertTrue(events.isEmpty())
    }

    @Test
    fun `stop to inverted stop tolerates a missing hand gap`() {
        val decoder = ProductInteractionDecoder()
        val events = mutableListOf<ProductGestureEvent>()
        var timestamp = 0L

        fun show(label: String, frames: Int) {
            repeat(frames) {
                events += decoder.update(timestamp, listOf(hand(label = label))).events
                timestamp += 34
            }
        }

        show("stop", 4)
        show("no_gesture", 2)
        show("stop_inverted", 6)

        assertEquals(listOf(GestureId.SWIPE_DOWN), events.map { it.gesture })
    }

    @Test
    fun `downward transition tolerates the natural grabbing bridge`() {
        val decoder = ProductInteractionDecoder()
        val events = mutableListOf<ProductGestureEvent>()
        var timestamp = 0L
        fun show(label: String, frames: Int) {
            repeat(frames) {
                events += decoder.update(timestamp, listOf(hand(label = label))).events
                timestamp += 34
            }
        }
        show("stop", 4)
        show("grabbing", 2)
        show("no_gesture", 2)
        show("stop_inverted", 6)
        assertEquals(listOf(GestureId.SWIPE_DOWN), events.map { it.gesture })
    }

    @Test
    fun `vertical gesture transitions do not require displacement`() {
        val up = runVerticalSwipe(GestureId.SWIPE_UP, "fist", "stop", stepDistance = 0f)
        val down = runVerticalSwipe(GestureId.SWIPE_DOWN, "point", "stop_inverted", stepDistance = 0f)
        assertEquals(GestureId.SWIPE_UP, up.single().gesture)
        assertEquals(GestureId.SWIPE_DOWN, down.single().gesture)
    }

    @Test
    fun `stationary palm arms and first right stroke fires once`() {
        val decoder = ProductInteractionDecoder()
        val events = mutableListOf<ProductGestureEvent>()
        repeat(14) { frame ->
            events += decoder.update(frame * 34L, listOf(hand(x = 0.30f, label = "palm"))).events
        }
        assertEquals(ProductInteractionStatus.READY, decoder.update(480, listOf(hand(x = 0.30f, label = "palm"))).statuses[1])
        repeat(8) { step ->
            events += decoder.update(514L + step * 34L, listOf(hand(x = 0.30f + (step + 1) * 0.035f, label = "palm"))).events
        }
        repeat(8) { step ->
            events += decoder.update(786L + step * 34L, listOf(hand(x = 0.58f - (step + 1) * 0.035f, label = "palm"))).events
        }
        assertEquals(listOf(GestureId.SWIPE_RIGHT), events.map { it.gesture })
    }

    @Test
    fun `three consecutive horizontal swipes trigger without a sacrificial attempt`() {
        val decoder = ProductInteractionDecoder()
        val events = mutableListOf<ProductGestureEvent>()
        var timestamp = 0L

        fun holdUntilReady(x: Float) {
            var status = ProductInteractionStatus.IDLE
            var frames = 0
            while (status != ProductInteractionStatus.READY && frames < 30) {
                val update = decoder.update(timestamp, listOf(hand(x = x, label = "palm")))
                events += update.events
                status = requireNotNull(update.statuses[1])
                timestamp += 34
                frames++
            }
            assertEquals(ProductInteractionStatus.READY, status)
            // Allow the full one-second horizontal cooldown to elapse before
            // attempting the next wave.
            repeat(32) {
                events += decoder.update(timestamp, listOf(hand(x = x, label = "palm"))).events
                timestamp += 34
            }
        }

        fun swipe(from: Float, step: Float) {
            repeat(10) { index ->
                events += decoder.update(
                    timestamp,
                    listOf(hand(x = from + step * (index + 1), label = "palm")),
                ).events
                timestamp += 34
            }
        }

        holdUntilReady(0.30f)
        swipe(0.30f, 0.035f)
        holdUntilReady(0.65f)
        swipe(0.65f, -0.035f)
        holdUntilReady(0.30f)
        swipe(0.30f, 0.035f)

        assertEquals(
            listOf(GestureId.SWIPE_RIGHT, GestureId.SWIPE_LEFT, GestureId.SWIPE_RIGHT),
            events.map { it.gesture },
        )
    }

    @Test
    fun `stop and fist transitions alternate without a sacrificial attempt`() {
        val decoder = ProductInteractionDecoder()
        val events = mutableListOf<ProductGestureEvent>()
        var timestamp = 0L

        fun show(label: String, frames: Int) {
            repeat(frames) {
                events += decoder.update(timestamp, listOf(hand(label = label))).events
                timestamp += 34
            }
        }

        show("fist", 8)
        show("stop", 8)
        show("point", 8)
        show("stop_inverted", 8)
        show("fist", 8)
        show("stop", 8)

        assertEquals(
            listOf(GestureId.SWIPE_UP, GestureId.SWIPE_DOWN, GestureId.SWIPE_DOWN, GestureId.SWIPE_UP),
            events.map { it.gesture },
        )
    }

    @Test
    fun `short no gesture after arming does not cancel swipe`() {
        val decoder = ProductInteractionDecoder()
        var timestamp = 0L
        repeat(16) {
            decoder.update(timestamp, listOf(hand(x = 0.30f, label = "palm")))
            timestamp += 34
        }
        val labels = listOf("no_gesture", "no_gesture", "point", "point", "point", "point", "point", "point")
        val events = labels.mapIndexedNotNull { index, label ->
            decoder.update(timestamp + index * 34L, listOf(hand(x = 0.34f + index * 0.04f, label = label)))
                .events.singleOrNull()
        }
        assertEquals(GestureId.SWIPE_RIGHT, events.single().gesture)
    }

    @Test
    fun `single hand zoom emits signed scale factors`() {
        val zoomIn = runZoom("fist", "thumb_index", 0.04f, 0.04f)
        val zoomOut = runZoom("palm", "fist", 0.04f, 0.04f)
        assertEquals(GestureId.ZOOM_IN, zoomIn.gesture)
        assertTrue(requireNotNull(zoomIn.scaleFactor) > 1f)
        assertEquals(GestureId.ZOOM_OUT, zoomOut.gesture)
        assertTrue(requireNotNull(zoomOut.scaleFactor) < 1f)
    }

    @Test
    fun `single hand palm to fist zoom out tolerates grabbing bridge`() {
        val decoder = ProductInteractionDecoder()
        val events = mutableListOf<ProductGestureEvent>()
        var timestamp = 0L

        fun show(label: String, frames: Int) {
            repeat(frames) {
                events += decoder.update(timestamp, listOf(hand(label = label))).events
                timestamp += 50
            }
        }

        show("palm", 8)
        show("grabbing", 2)
        show("fist", 8)

        assertEquals(listOf(GestureId.ZOOM_OUT), events.map { it.gesture })
    }

    @Test
    fun `single hand palm to fist zoom out expires after the action window`() {
        val decoder = ProductInteractionDecoder()
        val events = mutableListOf<ProductGestureEvent>()
        var timestamp = 0L

        fun show(label: String, frames: Int) {
            repeat(frames) {
                events += decoder.update(timestamp, listOf(hand(label = label))).events
                timestamp += 50
            }
        }

        show("palm", 8)
        // The bridge has no frame-count limit, but the complete palm-to-fist
        // action must still finish within the normal two-second window.
        show("grabbing", 60)
        show("fist", 8)

        assertTrue(events.isEmpty())
    }

    @Test
    fun `old pinch to fist zoom out path is rejected`() {
        val decoder = ProductInteractionDecoder()
        val events = mutableListOf<ProductGestureEvent>()
        var timestamp = 0L

        repeat(8) {
            events += decoder.update(timestamp, listOf(hand(label = "thumb_index"))).events
            timestamp += 50
        }
        repeat(8) {
            events += decoder.update(timestamp, listOf(hand(label = "fist"))).events
            timestamp += 50
        }

        assertTrue(events.isEmpty())
    }

    @Test
    fun `single hand zoom does not require usable keypoints`() {
        val decoder = ProductInteractionDecoder()
        val events = mutableListOf<ProductGestureEvent>()
        var timestamp = 0L
        repeat(8) {
            events += decoder.update(
                timestamp,
                listOf(hand(label = "fist").copy(keypoints = emptyList())),
            ).events
            timestamp += 50
        }
        repeat(8) {
            events += decoder.update(
                timestamp,
                listOf(hand(label = "thumb_index").copy(keypoints = emptyList())),
            ).events
            timestamp += 50
        }
        assertEquals(GestureId.ZOOM_IN, events.single().gesture)
    }

    @Test
    fun `single hand zoom accepts pointing and victory poses as bridges`() {
        listOf(
            Triple("fist", "thumb_index", GestureId.ZOOM_IN),
            Triple("palm", "fist", GestureId.ZOOM_OUT),
        ).forEach { (start, target, expected) ->
            listOf("one", "peace", "peace_inverted").forEach { bridge ->
                val decoder = ProductInteractionDecoder()
                val events = mutableListOf<ProductGestureEvent>()
                var timestamp = 0L

                fun show(label: String, frames: Int) {
                    repeat(frames) {
                        events += decoder.update(timestamp, listOf(hand(label = label))).events
                        timestamp += 50
                    }
                }

                show(start, 3)
                show(bridge, 3)
                show(target, 2)

                assertEquals(listOf(expected), events.map { it.gesture }, "$start -> $bridge -> $target")
            }
        }
    }

    @Test
    fun `single hand shrink accepts multiple bridge poses before one fist frame`() {
        val decoder = ProductInteractionDecoder()
        val events = mutableListOf<ProductGestureEvent>()
        var timestamp = 0L

        fun show(label: String, frames: Int) {
            repeat(frames) {
                events += decoder.update(timestamp, listOf(hand(label = label))).events
                timestamp += 50
            }
        }

        show("palm", 3)
        show("one", 1)
        show("peace", 1)
        show("grip", 1)
        show("no_gesture", 1)
        show("fist", 2)

        assertEquals(listOf(GestureId.ZOOM_OUT), events.map { it.gesture })
    }

    @Test
    fun `single hand shrink accepts any static one-hand bridge`() {
        val decoder = ProductInteractionDecoder()
        val events = mutableListOf<ProductGestureEvent>()
        var timestamp = 0L

        fun show(label: String, frames: Int) {
            repeat(frames) {
                events += decoder.update(timestamp, listOf(hand(label = label))).events
                timestamp += 34
            }
        }

        show("palm", 3)
        show("call", 1)
        show("four", 1)
        show("thumb_index", 1)
        show("two_up_inverted", 1)
        show("fist", 2)

        assertEquals(listOf(GestureId.ZOOM_OUT), events.map { it.gesture })
    }

    @Test
    fun `single hand zoom confirms endpoint on two frames`() {
        listOf(
            Triple("fist", "thumb_index", GestureId.ZOOM_IN),
            Triple("palm", "fist", GestureId.ZOOM_OUT),
        ).forEach { (start, target, expected) ->
            val decoder = ProductInteractionDecoder()
            val events = mutableListOf<ProductGestureEvent>()
            var timestamp = 0L
            repeat(3) {
                events += decoder.update(timestamp, listOf(hand(label = start))).events
                timestamp += 50
            }
            repeat(2) {
                events += decoder.update(timestamp, listOf(hand(label = target))).events
                timestamp += 50
            }
            assertEquals(listOf(expected), events.map { it.gesture }, "$start -> $target")
        }
    }

    @Test
    fun `single hand zoom tolerates a short no gesture gap`() {
        listOf(
            Triple("fist", "thumb_index", GestureId.ZOOM_IN),
            Triple("palm", "fist", GestureId.ZOOM_OUT),
        ).forEach { (start, target, expected) ->
            val decoder = ProductInteractionDecoder()
            val events = mutableListOf<ProductGestureEvent>()
            var timestamp = 0L

            fun show(label: String, frames: Int) {
                repeat(frames) {
                    events += decoder.update(timestamp, listOf(hand(label = label))).events
                    timestamp += 50
                }
            }

            show(start, 8)
            show("no_gesture", 3)
            show(target, 8)

            assertEquals(listOf(expected), events.map { it.gesture }, "$start -> no_gesture -> $target")
        }
    }

    @Test
    fun `single hand zoom accepts no gesture within the action window`() {
        val decoder = ProductInteractionDecoder()
        val events = mutableListOf<ProductGestureEvent>()
        var timestamp = 0L

        fun show(label: String, frames: Int) {
            repeat(frames) {
                events += decoder.update(timestamp, listOf(hand(label = label))).events
                timestamp += 50
            }
        }

        show("fist", 8)
        show("no_gesture", 6)
        show("thumb_index", 8)

        assertEquals(listOf(GestureId.ZOOM_IN), events.map { it.gesture })
    }

    @Test
    fun `single hand zoom cancels an overlong one bridge`() {
        val decoder = ProductInteractionDecoder(ProductInteractionConfig(singleZoomBridgeFrames = 4))
        val events = mutableListOf<ProductGestureEvent>()
        var timestamp = 0L

        fun show(label: String, frames: Int) {
            repeat(frames) {
                events += decoder.update(timestamp, listOf(hand(label = label))).events
                timestamp += 50
            }
        }

        show("fist", 3)
        show("one", 5)
        show("thumb_index", 3)

        assertTrue(events.isEmpty())
    }

    @Test
    fun `single hand zoom accepts the relaxed bridge window`() {
        val decoder = ProductInteractionDecoder()
        val events = mutableListOf<ProductGestureEvent>()
        var timestamp = 0L

        fun show(label: String, frames: Int) {
            repeat(frames) {
                events += decoder.update(timestamp, listOf(hand(label = label))).events
                timestamp += 34
            }
        }

        show("palm", 3)
        show("one", 6)
        show("peace", 6)
        show("grip", 6)
        show("fist", 2)

        assertEquals(listOf(GestureId.ZOOM_OUT), events.map { it.gesture })
    }

    @Test
    fun `three2 no longer participates in single hand zoom`() {
        val decoder = ProductInteractionDecoder()
        val events = mutableListOf<ProductGestureEvent>()
        var timestamp = 0L
        repeat(8) {
            events += decoder.update(timestamp, listOf(hand(label = "fist", pinch = 0.035f))).events
            timestamp += 50
        }
        repeat(10) { step ->
            events += decoder.update(
                timestamp,
                listOf(hand(label = "three2", pinch = 0.035f + step * 0.007f)),
            ).events
            timestamp += 50
        }
        assertTrue(events.isEmpty())
    }

    @Test
    fun `two hand zoom follows fist distance and confirms when a fist is released`() {
        val zoomDecoder = ProductInteractionDecoder()
        val zoomEvents = mutableListOf<ProductGestureEvent>()
        var timestamp = 0L
        repeat(8) {
            zoomEvents += pairUpdate(zoomDecoder, timestamp, "thumb_index", 0.20f).events
            timestamp += 40
        }
        repeat(8) {
            zoomEvents += pairUpdate(zoomDecoder, timestamp, "fist", 0.20f).events
            timestamp += 40
        }
        var latestPreview: ProductScalePreview? = null
        repeat(8) { step ->
            val update = pairUpdate(zoomDecoder, timestamp, "fist", 0.20f + step * 0.025f)
            zoomEvents += update.events
            latestPreview = update.scalePreview ?: latestPreview
            timestamp += 40
        }
        repeat(5) {
            zoomEvents += pairUpdate(zoomDecoder, timestamp, "thumb_index", 0.375f).events
            timestamp += 40
        }
        assertEquals(1, zoomEvents.size)
        assertEquals(setOf(1, 2), zoomEvents.single().participantTrackIds)
        assertEquals(GestureId.TWO_HAND_ZOOM, zoomEvents.single().gesture)
        assertTrue(requireNotNull(zoomEvents.single().scaleFactor) > 1f)
        assertTrue(requireNotNull(latestPreview).scaleFactor > 1f)

        val translationDecoder = ProductInteractionDecoder()
        val translationEvents = mutableListOf<ProductGestureEvent>()
        timestamp = 0L
        repeat(8) {
            translationEvents += pairUpdate(translationDecoder, timestamp, "thumb_index", 0.20f).events
            timestamp += 40
        }
        repeat(8) {
            translationEvents += pairUpdate(translationDecoder, timestamp, "fist", 0.20f).events
            timestamp += 40
        }
        repeat(8) { step ->
            translationEvents += translationDecoder.update(
                timestamp,
                listOf(
                    hand(trackId = 1, x = 0.30f + step * 0.01f, label = "fist"),
                    hand(trackId = 2, x = 0.70f + step * 0.01f, label = "fist"),
                ),
            ).events
            timestamp += 40
        }
        repeat(5) {
            translationEvents += pairUpdate(translationDecoder, timestamp, "thumb_index", 0.20f).events
            timestamp += 40
        }
        assertTrue(translationEvents.isEmpty())
    }

    @Test
    fun `two hand zoom reports scale below one when fists move inward`() {
        val decoder = ProductInteractionDecoder()
        val events = mutableListOf<ProductGestureEvent>()
        var timestamp = 0L
        repeat(8) {
            events += pairUpdate(decoder, timestamp, "thumb_index2", 0.36f).events
            timestamp += 40
        }
        repeat(8) {
            events += pairUpdate(decoder, timestamp, "fist", 0.36f).events
            timestamp += 40
        }
        repeat(8) { step ->
            events += pairUpdate(decoder, timestamp, "fist", 0.36f - step * 0.025f).events
            timestamp += 40
        }
        repeat(5) {
            events += pairUpdate(decoder, timestamp, "thumb_index", 0.185f).events
            timestamp += 40
        }
        assertEquals(GestureId.TWO_HAND_ZOOM, events.single().gesture)
        assertTrue(requireNotNull(events.single().scaleFactor) < 1f)
    }

    @Test
    fun `two hand zoom freezes for one released or missing fist and resumes without a jump`() {
        val decoder = ProductInteractionDecoder()
        val events = mutableListOf<ProductGestureEvent>()
        var timestamp = 0L
        repeat(8) {
            events += pairUpdate(decoder, timestamp, "thumb_index", 0.20f).events
            timestamp += 40
        }
        repeat(8) {
            events += pairUpdate(decoder, timestamp, "fist", 0.20f).events
            timestamp += 40
        }
        var frozenScale = 1f
        repeat(6) { step ->
            val update = pairUpdate(decoder, timestamp, "fist", 0.20f + step * 0.025f)
            frozenScale = update.scalePreview?.scaleFactor ?: frozenScale
            timestamp += 40
        }

        repeat(6) {
            val update = decoder.update(
                timestamp,
                listOf(
                    hand(trackId = 1, x = 0.175f, label = "fist"),
                    hand(trackId = 2, x = 0.825f, label = "thumb_index"),
                ),
            )
            events += update.events
            assertEquals(frozenScale, requireNotNull(update.scalePreview).scaleFactor, 0.001f)
            timestamp += 40
        }
        repeat(10) {
            val update = decoder.update(
                timestamp,
                listOf(hand(trackId = 1, x = 0.175f, label = "fist")),
            )
            events += update.events
            assertEquals(frozenScale, requireNotNull(update.scalePreview).scaleFactor, 0.001f)
            timestamp += 40
        }
        assertTrue(events.isEmpty())

        var resumedScale = frozenScale
        repeat(8) {
            val update = pairUpdate(decoder, timestamp, "fist", 0.30f)
            resumedScale = update.scalePreview?.scaleFactor ?: resumedScale
            timestamp += 40
        }
        assertEquals(frozenScale, resumedScale, 0.001f)
        repeat(5) { step ->
            val update = pairUpdate(decoder, timestamp, "fist", 0.30f + step * 0.025f)
            resumedScale = update.scalePreview?.scaleFactor ?: resumedScale
            timestamp += 40
        }
        assertTrue(resumedScale > frozenScale)

        repeat(6) {
            events += pairUpdate(decoder, timestamp, "thumb_index", 0.40f).events
            timestamp += 40
        }
        assertEquals(GestureId.TWO_HAND_ZOOM, events.single().gesture)
        assertEquals(resumedScale, requireNotNull(events.single().scaleFactor), 0.001f)
    }

    @Test
    fun `two hand zoom exits when both tracked hands disappear`() {
        val decoder = ProductInteractionDecoder()
        var timestamp = 0L
        repeat(8) {
            decoder.update(timestamp, listOf(
                hand(trackId = 1, x = 0.30f, label = "thumb_index"),
                hand(trackId = 2, x = 0.70f, label = "thumb_index"),
            ))
            timestamp += 40
        }
        repeat(8) {
            decoder.update(timestamp, listOf(
                hand(trackId = 1, x = 0.30f, label = "fist"),
                hand(trackId = 2, x = 0.70f, label = "fist"),
            ))
            timestamp += 40
        }
        repeat(8) {
            decoder.update(timestamp, listOf(
                hand(trackId = 1, x = 0.20f, label = "fist"),
                hand(trackId = 2, x = 0.80f, label = "fist"),
            ))
            timestamp += 40
        }
        decoder.update(timestamp, emptyList())
        timestamp += 40
        val events = decoder.update(timestamp, emptyList()).events
        assertEquals(GestureId.TWO_HAND_ZOOM, events.single().gesture)
    }

    @Test
    fun `comparable hands disable singles while large size mismatch selects only the larger hand`() {
        fun run(boxScaleSecond: Float): List<ProductGestureEvent> {
            val decoder = ProductInteractionDecoder()
            val events = mutableListOf<ProductGestureEvent>()
            var timestamp = 0L
            repeat(16) {
                events += decoder.update(
                    timestamp,
                    listOf(
                        hand(trackId = 1, x = 0.35f, label = "fist"),
                        hand(trackId = 2, x = 0.70f, label = "palm", boxScale = boxScaleSecond),
                    ),
                ).events
                timestamp += 34
            }
            repeat(10) {
                events += decoder.update(
                    timestamp,
                    listOf(
                        hand(trackId = 1, x = 0.35f, label = "stop"),
                        hand(trackId = 2, x = 0.70f, label = "palm", boxScale = boxScaleSecond),
                    ),
                ).events
                timestamp += 34
            }
            return events
        }

        assertTrue(run(boxScaleSecond = 1f).isEmpty())
        assertEquals(GestureId.SWIPE_UP, run(boxScaleSecond = 0.4f).single().gesture)
    }

    private fun pairUpdate(
        decoder: ProductInteractionDecoder,
        timestampMs: Long,
        label: String,
        halfDistance: Float,
    ): ProductDecoderUpdate = decoder.update(
        timestampMs,
        listOf(
            hand(trackId = 1, x = 0.50f - halfDistance, label = label),
            hand(trackId = 2, x = 0.50f + halfDistance, label = label),
        ),
    )

    private fun runZoom(
        startLabel: String,
        endLabel: String,
        startPinch: Float,
        endPinch: Float,
    ): ProductGestureEvent {
        val decoder = ProductInteractionDecoder()
        val events = mutableListOf<ProductGestureEvent>()
        var timestamp = 0L
        repeat(8) {
            events += decoder.update(timestamp, listOf(hand(label = startLabel, pinch = startPinch))).events
            timestamp += 50
        }
        repeat(10) { step ->
            val progress = (step + 1) / 10f
            val pinch = startPinch + (endPinch - startPinch) * progress
            val label = if (step < 4) startLabel else endLabel
            events += decoder.update(timestamp, listOf(hand(label = label, pinch = pinch))).events
            timestamp += 50
        }
        return events.single()
    }

    private fun runVerticalSwipe(
        direction: GestureId,
        startLabel: String,
        endLabel: String,
        intermediateLabels: List<String> = emptyList(),
        stepDistance: Float = 0.035f,
    ): List<ProductGestureEvent> {
        val decoder = ProductInteractionDecoder()
        val events = mutableListOf<ProductGestureEvent>()
        var timestamp = 0L
        repeat(16) {
            events += decoder.update(timestamp, listOf(hand(y = 0.50f, label = startLabel))).events
            timestamp += 34
        }
        val sign = if (direction == GestureId.SWIPE_UP) -1f else 1f
        val labels = intermediateLabels + List(10) { step -> if (step < 2) startLabel else endLabel }
        labels.forEachIndexed { step, label ->
            events += decoder.update(
                timestamp,
                listOf(hand(y = 0.50f + sign * stepDistance * (step + 1), label = label)),
            ).events
            timestamp += 34
        }
        return events
    }

    private fun hand(
        trackId: Int = 1,
        x: Float = 0.5f,
        y: Float = 0.5f,
        label: String,
        pinch: Float = 0.04f,
        score: Float = 0.90f,
        keypointScore: Float = 0.95f,
        boxScale: Float = 1f,
    ): ProductHandObservation {
        val box = ProductBox(
            x - 0.10f * boxScale,
            y - 0.12f * boxScale,
            x + 0.10f * boxScale,
            y + 0.12f * boxScale,
        )
        val points = MutableList(21) { ProductKeypoint(x, y, keypointScore) }
        points[4] = ProductKeypoint(x - pinch / 2f, y - 0.03f, keypointScore)
        points[8] = ProductKeypoint(x + pinch / 2f, y - 0.03f, keypointScore)
        return ProductHandObservation(trackId, box, score, label, points)
    }
}
