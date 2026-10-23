package com.example.klipa.feature.preview

import com.example.klipa.core.model.Project
import com.example.klipa.core.model.TextStyle
import com.example.klipa.core.model.TrackKind
import com.example.klipa.core.model.Transform
import com.example.klipa.core.model.createClip
import com.example.klipa.core.model.createProject
import com.example.klipa.core.model.createTrack
import com.example.klipa.core.model.testAsset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MonitorGeometryTest {
    /** Titles measure 400 × 100 project pixels here. */
    private val textSize: (TextStyle) -> Pair<Int, Int> = { 400 to 100 }

    private fun box(rotation: Double = 0.0, scale: Double = 1.0) = LayerBox("c", centerX = 960.0, centerY = 540.0, width = 400.0, height = 100.0, scale = scale, rotation = rotation)

    @Test
    fun `boxes contain points along their own axes`() {
        val flat = box()
        assertTrue(flat.contains(1150.0, 580.0))
        assertFalse(flat.contains(1170.0, 540.0))
        assertTrue(flat.contains(1170.0, 540.0, slop = 12.0))
        // Turned a quarter, the box stands upright.
        val upright = box(rotation = 90.0)
        assertTrue(upright.contains(960.0, 730.0))
        assertFalse(upright.contains(1150.0, 540.0))
        // Scale counts, and a flipped (negative) scale is still the same size.
        assertTrue(box(scale = 2.0).contains(1350.0, 540.0))
        assertTrue(box(scale = -1.0).contains(1150.0, 540.0))
    }

    @Test
    fun `corners go clockwise from the top left and follow rotation`() {
        assertEquals(listOf(760.0 to 490.0, 1160.0 to 490.0, 1160.0 to 590.0, 760.0 to 590.0), box().corners())
        val (x, y) = box(rotation = 90.0).corners().first()
        assertEquals(1010.0, x, 1e-9)
        assertEquals(340.0, y, 1e-9)
    }

    @Test
    fun `layers stack as drawn, titles above media and upper tracks above lower ones`() {
        val base = createProject().copy(assets = listOf(testAsset))
        val overlay = createTrack(TrackKind.Video, "Video 2")
        val p: Project = base.copy(tracks = listOf(base.tracks[0], overlay) + base.tracks.drop(1)).let { withOverlay ->
            withOverlay.copy(
                clips = listOf(
                    createClip(testAsset, base.tracks[1].id, 0.0, 30).copy(id = "main", duration = 4.0),
                    createClip(testAsset, overlay.id, 0.0, 30).copy(id = "pip", duration = 4.0, transform = Transform(x = 600.0, y = -300.0, scale = 0.3)),
                    createClip(null, base.tracks[0].id, 0.0, 30).copy(id = "title", duration = 4.0, text = TextStyle(), transform = Transform(y = 400.0)),
                    createClip(testAsset, base.tracks[1].id, 5.0, 30).copy(id = "later", duration = 1.0),
                ),
            )
        }
        val boxes = layerBoxes(p, 1.0, textSize)
        assertEquals(listOf("title", "pip", "main"), boxes.map { it.clipId })
        // Media fill the frame by their shape before their own scale.
        assertEquals(LayerBox("pip", 1560.0, 240.0, 1920.0, 1080.0, 0.3, 0.0), boxes[1])
        assertEquals(400.0, boxes[0].width)

        assertEquals("title", hitTest(boxes, 960.0, 940.0))
        assertEquals("pip", hitTest(boxes, 1560.0, 240.0))
        assertEquals("main", hitTest(boxes, 300.0, 300.0))
        assertNull(hitTest(layerBoxes(p, 4.5, textSize), 960.0, 540.0))
    }
}
