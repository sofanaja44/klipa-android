package com.example.klipa.core.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.graphics.createBitmap
import com.example.klipa.core.model.TextAlign
import com.example.klipa.core.model.TextStyle
import kotlin.math.ceil
import kotlin.math.max

// Text layers are rendered to a bitmap, as ReaCut does (electron/export.ts renderText),
// and the same bitmap code serves the program monitor and export, so titles look the
// same in both. Lines break only at newlines; the layer is centered on the clip position.

/** Largest text bitmap side, and area, ReaCut accepts. */
private const val MAX_SIDE = 8192
private const val MAX_PIXELS = 32_000_000

/**
 * Renders [style] at [scale] bitmap pixels per project pixel: the text with its stroke on
 * the background, padded by a quarter of the font size plus the stroke. Returns null when
 * there is nothing visible to draw or the bitmap would be too large.
 */
fun renderText(style: TextStyle, scale: Float = 1f): Bitmap? {
    val fontSize = (style.fontSize * scale).toFloat()
    if (fontSize < 1f || style.text.isBlank()) return null
    val strokeWidth = (style.strokeWidth * scale).toFloat()
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        textSize = fontSize
        typeface = Typeface.create(style.fontFamily.ifBlank { "sans-serif" }, if (style.bold) Typeface.BOLD else Typeface.NORMAL)
        textAlign = when (style.align) {
            TextAlign.Left -> Paint.Align.LEFT
            TextAlign.Center -> Paint.Align.CENTER
            TextAlign.Right -> Paint.Align.RIGHT
        }
    }
    val lines = style.text.split("\n")
    val padding = ceil(fontSize * 0.25f + strokeWidth)
    val lineHeight = fontSize * 1.3f
    val width = max(2, ceil(max(lines.maxOf { paint.measureText(it) }, 1f) + padding * 2).toInt())
    val height = max(2, ceil(lineHeight * lines.size + padding * 2).toInt())
    if (width > MAX_SIDE || height > MAX_SIDE || width.toLong() * height > MAX_PIXELS) return null

    val bitmap = createBitmap(width, height)
    val canvas = Canvas(bitmap)
    parseArgb(style.background)?.let { if (it ushr 24 != 0) canvas.drawColor(it) }
    val x = when (style.align) {
        TextAlign.Left -> padding
        TextAlign.Center -> width / 2f
        TextAlign.Right -> width - padding
    }
    // Lines are centered vertically in their 1.3 em slot, like canvas textBaseline "middle".
    val metrics = paint.fontMetrics
    val middleToBaseline = -(metrics.ascent + metrics.descent) / 2
    val stroke = parseArgb(style.stroke)?.takeIf { strokeWidth > 0 && it ushr 24 != 0 }
    val fill = parseArgb(style.color) ?: 0xFFFFFFFF.toInt()
    lines.forEachIndexed { index, line ->
        val y = padding + lineHeight * (index + 0.5f) + middleToBaseline
        if (stroke != null) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = strokeWidth * 2
            paint.strokeJoin = Paint.Join.ROUND
            paint.color = stroke
            canvas.drawText(line, x, y, paint)
        }
        paint.style = Paint.Style.FILL
        paint.color = fill
        canvas.drawText(line, x, y, paint)
    }
    return bitmap
}

/** Parses the colors a project may contain, `#rrggbb`, `#rrggbbaa` and a few names, to ARGB. */
fun parseArgb(value: String): Int? {
    when (value.lowercase()) {
        "black" -> return 0xFF000000.toInt()
        "white" -> return 0xFFFFFFFF.toInt()
        "red" -> return 0xFFFF0000.toInt()
        "green" -> return 0xFF008000.toInt()
        "blue" -> return 0xFF0000FF.toInt()
        "transparent" -> return 0
    }
    if (!value.startsWith("#") || (value.length != 7 && value.length != 9)) return null
    val rgb = value.substring(1, 7).toLongOrNull(16) ?: return null
    val alpha = if (value.length == 9) value.substring(7, 9).toLongOrNull(16) ?: return null else 0xFF
    return ((alpha shl 24) or rgb).toInt()
}
