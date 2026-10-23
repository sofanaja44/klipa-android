package com.example.klipa.core.edit

import com.example.klipa.core.model.MediaKind
import com.example.klipa.core.model.TrackKind
import com.example.klipa.core.model.createClip
import com.example.klipa.core.model.createProject
import com.example.klipa.core.model.testAsset
import com.example.klipa.core.model.validateProject
import kotlin.test.Test
import kotlin.test.assertEquals

class OverlayTest {
    private val still = testAsset.copy(id = "still", path = "/still.jpg", url = "file:///still.jpg", kind = MediaKind.Image, duration = 0.0, hasAudio = false)

    /** A 10 s shot on the main video track, playhead at 2 s. */
    private fun editor(): EditorState {
        val base = createProject().copy(assets = listOf(testAsset, still))
        val project = base.copy(clips = listOf(createClip(testAsset, base.tracks[1].id, 0.0, base.fps).copy(duration = 10.0)))
        return EditorState().setProject(project).seek(2.0)
    }

    @Test
    fun `the first overlay gets its own track above the main video, at half size`() {
        val s = editor().placeOverlays(listOf(testAsset.id to SourceRange(0.0, 3.0)))
        val tracks = s.project.tracks
        assertEquals(listOf(TrackKind.Text, TrackKind.Video, TrackKind.Video, TrackKind.Audio), tracks.map { it.kind })
        assertEquals("Video 2", tracks[1].name)
        val overlay = s.project.clips.single { it.trackId == tracks[1].id }
        assertEquals(listOf(2.0, 3.0, OVERLAY_SCALE), listOf(overlay.start, overlay.duration, overlay.transform.scale))
        assertEquals(listOf(overlay.id), s.selectedIds)
        // The main video is untouched: overlays never ripple.
        assertEquals(10.0, s.project.clips.single { it.trackId == tracks[2].id }.duration)
        assertEquals(1, s.past.size)
        validateProject(s.project)
    }

    @Test
    fun `overlays share a track where it is free and stack where it is not`() {
        val first = editor().placeOverlays(listOf(testAsset.id to SourceRange(0.0, 3.0)))
        // Later on, the same overlay track is free again.
        val later = first.seek(6.0).placeOverlays(listOf(still.id to SourceRange(0.0, 2.0)))
        assertEquals(4, later.project.tracks.size)
        // Over the first overlay, a new track goes on top of the video tracks.
        val stacked = first.seek(3.0).placeOverlays(listOf(still.id to SourceRange(0.0, 2.0)))
        assertEquals(listOf("Video 3", "Video 2", "Video 1"), stacked.project.tracks.filter { it.kind == TrackKind.Video }.map { it.name })
    }

    @Test
    fun `several overlays play one after another`() {
        val s = editor().placeOverlays(listOf(still.id to SourceRange(0.0, 2.0), testAsset.id to SourceRange(0.0, 4.0)))
        val overlays = s.project.clips.filter { it.trackId == s.project.tracks[1].id }.sortedBy { it.start }
        assertEquals(listOf(2.0 to 2.0, 4.0 to 4.0), overlays.map { it.start to it.duration })
        assertEquals(listOf(overlays.last().id), s.selectedIds)
    }
}
