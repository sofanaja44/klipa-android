package com.example.klipa

import com.example.klipa.core.edit.detachLinkedAudio
import com.example.klipa.core.model.Easing
import com.example.klipa.core.model.Keyframe
import com.example.klipa.core.model.KeyframeProperty
import com.example.klipa.core.model.MediaAsset
import com.example.klipa.core.model.MediaKind
import com.example.klipa.core.model.Project
import com.example.klipa.core.model.TextStyle
import com.example.klipa.core.model.TrackKind
import com.example.klipa.core.model.Transform
import com.example.klipa.core.model.createClip
import com.example.klipa.core.model.createProject
import com.example.klipa.core.model.createTrack
import kotlin.math.sin

/** A short edit: two shots with extracted sound, a title, a music bed. */
internal fun sampleProject(): Project {
    var project = createProject("Weekend in Lisbon")
    val video = project.tracks[1].id
    val harbor = asset("harbor", "Harbor at dusk.mp4", MediaKind.Video, 14.0)
    val tram = asset("tram", "Tram 28.mp4", MediaKind.Video, 9.0)
    val music = asset("music", "Saudade (instrumental).m4a", MediaKind.Audio, 60.0)
        .copy(waveform = List(400) { i -> (0.35 + 0.3 * sin(i / 3.0) * sin(i / 17.0)).coerceIn(0.05, 1.0) })
    project = project.copy(assets = listOf(harbor, tram, music))
    val first = createClip(harbor, video, 0.0, 30).copy(sourceIn = 2.0, duration = 9.0, fadeIn = 1.0)
    val second = createClip(tram, video, 9.0, 30).copy(duration = 6.0, speed = 1.5)
    project = project.copy(clips = listOf(first, second))
    project = detachLinkedAudio(project, first.id).project
    val bed = createClip(music, project.tracks[2].id, 0.0, 30).copy(duration = 15.0, audioFadeOut = 3.0, muted = false)
    val musicTrack = createTrack(TrackKind.Audio, "Music")
    val title = createClip(null, project.tracks[0].id, 1.0, 30).copy(
        name = "Title",
        duration = 6.0,
        text = TextStyle(text = "Lisbon", fontSize = 160.0, strokeWidth = 0.0),
        transform = Transform(y = 280.0),
        keyframes = listOf(
            Keyframe("k1", 0.0, KeyframeProperty.Opacity, 0.0, Easing.Linear),
            Keyframe("k2", 1.0, KeyframeProperty.Opacity, 1.0, Easing.EaseInOut),
        ),
    )
    return project.copy(
        tracks = project.tracks + musicTrack,
        clips = project.clips + bed.copy(trackId = musicTrack.id) + title,
    )
}

internal fun asset(id: String, name: String, kind: MediaKind, duration: Double) = MediaAsset(
    id = id,
    name = name,
    path = "content://media/$id",
    url = "content://media/$id",
    kind = kind,
    duration = duration,
    width = if (kind == MediaKind.Video) 1920 else 0,
    height = if (kind == MediaKind.Video) 1080 else 0,
    fps = if (kind == MediaKind.Video) 30.0 else 0.0,
    hasAudio = true,
)
