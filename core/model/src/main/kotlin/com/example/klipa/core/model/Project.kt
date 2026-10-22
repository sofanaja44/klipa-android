package com.example.klipa.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Ported from ReaCut src/core/types.ts. Property names and enum values match
// ReaCut's JSON so project files keep the same shape (`version: 1`).
// Times are in seconds; clip times are on the project timeline unless noted.

@Serializable
enum class MediaKind {
    @SerialName("video") Video,
    @SerialName("audio") Audio,
    @SerialName("image") Image,
}

@Serializable
enum class TrackKind {
    @SerialName("video") Video,
    @SerialName("audio") Audio,
    @SerialName("image") Image,
    @SerialName("text") Text,
}

/** The track kind a new track gets when it is created to hold [this] media. */
val MediaKind.trackKind: TrackKind
    get() = when (this) {
        MediaKind.Video -> TrackKind.Video
        MediaKind.Audio -> TrackKind.Audio
        MediaKind.Image -> TrackKind.Image
    }

/** Video tracks also accept stills; every other track only accepts its own kind. */
fun TrackKind.accepts(media: MediaKind): Boolean =
    media.trackKind == this || (this == TrackKind.Video && media == MediaKind.Image)

@Serializable
data class MediaAsset(
    val id: String,
    val name: String,
    val path: String,
    val url: String,
    val kind: MediaKind,
    val duration: Double,
    val width: Int,
    val height: Int,
    val fps: Double,
    val hasAudio: Boolean,
    val thumbnail: String? = null,
    val waveform: List<Double>? = null,
    val missing: Boolean? = null,
    val previewUrl: String? = null,
    val previewError: String? = null,
)

@Serializable
data class Transform(
    val x: Double = 0.0,
    val y: Double = 0.0,
    val scale: Double = 1.0,
    val rotation: Double = 0.0,
    val opacity: Double = 1.0,
    val flipX: Boolean = false,
    val flipY: Boolean = false,
)

@Serializable
data class Adjustments(
    val brightness: Double = 0.0,
    val contrast: Double = 1.0,
    val saturation: Double = 1.0,
    val temperature: Double = 0.0,
    val blur: Double = 0.0,
)

@Serializable
enum class TextAlign {
    @SerialName("left") Left,
    @SerialName("center") Center,
    @SerialName("right") Right,
}

@Serializable
data class TextStyle(
    val text: String = "Enter text",
    val fontSize: Double = 72.0,
    val fontFamily: String = "sans-serif",
    val color: String = "#ffffff",
    val background: String = "transparent",
    val align: TextAlign = TextAlign.Center,
    val bold: Boolean = true,
    val stroke: String = "#000000",
    val strokeWidth: Double = 2.0,
)

/** Animatable clip properties, in the order ReaCut evaluates and slices them. */
@Serializable
enum class KeyframeProperty {
    @SerialName("x") X,
    @SerialName("y") Y,
    @SerialName("scale") Scale,
    @SerialName("rotation") Rotation,
    @SerialName("opacity") Opacity,
    @SerialName("volume") Volume,
}

@Serializable
enum class Easing {
    @SerialName("linear") Linear,
    @SerialName("easeInOut") EaseInOut,
}

/**
 * The part of the smoothstep curve an `easeInOut` segment covers, as fractions of the
 * full curve. Splits and trims narrow it so a cut segment keeps its original shape.
 */
@Serializable
data class EasingWindow(val start: Double, val end: Double) {
    companion object {
        val Full = EasingWindow(0.0, 1.0)
    }
}

/** Keyframe [time] is clip-local, in timeline seconds from the clip's start. */
@Serializable
data class Keyframe(
    val id: String,
    val time: Double,
    val property: KeyframeProperty,
    val value: Double,
    val easing: Easing,
    val easingWindow: EasingWindow? = null,
)

/**
 * A fade envelope in clip-local timeline seconds. After a cut, [offset] places the
 * piece inside the envelope of the clip it was cut from, so the fade keeps its phase.
 */
@Serializable
data class FadeWindow(
    val offset: Double,
    val duration: Double,
    val fadeIn: Double,
    val fadeOut: Double,
)

/** Normalized source-space crop; all values are fractions of the source frame. */
@Serializable
data class CropRect(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
)

@Serializable
data class ChromaKey(
    val enabled: Boolean,
    val color: String,
    val similarity: Double,
    val blend: Double,
)

@Serializable
enum class MaskShape {
    @SerialName("rectangle") Rectangle,
    @SerialName("ellipse") Ellipse,
}

@Serializable
data class ShapeMask(
    val shape: MaskShape,
    val width: Double,
    val height: Double,
    val feather: Double,
    val invert: Boolean,
)

/**
 * The atomic unit of the editor: one placement of a source range (or a text layer) on a
 * track, with everything needed to render it.
 *
 * [sourceIn] is in source seconds; the clip plays `duration * speed` seconds of source.
 * [linkId] is a persistent editing association (e.g. a video and its detached audio);
 * rendering still uses each clip's own timing.
 */
@Serializable
data class Clip(
    val id: String,
    val linkId: String? = null,
    val trackId: String,
    val assetId: String? = null,
    val name: String,
    val start: Double,
    val duration: Double,
    val sourceIn: Double = 0.0,
    val speed: Double = 1.0,
    val volume: Double = 1.0,
    val muted: Boolean = false,
    val transform: Transform = Transform(),
    val adjustments: Adjustments = Adjustments(),
    val text: TextStyle? = null,
    val fadeIn: Double = 0.0,
    val fadeOut: Double = 0.0,
    val audioFadeIn: Double = 0.0,
    val audioFadeOut: Double = 0.0,
    val videoFadeWindow: FadeWindow? = null,
    val audioFadeWindow: FadeWindow? = null,
    val keyframes: List<Keyframe> = emptyList(),
    val crop: CropRect? = null,
    val chromaKey: ChromaKey? = null,
    val mask: ShapeMask? = null,
)

/** Timeline time at which the clip stops playing. */
val Clip.end: Double get() = start + duration

@Serializable
data class Track(
    val id: String,
    val name: String,
    val kind: TrackKind,
    val muted: Boolean = false,
    val hidden: Boolean = false,
    val locked: Boolean = false,
)

@Serializable
data class Project(
    val version: Int = 1,
    val id: String,
    val name: String,
    val width: Int = 1920,
    val height: Int = 1080,
    val fps: Int = 30,
    val background: String = "#101015",
    val assets: List<MediaAsset> = emptyList(),
    val tracks: List<Track> = emptyList(),
    val clips: List<Clip> = emptyList(),
    val createdAt: String,
    val updatedAt: String,
)

/** Supported project frame rates. */
val ProjectFrameRates: Set<Int> = setOf(24, 25, 30, 50, 60)
