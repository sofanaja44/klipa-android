package com.example.klipa.core.model

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** A fresh random identifier for projects, tracks, clips, assets, keyframes and links. */
fun newId(): String = UUID.randomUUID().toString()

/** A new 1920×1080 @ 30 fps project with an empty text, video and audio track. */
fun createProject(name: String = "Untitled project", now: Instant = Instant.now()): Project {
    // Millisecond precision matches ReaCut's `toISOString()` timestamps.
    val timestamp = now.truncatedTo(ChronoUnit.MILLIS).toString()
    return Project(
        id = newId(),
        name = name,
        tracks = listOf(
            createTrack(TrackKind.Text, "Text"),
            createTrack(TrackKind.Video, "Video 1"),
            createTrack(TrackKind.Audio, "Audio 1"),
        ),
        createdAt = timestamp,
        updatedAt = timestamp,
    )
}

fun createTrack(kind: TrackKind, name: String? = null): Track = Track(
    id = newId(),
    kind = kind,
    name = name ?: when (kind) {
        TrackKind.Video -> "Video"
        TrackKind.Image -> "Image"
        TrackKind.Audio -> "Audio"
        TrackKind.Text -> "Text"
    },
)

/**
 * A clip for [asset] at [start] (snapped to a frame), spanning the whole source. Stills
 * and text get 5 seconds. Passing no asset creates a text clip with the default style.
 */
fun createClip(asset: MediaAsset?, trackId: String, start: Double, fps: Int): Clip = Clip(
    id = newId(),
    trackId = trackId,
    assetId = asset?.id,
    name = asset?.name ?: "Text",
    start = snapToFrame(maxOf(0.0, start), fps),
    duration = if (asset == null || asset.kind == MediaKind.Image) 5.0 else maxOf(1.0 / fps, asset.duration),
    text = if (asset == null) TextStyle() else null,
)
