package com.example.klipa.core.model

// Keyframe and fade evaluation, ported from ReaCut src/core/model.ts. Preview and export
// both read clip state through these so what plays matches what renders.

enum class FadeChannel { Video, Audio }

/**
 * The fade envelope for [channel]. Clips that were never cut have no stored window and
 * use their own fade lengths over their full duration.
 */
fun Clip.fadeWindow(channel: FadeChannel): FadeWindow = when (channel) {
    FadeChannel.Video -> videoFadeWindow ?: FadeWindow(0.0, duration, fadeIn, fadeOut)
    FadeChannel.Audio -> audioFadeWindow ?: FadeWindow(0.0, duration, audioFadeIn, audioFadeOut)
}

/** Fade gain in 0..1 at clip-local [time]. */
fun evaluateFade(clip: Clip, time: Double, channel: FadeChannel): Double {
    val window = clip.fadeWindow(channel)
    val local = window.offset + time
    val incoming = if (window.fadeIn > 0) maxOf(0.0, minOf(1.0, local / window.fadeIn)) else 1.0
    val outgoing =
        if (window.fadeOut > 0) maxOf(0.0, minOf(1.0, (window.duration - local) / window.fadeOut)) else 1.0
    return incoming * outgoing
}

/**
 * Keyframes of one property sorted by time. Where several share a timestamp only the
 * last one is kept: each destination key owns the interpolation for the interval ending
 * at it.
 */
fun orderedKeys(clip: Clip, property: KeyframeProperty): List<Keyframe> {
    val keys = clip.keyframes.filter { it.property == property }.sortedBy { it.time }
    return keys.filterIndexed { i, key -> i == keys.lastIndex || keys[i + 1].time != key.time }
}

/** The clip with every keyframed property replaced by its value at clip-local [time]. */
fun evaluateKeyframes(clip: Clip, time: Double): Clip {
    var result = clip
    for (property in KeyframeProperty.entries) {
        val value = evaluateKeyframe(clip, property, time) ?: continue
        result = result.withValue(property, value)
    }
    return result
}

/** The value of [property] at clip-local [time], or null when it has no keyframes. */
fun evaluateKeyframe(clip: Clip, property: KeyframeProperty, time: Double): Double? {
    val keys = orderedKeys(clip, property)
    if (keys.isEmpty()) return null
    if (time >= keys.last().time) return keys.last().value
    for (i in 0 until keys.lastIndex) {
        val from = keys[i]
        val to = keys[i + 1]
        if (time >= from.time && time <= to.time) {
            val span = to.time - from.time
            var ratio = (time - from.time) / (if (span == 0.0 || span.isNaN()) 1.0 else span)
            if (to.easing == Easing.EaseInOut) ratio = easeInOut(ratio, to.easingWindow ?: EasingWindow.Full)
            return from.value + (to.value - from.value) * ratio
        }
    }
    return keys.first().value
}

/**
 * Smoothstep restricted to [window] and renormalized to 0..1. The cubic's coefficients
 * are computed analytically so very narrow windows stay accurate.
 */
fun easeInOut(ratio: Double, window: EasingWindow): Double {
    val delta = window.end - window.start
    val a = 6 * window.start * (1 - window.start)
    val b = 3 * delta * (1 - 2 * window.start)
    val c = -2 * delta * delta
    return ratio * (a + b * ratio + c * ratio * ratio) / (a + b + c)
}

/** The current static value of [property], ignoring keyframes. */
fun Clip.valueOf(property: KeyframeProperty): Double = when (property) {
    KeyframeProperty.X -> transform.x
    KeyframeProperty.Y -> transform.y
    KeyframeProperty.Scale -> transform.scale
    KeyframeProperty.Rotation -> transform.rotation
    KeyframeProperty.Opacity -> transform.opacity
    KeyframeProperty.Volume -> volume
}

fun Clip.withValue(property: KeyframeProperty, value: Double): Clip = when (property) {
    KeyframeProperty.X -> copy(transform = transform.copy(x = value))
    KeyframeProperty.Y -> copy(transform = transform.copy(y = value))
    KeyframeProperty.Scale -> copy(transform = transform.copy(scale = value))
    KeyframeProperty.Rotation -> copy(transform = transform.copy(rotation = value))
    KeyframeProperty.Opacity -> copy(transform = transform.copy(opacity = value))
    KeyframeProperty.Volume -> copy(volume = value)
}
