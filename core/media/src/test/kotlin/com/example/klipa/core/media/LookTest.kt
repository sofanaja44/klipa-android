package com.example.klipa.core.media

import com.example.klipa.core.model.Adjustments
import com.example.klipa.core.model.ChromaKey
import com.example.klipa.core.model.Clip
import com.example.klipa.core.model.MaskShape
import com.example.klipa.core.model.ShapeMask
import com.example.klipa.core.model.createClip
import com.example.klipa.core.model.createProject
import com.example.klipa.core.model.testAsset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The look's math, checked against values worked out from ReaCut's 8-bit pipeline
 * (src/core/color.ts and visual.ts). The GLSL shader computes the same; it was run in
 * WebGL against ReaCut's own functions (tools/shader-check).
 */
class LookTest {
    private val tolerance = 1e-6

    private fun pixel(look: Look, r: Double, g: Double, b: Double, a: Double = 1.0, x: Double = 32.0, y: Double = 24.0) =
        lookPixel(look, floatArrayOf(r.toFloat(), g.toFloat(), b.toFloat(), a.toFloat()), x, y, 64, 48).map { it.toDouble() }

    private fun assertPixel(expected: List<Double>, actual: List<Double>) {
        expected.zip(actual).forEach { (e, a) -> assertEquals(e, a, 1e-5, "expected $expected, got $actual") }
    }

    @Test
    fun `a neutral look changes nothing`() {
        assertTrue(Look.Neutral.isNeutral)
        assertPixel(listOf(0.2, 0.5, 0.9, 0.5), pixel(Look.Neutral, 0.2, 0.5, 0.9, 0.5))
    }

    @Test
    fun `brightness then contrast around mid gray, clamped in between as ReaCut does`() {
        assertPixel(listOf(0.7, 0.7, 0.7, 1.0), pixel(Look(brightness = 0.1, contrast = 2.0), 0.5, 0.5, 0.5))
        // 0.8 + 0.5 clamps to 1 before contrast: (1 − 0.5) × 0.5 + 0.5.
        assertPixel(listOf(0.75, 0.75, 0.75, 1.0), pixel(Look(brightness = 0.5, contrast = 0.5), 0.8, 0.8, 0.8))
    }

    @Test
    fun `saturation scales around Rec 709 luma`() {
        val luma = 0.2126 * 0.9 + 0.7152 * 0.3 + 0.0722 * 0.1
        assertPixel(listOf(luma, luma, luma, 1.0), pixel(Look(saturation = 0.0), 0.9, 0.3, 0.1))
        val l = 0.2126 * 0.6 + 0.7152 * 0.4 + 0.0722 * 0.4
        assertPixel(listOf(l + (0.6 - l) * 1.5, l + (0.4 - l) * 1.5, l + (0.4 - l) * 1.5, 1.0), pixel(Look(saturation = 1.5), 0.6, 0.4, 0.4))
    }

    @Test
    fun `temperature moves 35 levels between red and blue`() {
        val tint = 35.0 / 255
        assertPixel(listOf(0.5 + tint, 0.5, 0.5 - tint, 1.0), pixel(Look(temperature = 1.0), 0.5, 0.5, 0.5))
        assertPixel(listOf(0.5 - tint * 0.5, 0.5, 0.5 + tint * 0.5, 1.0), pixel(Look(temperature = -0.5), 0.5, 0.5, 0.5))
    }

    @Test
    fun `the chroma key removes the key color and fades in across the blend band`() {
        val key = Look(key = ChromaKey(enabled = true, color = "#00ff00", similarity = 0.1, blend = 0.2))
        assertEquals(0.0, pixel(key, 0.0, 1.0, 0.0)[3], tolerance)
        assertEquals(1.0, pixel(key, 1.0, 0.0, 1.0)[3], tolerance)
        // Distance √(0.4² × 3)/√3 = 0.4: past similarity and blend.
        assertEquals(1.0, pixel(key, 0.4, 0.6, 0.4)[3], tolerance)
        // Distance 0.2: halfway through the band.
        assertEquals(0.5, pixel(key, 0.2, 0.8, 0.2)[3], 1e-5)
        // Existing transparency is kept.
        assertEquals(0.25, pixel(key, 0.2, 0.8, 0.2, a = 0.5)[3], 1e-5)
        // RGB is untouched; a disabled key is no key.
        assertEquals(0.2, pixel(key, 0.2, 0.8, 0.2)[0], 1e-5)
        assertNull(Look.of(createClip(testAsset, "t", 0.0, 30).copy(chromaKey = ChromaKey(false, "#00ff00", 0.1, 0.0))).key)
    }

    @Test
    fun `masks keep a centered shape, feathered across its edge`() {
        val rectangle = ShapeMask(MaskShape.Rectangle, width = 0.5, height = 0.5, feather = 0.0, invert = false)
        // 64 × 48 frame: the rectangle spans x 16..48 and y 12..36.
        assertEquals(1.0, maskCoverage(rectangle, 32.0, 24.0, 64, 48), tolerance)
        assertEquals(1.0, maskCoverage(rectangle, 16.5, 12.5, 64, 48), tolerance)
        assertEquals(0.0, maskCoverage(rectangle, 15.5, 24.0, 64, 48), tolerance)
        // A feather of 0.25 × 48 = 12 px is centered on the edge: half coverage on it.
        assertEquals(0.5, maskCoverage(rectangle.copy(feather = 0.25), 16.0, 24.0, 64, 48), tolerance)
        assertEquals(0.0, maskCoverage(rectangle.copy(invert = true), 32.0, 24.0, 64, 48), tolerance)

        val ellipse = ShapeMask(MaskShape.Ellipse, width = 1.0, height = 1.0, feather = 0.0, invert = false)
        assertEquals(1.0, maskCoverage(ellipse, 32.0, 24.0, 64, 48), tolerance)
        assertEquals(0.0, maskCoverage(ellipse, 0.5, 0.5, 64, 48), tolerance)
        assertEquals(1.0, maskCoverage(ellipse, 32.0, 0.5, 64, 48), tolerance)
        assertEquals(0.0, pixel(Look(mask = ellipse), 0.5, 0.5, 0.5, x = 0.5, y = 0.5)[3], tolerance)
    }

    @Test
    fun `looks know when they leave pixels alone or cut them out`() {
        val clip = createClip(testAsset, "t", 0.0, 30)
        assertTrue(Look.of(clip).isNeutral)
        // Blur is not part of the per-pixel look.
        assertTrue(Look.of(clip.copy(adjustments = Adjustments(blur = 4.0))).isNeutral)
        assertFalse(Look.of(clip.copy(adjustments = Adjustments(temperature = 0.1))).isNeutral)
        assertFalse(Look.of(clip.copy(adjustments = Adjustments(temperature = 0.1))).cutsOut)
        assertTrue(Look.of(clip.copy(mask = ShapeMask(MaskShape.Ellipse, 1.0, 1.0, 0.0, false))).cutsOut)
    }

    @Test
    fun `still frames get the same colors from a color matrix`() {
        assertNull(Look.Neutral.colorMatrix())
        assertNull(Look(key = ChromaKey(true, "#00ff00", 0.1, 0.0)).colorMatrix())
        val cinema = Look(brightness = -0.04, contrast = 1.22, saturation = 0.78, temperature = -0.08)
        val matrix = cinema.colorMatrix()!!
        // Mid-range colors, where ReaCut never clamps between stages.
        for (rgb in listOf(listOf(0.5, 0.5, 0.5), listOf(0.6, 0.45, 0.4), listOf(0.35, 0.55, 0.62))) {
            val expected = pixel(cinema, rgb[0], rgb[1], rgb[2])
            for (row in 0..2) {
                val value = (0..2).sumOf { matrix[row * 5 + it] * rgb[it] * 255 } + matrix[row * 5 + 4]
                assertEquals(expected[row], value / 255, 1e-5)
            }
        }
    }

    @Test
    fun `blur buckets double`() {
        assertEquals(listOf(0, 1, 1, 2, 3, 3, 4, 8), listOf(0.0, 0.5, 1.0, 2.0, 3.0, 4.0, 5.0, 100.0).map(::blurBucket))
        assertEquals(listOf(1.0, 2.0, 4.0, 128.0), listOf(1, 2, 3, 8).map(::bucketMaxBlur))
    }

    @Test
    fun `look values and volume are read live, so only their presence reloads playback`() {
        val base = createProject().copy(assets = listOf(testAsset))
        val clip = createClip(testAsset, base.tracks[1].id, 0.0, base.fps)
        fun signature(change: (Clip) -> Clip) = playbackSignature(base.copy(clips = listOf(change(clip))))

        val warm = signature { it.copy(adjustments = Adjustments(temperature = 0.2)) }
        assertEquals(warm, signature { it.copy(adjustments = Adjustments(temperature = 0.5, contrast = 1.3)) })
        assertNotEquals(warm, signature { it })

        val keyed = signature { it.copy(chromaKey = ChromaKey(true, "#00ff00", 0.1, 0.05)) }
        assertEquals(keyed, signature { it.copy(chromaKey = ChromaKey(true, "#0000ff", 0.4, 0.0)) })
        // Cutting out changes how layers blend, so it reloads.
        assertNotEquals(keyed, warm)

        // Volume away from unity is a live gain stage.
        assertEquals(signature { it.copy(volume = 0.4) }, signature { it.copy(volume = 1.7) })
        assertNotEquals(signature { it.copy(volume = 0.4) }, signature { it })

        assertEquals(signature { it.copy(adjustments = Adjustments(blur = 3.0)) }, signature { it.copy(adjustments = Adjustments(blur = 4.0)) })
        assertNotEquals(signature { it.copy(adjustments = Adjustments(blur = 4.0)) }, signature { it.copy(adjustments = Adjustments(blur = 5.0)) })
    }
}
