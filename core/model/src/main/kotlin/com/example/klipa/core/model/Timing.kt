package com.example.klipa.core.model

import kotlin.math.abs

/** Rounds [time] to the nearest frame boundary. Every edit snaps its times with this. */
fun snapToFrame(time: Double, fps: Int): Double = roundHalfUp(time * fps) / fps

/** End of the last clip, or 0 for an empty project. */
fun projectDuration(project: Project): Double =
    project.clips.fold(0.0) { end, clip -> maxOf(end, clip.end) }

/** `HH:MM:SS:FF` timecode for [time] at [fps]. */
fun formatTime(time: Double, fps: Int = 30): String {
    val frames = maxOf(0L, Math.round(time * fps))
    val seconds = frames / fps
    return listOf(seconds / 3600, seconds / 60 % 60, seconds % 60, frames % fps)
        .joinToString(":") { it.toString().padStart(2, '0') }
}

/**
 * JavaScript `Math.round`: nearest integer, ties toward positive infinity. Kept exact so
 * frame snapping produces the same values as ReaCut. Kotlin's `round` ties to even.
 */
internal fun roundHalfUp(value: Double): Double =
    if (!value.isFinite() || abs(value) >= TWO_POW_52) value else Math.round(value).toDouble()

// Doubles at or above 2^52 have no fractional part.
private const val TWO_POW_52 = 4503599627370496.0
