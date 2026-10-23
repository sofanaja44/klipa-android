package com.example.klipa.core.media

import com.example.klipa.core.model.ChromaKey
import com.example.klipa.core.model.Clip
import com.example.klipa.core.model.MaskShape
import com.example.klipa.core.model.ShapeMask
import kotlin.math.ceil
import kotlin.math.log2

// A clip's look: everything ReaCut does to a clip's pixels before placing it. Chroma key
// and mask set alpha, the color adjustments change RGB, and blur softens the result.
// The look shader (res/raw/klipa_look_fragment_es2.glsl) renders the first three; blur is
// a separate two-pass effect.

/** The per-pixel part of a clip's look. [key] is only set when it is enabled. */
data class Look(
    val brightness: Double = 0.0,
    val contrast: Double = 1.0,
    val saturation: Double = 1.0,
    val temperature: Double = 0.0,
    val key: ChromaKey? = null,
    val mask: ShapeMask? = null,
) {
    /** True when the look leaves every pixel as it is. */
    val isNeutral: Boolean get() = this == Neutral

    /** True when the look makes parts of the frame transparent. */
    val cutsOut: Boolean get() = key != null || mask != null

    companion object {
        val Neutral = Look()

        fun of(clip: Clip): Look = Look(
            brightness = clip.adjustments.brightness,
            contrast = clip.adjustments.contrast,
            saturation = clip.adjustments.saturation,
            temperature = clip.adjustments.temperature,
            key = clip.chromaKey?.takeIf { it.enabled },
            mask = clip.mask,
        )
    }
}

/** Temperature ±1 shifts red and blue by 35 of 255 levels, as in ReaCut. */
internal const val TEMPERATURE_LEVELS = 35.0

/**
 * Which blur effect a clip needs: 0 for none, else one per doubling of the radius. A blur
 * effect is built for its bucket, so dragging the blur slider within one only redraws.
 */
fun blurBucket(blur: Double): Int = if (blur <= 0) 0 else 1 + ceil(log2(blur.coerceAtLeast(1.0))).toInt()

/** The largest blur, in project pixels, of [bucket]. */
internal fun bucketMaxBlur(bucket: Int): Double = if (bucket <= 1) 1.0 else Math.pow(2.0, (bucket - 1).toDouble())

/**
 * The look's color adjustments as a 4 × 5 color matrix (row-major, offsets in 0..255),
 * for drawing still frames; null when they change nothing. Brightness, contrast,
 * saturation and temperature are affine, so this matches the shader except where ReaCut
 * clamps between stages. Chroma key and mask are not included.
 */
fun Look.colorMatrix(): FloatArray? {
    if (copy(key = null, mask = null).isNeutral) return null
    val weights = doubleArrayOf(0.2126, 0.7152, 0.0722)
    val offset = contrast * (brightness * 255 - 127.5) + 127.5
    val tint = doubleArrayOf(temperature * TEMPERATURE_LEVELS, 0.0, -temperature * TEMPERATURE_LEVELS)
    val values = FloatArray(20)
    for (row in 0..2) {
        for (column in 0..2) {
            val saturated = weights[column] * (1 - saturation) + if (row == column) saturation else 0.0
            values[row * 5 + column] = (contrast * saturated).toFloat()
        }
        // Each saturation row sums to one, so the brightness offset passes through unchanged.
        values[row * 5 + 4] = (offset + tint[row]).toFloat()
    }
    values[18] = 1f
    return values
}

/**
 * One pixel through the look, as the shader computes it, for tests and for code that needs
 * the same answer on the CPU. Channels are 0..1; [x], [y] is the pixel center in a frame of
 * [width] × [height]. Returns straight RGBA.
 */
fun lookPixel(look: Look, rgba: FloatArray, x: Double, y: Double, width: Int, height: Int): FloatArray {
    var r = rgba[0].toDouble()
    var g = rgba[1].toDouble()
    var b = rgba[2].toDouble()
    var alpha = rgba[3].toDouble()
    look.key?.let { key ->
        val target = parseArgb(key.color) ?: 0
        val distance = Math.sqrt(sq(r - (target shr 16 and 0xFF) / 255.0) + sq(g - (target shr 8 and 0xFF) / 255.0) + sq(b - (target and 0xFF) / 255.0)) / Math.sqrt(3.0)
        alpha *= when {
            distance <= key.similarity -> 0.0
            key.blend > 0 -> ((distance - key.similarity) / key.blend).coerceIn(0.0, 1.0)
            else -> 1.0
        }
    }
    look.mask?.let { alpha *= maskCoverage(it, x, y, width, height) }
    fun stage(value: Double) = ((value + look.brightness).coerceIn(0.0, 1.0) - 0.5) * look.contrast + 0.5
    r = stage(r).coerceIn(0.0, 1.0)
    g = stage(g).coerceIn(0.0, 1.0)
    b = stage(b).coerceIn(0.0, 1.0)
    val luma = r * 0.2126 + g * 0.7152 + b * 0.0722
    r = (luma + (r - luma) * look.saturation).coerceIn(0.0, 1.0)
    g = (luma + (g - luma) * look.saturation).coerceIn(0.0, 1.0)
    b = (luma + (b - luma) * look.saturation).coerceIn(0.0, 1.0)
    val tint = look.temperature * TEMPERATURE_LEVELS / 255
    r = (r + tint).coerceIn(0.0, 1.0)
    b = (b - tint).coerceIn(0.0, 1.0)
    return floatArrayOf(r.toFloat(), g.toFloat(), b.toFloat(), alpha.toFloat())
}

/**
 * How much of the pixel centered at [x], [y] a mask keeps, 0..1: ReaCut's centered
 * rectangle distance field or elliptical radial distance, feathered across the edge.
 */
fun maskCoverage(mask: ShapeMask, x: Double, y: Double, width: Int, height: Int): Double {
    val rx = maxOf(1e-6, width * mask.width / 2)
    val ry = maxOf(1e-6, height * mask.height / 2)
    val dx = Math.abs(x - width / 2.0)
    val dy = Math.abs(y - height / 2.0)
    val distance = if (mask.shape == MaskShape.Ellipse) {
        (Math.hypot(dx / rx, dy / ry) - 1) * minOf(rx, ry)
    } else {
        val qx = dx - rx
        val qy = dy - ry
        Math.hypot(maxOf(qx, 0.0), maxOf(qy, 0.0)) + minOf(maxOf(qx, qy), 0.0)
    }
    val feather = mask.feather * minOf(width, height)
    val inside = if (feather > 0) (0.5 - distance / feather).coerceIn(0.0, 1.0) else if (distance <= 0) 1.0 else 0.0
    return if (mask.invert) 1 - inside else inside
}

private fun sq(value: Double) = value * value
