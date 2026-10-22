package com.example.klipa.core.edit

import com.example.klipa.core.model.Clip
import com.example.klipa.core.model.Easing
import com.example.klipa.core.model.EasingWindow
import com.example.klipa.core.model.FadeChannel
import com.example.klipa.core.model.Keyframe
import com.example.klipa.core.model.KeyframeProperty
import com.example.klipa.core.model.MediaKind
import com.example.klipa.core.model.Project
import com.example.klipa.core.model.evaluateKeyframe
import com.example.klipa.core.model.fadeWindow
import com.example.klipa.core.model.newId
import com.example.klipa.core.model.orderedKeys
import com.example.klipa.core.model.snapToFrame

// Single-clip edits, ported from ReaCut src/core/model.ts. Each returns a new project,
// or the same instance when the edit does not apply (locked track, bad time, no-op).

enum class TrimEdge { Start, End }

/**
 * Splits a clip at [time] (snapped to a frame) into two clips that together play exactly
 * what the original did, including keyframe curves and fade envelopes. Both sides must
 * be at least one frame long.
 */
fun splitClip(project: Project, clipId: String, time: Double): Project {
    val clip = project.clips.find { it.id == clipId }
    if (!time.isFinite() || clip == null || project.isTrackLocked(clip)) return project
    val at = snapToFrame(time, project.fps)
    val leftDuration = at - clip.start
    val frame = 1.0 / project.fps
    if (leftDuration < frame - 1e-9 || clip.duration - leftDuration < frame - 1e-9) return project
    val right = clip.copy(
        id = newId(),
        start = at,
        duration = clip.duration - leftDuration,
        sourceIn = clip.sourceIn + leftDuration * clip.speed,
        fadeIn = 0.0,
        audioFadeIn = 0.0,
        keyframes = sliceKeyframes(clip, leftDuration, clip.duration),
    )
    val left = clip.copy(
        duration = leftDuration,
        fadeOut = 0.0,
        audioFadeOut = 0.0,
        keyframes = sliceKeyframes(clip, 0.0, leftDuration),
    )
    val pieces = listOf(
        preserveFades(clip, left, 0.0).clampFades(),
        preserveFades(clip, right, leftDuration).clampFades(),
    )
    return project.copy(clips = project.clips.flatMap { if (it.id == clipId) pieces else listOf(it) })
}

/**
 * Moves one edge of a clip to [time] (snapped to a frame), clamped so the clip never
 * reads before the start or past the end of its source. Stills can extend freely.
 */
fun trimClip(project: Project, clipId: String, edge: TrimEdge, time: Double): Project {
    val original = project.clips.find { it.id == clipId }
    if (!time.isFinite() || original == null || project.isTrackLocked(original)) return project
    val asset = project.assets.find { it.id == original.assetId }
    val bounded = asset != null && asset.kind != MediaKind.Image
    val min = 1.0 / project.fps
    val at = snapToFrame(time, project.fps)
    var clip = when (edge) {
        TrimEdge.Start -> {
            val lower = if (bounded) maxOf(0.0, original.start - original.sourceIn / original.speed) else 0.0
            val next = maxOf(lower, minOf(at, original.start + original.duration - min))
            val delta = next - original.start
            original.copy(
                sourceIn = maxOf(0.0, original.sourceIn + delta * original.speed),
                duration = original.duration - delta,
                start = next,
                keyframes = sliceKeyframes(original, delta, original.duration),
            )
        }
        TrimEdge.End -> {
            val max = if (bounded) {
                original.start + (asset.duration - original.sourceIn) / original.speed
            } else {
                Double.POSITIVE_INFINITY
            }
            val duration = maxOf(min, minOf(at, max) - original.start)
            original.copy(duration = duration, keyframes = sliceKeyframes(original, 0.0, duration))
        }
    }
    clip = preserveFades(original, clip, clip.start - original.start).clampFades()
    if (clip.start == original.start && clip.duration == original.duration) return project
    return project.copy(clips = project.clips.map { if (it.id == clipId) clip else it })
}

/**
 * Changes playback speed (0.1–8) while keeping the same source range: the clip's
 * duration, keyframe times and fades all scale by `oldSpeed / newSpeed`.
 */
fun changeClipSpeed(project: Project, clipId: String, speed: Double): Project {
    val clip = project.clips.find { it.id == clipId }
    if (clip == null || project.isTrackLocked(clip) || !speed.isFinite() ||
        speed < 0.1 || speed > 8 || speed == clip.speed
    ) return project
    val ratio = clip.speed / speed
    val next = clip.copy(
        speed = speed,
        duration = clip.duration * ratio,
        keyframes = clip.keyframes.map { it.copy(time = it.time * ratio) },
        fadeIn = clip.fadeIn * ratio,
        fadeOut = clip.fadeOut * ratio,
        audioFadeIn = clip.audioFadeIn * ratio,
        audioFadeOut = clip.audioFadeOut * ratio,
        videoFadeWindow = clip.videoFadeWindow?.let {
            it.copy(offset = it.offset * ratio, duration = it.duration * ratio, fadeIn = it.fadeIn * ratio, fadeOut = it.fadeOut * ratio)
        },
        audioFadeWindow = clip.audioFadeWindow?.let {
            it.copy(offset = it.offset * ratio, duration = it.duration * ratio, fadeIn = it.fadeIn * ratio, fadeOut = it.fadeOut * ratio)
        },
    )
    return project.copy(clips = project.clips.map { if (it.id == clipId) next else it })
}

// Unlike the linked edits, a missing track does not count as locked here (as in ReaCut).
private fun Project.isTrackLocked(clip: Clip): Boolean = tracks.find { it.id == clip.trackId }?.locked == true

/** Stores the original fade envelope on a cut piece, shifted by where the piece begins. */
private fun preserveFades(original: Clip, next: Clip, offset: Double): Clip {
    var result = next
    for (channel in FadeChannel.entries) {
        val window = original.fadeWindow(channel)
        if (window.fadeIn > 0 || window.fadeOut > 0) {
            val shifted = window.copy(offset = window.offset + offset)
            result = when (channel) {
                FadeChannel.Video -> result.copy(videoFadeWindow = shifted)
                FadeChannel.Audio -> result.copy(audioFadeWindow = shifted)
            }
        }
    }
    return result
}

private fun Clip.clampFades(): Clip = copy(
    fadeIn = minOf(fadeIn, duration),
    fadeOut = minOf(fadeOut, duration),
    audioFadeIn = minOf(audioFadeIn, duration),
    audioFadeOut = minOf(audioFadeOut, duration),
)

/**
 * Keyframes for the clip-local interval [from]..[to], rebased to start at 0. The curve is
 * remapped rather than resampled: boundary keys take the evaluated value, and a cut
 * `easeInOut` segment gets a narrowed [EasingWindow] so its shape is unchanged.
 */
internal fun sliceKeyframes(clip: Clip, from: Double, to: Double): List<Keyframe> {
    val result = mutableListOf<Keyframe>()
    for (property in KeyframeProperty.entries) {
        val keys = orderedKeys(clip, property)
        if (keys.isEmpty()) continue
        // `+ 0.0` folds -0.0 into 0.0 so distinct() dedupes like a JavaScript Set.
        val times = (listOf(from) + keys.filter { it.time > from && it.time < to }.map { it.time } + to)
            .map { it + 0.0 }
            .distinct()
            .sorted()
        for ((i, at) in times.withIndex()) {
            var easing = Easing.Linear
            var easingWindow: EasingWindow? = null
            if (i > 0) {
                val previous = times[i - 1]
                val midpoint = (previous + at) / 2
                val rightIndex = keys.indexOfFirst { it.time > midpoint }
                if (rightIndex > 0) {
                    val left = keys[rightIndex - 1]
                    val right = keys[rightIndex]
                    easing = right.easing
                    if (right.easing == Easing.EaseInOut) {
                        val window = right.easingWindow ?: EasingWindow.Full
                        val span = right.time - left.time
                        val start = window.start + (window.end - window.start) * (previous - left.time) / span
                        val end = window.start + (window.end - window.start) * (at - left.time) / span
                        if (start > 0 || end < 1) easingWindow = EasingWindow(maxOf(0.0, start), minOf(1.0, end))
                    }
                }
            }
            result += Keyframe(
                id = newId(),
                time = at - from,
                property = property,
                value = evaluateKeyframe(clip, property, at)!!,
                easing = easing,
                easingWindow = easingWindow,
            )
        }
    }
    return result
}
