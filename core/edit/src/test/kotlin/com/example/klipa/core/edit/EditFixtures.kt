package com.example.klipa.core.edit

import com.example.klipa.core.model.MediaAsset
import com.example.klipa.core.model.MediaKind
import com.example.klipa.core.model.Project
import com.example.klipa.core.model.createClip
import com.example.klipa.core.model.createProject
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// Fixtures from ReaCut tests/timeline-edits.test.ts.

val linkedSource = MediaAsset(
    id = "v",
    name = "Synthetic",
    path = "/synthetic.mp4",
    url = "file:///synthetic.mp4",
    kind = MediaKind.Video,
    duration = 20.0,
    width = 160,
    height = 90,
    fps = 30.0,
    hasAudio = true,
)

/** One 6 s video clip at 0 reading source 2–8 on the video track (tracks: text, video, audio). */
fun linkedFixture(): Project {
    val project = createProject("Linked rough cut")
    val clip = createClip(linkedSource, project.tracks[1].id, 0.0, project.fps).copy(duration = 6.0, sourceIn = 2.0)
    return project.copy(assets = listOf(linkedSource), clips = listOf(clip))
}

/** [linkedFixture] with its sound detached: clips[0] is the muted video, clips[1] the linked audio. */
fun linkedPair(): Project {
    val project = linkedFixture()
    return detachLinkedAudio(project, project.clips[0].id).project
}

fun Project.lockTrack(index: Int): Project =
    copy(tracks = tracks.mapIndexed { i, t -> if (i == index) t.copy(locked = true) else t })

fun assertRejected(reason: EditRejectedException.Reason, block: () -> Unit) {
    assertEquals(reason, assertFailsWith<EditRejectedException> { block() }.reason)
}
