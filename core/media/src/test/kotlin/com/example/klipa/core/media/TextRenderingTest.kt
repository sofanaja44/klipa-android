package com.example.klipa.core.media

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import com.example.klipa.core.model.Project
import com.example.klipa.core.model.TextStyle
import com.example.klipa.core.model.Transform
import com.example.klipa.core.model.createClip
import com.example.klipa.core.model.createProject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TextRenderingTest {
    @Test
    fun `layers are padded and lines are 1_3 em apart, as in ReaCut`() {
        val style = TextStyle(text = "Lisbon", fontSize = 72.0, strokeWidth = 2.0)
        // Padding ceil(72 × 0.25 + 2) = 20 on each side; one line is 72 × 1.3 high.
        val one = assertNotNull(renderText(style))
        assertEquals(134, one.height)
        val two = assertNotNull(renderText(style.copy(text = "Lisbon\nat night")))
        assertEquals(228, two.height)
        assertTrue(two.width > one.width)
        // At half scale everything halves: padding 10, line 46.8.
        assertEquals(67, assertNotNull(renderText(style, scale = 0.5f)).height)
    }

    @Test
    fun `the background fills the layer and the words take the text color`() {
        val bitmap = assertNotNull(renderText(TextStyle(text = "I", fontSize = 100.0, color = "#00ff00", background = "#ff0000", strokeWidth = 0.0)))
        assertEquals(0xFFFF0000.toInt(), bitmap.getPixel(0, 0))
        assertEquals(0xFF00FF00.toInt(), bitmap.getPixel(bitmap.width / 2, bitmap.height / 2))
        // A transparent background leaves the corners clear.
        assertEquals(0, assertNotNull(renderText(TextStyle(text = "I"))).getPixel(0, 0))
    }

    @Test
    fun `blank, tiny or huge text renders nothing`() {
        assertNull(renderText(TextStyle(text = " \n ")))
        assertNull(renderText(TextStyle(text = "Lisbon"), scale = 0.001f))
        assertNull(renderText(TextStyle(text = "x".repeat(2000), fontSize = 400.0)))
    }

    @Test
    fun `a text lane renders each title once and hides between titles`() {
        val base = createProject()
        fun title(start: Double, duration: Double) =
            createClip(null, base.tracks[0].id, start, base.fps).copy(duration = duration, text = TextStyle(text = "t$start"), transform = Transform(x = 100.0))
        val first = title(1.0, 2.0)
        val second = title(4.0, 1.0)
        val project: Project = base.copy(clips = listOf(first, second))
        var renders = 0
        val overlay = TextLaneOverlay(project, listOf(first, second)) {
            renders++
            createBitmap(100, 40)
        }

        val shown: Bitmap = overlay.getBitmap(1_500_000)
        assertEquals(100, shown.width)
        assertSame(shown, overlay.getBitmap(2_900_000))
        assertEquals(1, renders)
        // Placed like a composited layer, at its local time.
        assertEquals(layerOverlay(project, first, 0.5), overlay.getOverlaySettings(1_500_000))

        assertEquals(0f, overlay.getOverlaySettings(3_500_000).alphaScale)
        assertEquals(1, overlay.getBitmap(3_500_000).width)

        overlay.getBitmap(4_200_000)
        assertEquals(2, renders)
        assertEquals(0f, overlay.getOverlaySettings(5_000_000).alphaScale)
    }
}
