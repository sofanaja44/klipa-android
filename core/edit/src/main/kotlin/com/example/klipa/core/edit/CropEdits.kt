package com.example.klipa.core.edit

import com.example.klipa.core.model.CropRect

// Crop rectangles are fractions of the (oriented) source frame. These helpers are what
// the crop tool does with them; centeredCrop and updateCropField are ported from ReaCut
// (src/components/VisualTools.tsx), the drag helpers are Klipa's.

/** The whole source: no crop. */
val FullCrop = CropRect(x = 0.0, y = 0.0, width = 1.0, height = 1.0)

/** Smallest crop side a drag can leave, as a fraction of the source. */
const val MIN_CROP_SIDE = 0.05

/** Largest centered crop of a [sourceWidth] × [sourceHeight] frame whose pixels have the shape [ratio] (width / height). */
fun centeredCrop(sourceWidth: Double, sourceHeight: Double, ratio: Double): CropRect {
    if (!listOf(sourceWidth, sourceHeight, ratio).all { it.isFinite() && it > 0 }) return FullCrop
    val aspect = sourceWidth / sourceHeight
    val width = if (aspect > ratio) ratio / aspect else 1.0
    val height = if (aspect < ratio) aspect / ratio else 1.0
    return CropRect(x = (1 - width) / 2, y = (1 - height) / 2, width = width, height = height)
}

/** The crop's fields ReaCut edits one at a time. */
enum class CropField { X, Y, Width, Height }

/** Sets one field, keeping the crop inside the source and at least 1% in each direction. */
fun updateCropField(crop: CropRect, field: CropField, value: Double): CropRect {
    if (!value.isFinite()) return crop
    return when (field) {
        CropField.X -> crop.copy(x = value.coerceIn(0.0, 1 - crop.width))
        CropField.Y -> crop.copy(y = value.coerceIn(0.0, 1 - crop.height))
        CropField.Width -> crop.copy(width = value.coerceIn(0.01, 1 - crop.x))
        CropField.Height -> crop.copy(height = value.coerceIn(0.01, 1 - crop.y))
    }
}

/** Moves a crop by [dx], [dy] (fractions of the source), keeping its size and staying inside. */
fun moveCrop(crop: CropRect, dx: Double, dy: Double): CropRect = crop.copy(
    x = (crop.x + dx).coerceIn(0.0, 1 - crop.width),
    y = (crop.y + dy).coerceIn(0.0, 1 - crop.height),
)

/** A corner of the crop rectangle. */
enum class CropCorner { TopLeft, TopRight, BottomRight, BottomLeft }

/**
 * Drags [corner] by [dx], [dy] (fractions of the source); the opposite corner stays put.
 * With [shape], the crop keeps that width / height ratio in source fractions. The crop
 * stays inside the source and at least [MIN_CROP_SIDE] in each direction.
 */
fun resizeCrop(crop: CropRect, corner: CropCorner, dx: Double, dy: Double, shape: Double? = null): CropRect {
    val left = corner == CropCorner.TopLeft || corner == CropCorner.BottomLeft
    val top = corner == CropCorner.TopLeft || corner == CropCorner.TopRight
    // The fixed corner, and how far the dragged one may go in each direction.
    val anchorX = if (left) crop.x + crop.width else crop.x
    val anchorY = if (top) crop.y + crop.height else crop.y
    val maxWidth = if (left) anchorX else 1 - anchorX
    val maxHeight = if (top) anchorY else 1 - anchorY
    var width = (crop.width + if (left) -dx else dx).coerceIn(MIN_CROP_SIDE, maxWidth)
    var height = (crop.height + if (top) -dy else dy).coerceIn(MIN_CROP_SIDE, maxHeight)
    if (shape != null && shape > 0) {
        // Follow the larger of the two drags, then fit inside the source.
        if (width / shape >= height) height = width / shape else width = height * shape
        if (height > maxHeight) {
            height = maxHeight
            width = height * shape
        }
        if (width > maxWidth) {
            width = maxWidth
            height = width / shape
        }
    }
    return CropRect(
        x = if (left) anchorX - width else anchorX,
        y = if (top) anchorY - height else anchorY,
        width = width,
        height = height,
    )
}

/** The crop to store: none when it keeps the whole source, so no crop pass runs. */
fun storedCrop(crop: CropRect?): CropRect? =
    crop?.takeUnless { it.x <= 1e-6 && it.y <= 1e-6 && it.width >= 1 - 1e-6 && it.height >= 1 - 1e-6 }
