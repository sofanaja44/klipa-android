package com.example.klipa.core.edit

import com.example.klipa.core.model.CropRect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

// The first five cases are ReaCut's tests/visual-ui.test.ts "source crop controls".

class CropEditsTest {
    private fun assertCrop(expected: CropRect, actual: CropRect) {
        for ((e, a) in listOf(expected.x to actual.x, expected.y to actual.y, expected.width to actual.width, expected.height to actual.height)) {
            assertEquals(e, a, 1e-9, "expected $expected, got $actual")
        }
    }

    @Test
    fun `centers a maximal square crop in a landscape source`() {
        assertEquals(CropRect(0.21875, 0.0, 0.5625, 1.0), centeredCrop(1920.0, 1080.0, 1.0))
    }

    @Test
    fun `centers a horizontal crop in portrait footage`() {
        val crop = centeredCrop(1080.0, 1920.0, 16.0 / 9)
        assertEquals(1.0, crop.width)
        assertEquals(0.31640625, crop.height, 1e-9)
        assertEquals((1 - crop.height) / 2, crop.y, 1e-9)
    }

    @Test
    fun `leaves matching proportions untouched and handles unavailable dimensions safely`() {
        assertEquals(FullCrop, centeredCrop(1920.0, 1080.0, 16.0 / 9))
        assertEquals(FullCrop, centeredCrop(0.0, 0.0, 1.0))
    }

    @Test
    fun `keeps position edits inside source while preserving crop dimensions`() {
        assertEquals(CropRect(0.5, 0.1, 0.5, 0.7), updateCropField(CropRect(0.1, 0.1, 0.5, 0.7), CropField.X, 0.9))
    }

    @Test
    fun `limits extent edits to remaining source and disallows zero-size crops`() {
        val crop = CropRect(0.2, 0.1, 0.5, 0.7)
        assertEquals(0.8, updateCropField(crop, CropField.Width, 2.0).width)
        assertEquals(0.01, updateCropField(crop, CropField.Height, 0.0).height)
        assertSame(crop, updateCropField(crop, CropField.X, Double.NaN))
    }

    @Test
    fun `moving keeps the size and stays inside`() {
        assertCrop(CropRect(0.5, 0.0, 0.5, 0.5), moveCrop(CropRect(0.2, 0.2, 0.5, 0.5), 0.9, -0.9))
    }

    @Test
    fun `a corner drag keeps the opposite corner and the limits`() {
        val crop = CropRect(0.2, 0.2, 0.5, 0.5)
        assertCrop(CropRect(0.1, 0.0, 0.6, 0.7), resizeCrop(crop, CropCorner.TopLeft, -0.1, -0.4))
        assertCrop(CropRect(0.2, 0.2, 0.8, 0.3), resizeCrop(crop, CropCorner.BottomRight, 0.9, -0.2))
        // Never smaller than the minimum side.
        assertEquals(MIN_CROP_SIDE, resizeCrop(crop, CropCorner.BottomRight, -1.0, 0.0).width, 1e-9)
    }

    @Test
    fun `a locked shape follows the larger drag and fits inside`() {
        val square = CropRect(0.25, 0.25, 0.5, 0.5)
        assertCrop(CropRect(0.25, 0.25, 0.7, 0.7), resizeCrop(square, CropCorner.BottomRight, 0.2, 0.05, shape = 1.0))
        // Growing past the source edge stops at it, still square.
        assertCrop(CropRect(0.25, 0.25, 0.75, 0.75), resizeCrop(square, CropCorner.BottomRight, 0.6, 0.0, shape = 1.0))
    }

    @Test
    fun `a crop of the whole source is no crop`() {
        assertNull(storedCrop(FullCrop))
        assertNull(storedCrop(null))
        assertEquals(CropRect(0.0, 0.0, 0.9, 1.0), storedCrop(CropRect(0.0, 0.0, 0.9, 1.0)))
    }
}
