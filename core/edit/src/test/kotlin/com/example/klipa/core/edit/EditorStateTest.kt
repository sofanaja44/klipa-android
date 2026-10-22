package com.example.klipa.core.edit

import com.example.klipa.core.model.MediaAsset
import com.example.klipa.core.model.MediaKind
import com.example.klipa.core.model.Project
import com.example.klipa.core.model.TrackKind
import com.example.klipa.core.model.ProjectJson
import com.example.klipa.core.model.createClip
import com.example.klipa.core.model.createProject
import com.example.klipa.core.model.assertClose
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

// Ported from ReaCut tests/store.test.ts and the store cases of tests/timeline-edits.test.ts.
// Snapshot-isolation checks are dropped: EditorState and Project are immutable.

class EditorStateTest {
    private val asset = MediaAsset(
        id = "asset",
        name = "Video",
        path = "/tmp/video.mp4",
        url = "file:///tmp/video.mp4",
        kind = MediaKind.Video,
        duration = 20.0,
        width = 1920,
        height = 1080,
        fps = 30.0,
        hasAudio = true,
    )

    /** One 3 s clip at 0 on the video track (tracks: text, video, audio). */
    private fun fixture(): Project {
        val project = createProject()
        return project.copy(
            assets = listOf(asset),
            clips = listOf(createClip(asset, project.tracks[1].id, 0.0, project.fps).copy(duration = 3.0)),
        )
    }

    private fun editor(project: Project = fixture()) = EditorState().setProject(project)

    // editor transactions

    @Test
    fun `groups continuous inspector edits into one undo and ignores empty transactions`() {
        var s = editor()
        val c = s.project.clips[0]
        s = s.beginTransaction().endTransaction()
        assertEquals(0, s.past.size)
        s = s.beginTransaction()
            .updateClip(c.id) { it.copy(volume = 0.5) }
            .updateClip(c.id) { it.copy(volume = 0.2) }
            .endTransaction()
        assertEquals(1, s.past.size)
        assertEquals(0.2, s.project.clips[0].volume)
        s = s.undo()
        assertEquals(1.0, s.project.clips[0].volume)
        s = s.redo()
        assertEquals(0.2, s.project.clips[0].volume)
    }

    @Test
    fun `explicit fade editing resets only the edited channel's cut envelope`() {
        val p = fixture().let { p -> p.copy(clips = listOf(p.clips[0].copy(fadeIn = 2.0, audioFadeOut = 2.0))) }
        var s = editor(splitClip(p, p.clips[0].id, 1.0))
        val right = s.project.clips[1]
        assertNotNull(right.videoFadeWindow)
        assertNotNull(right.audioFadeWindow)
        s = s.updateClip(right.id) { it.copy(fadeIn = 0.25) }
        assertNull(s.project.clips[1].videoFadeWindow)
        assertNotNull(s.project.clips[1].audioFadeWindow)
        s = s.select(listOf(right.id)).updateSelected { it.copy(audioFadeOut = 0.0) }
        assertNull(s.project.clips[1].audioFadeWindow)
        s = s.undo()
        assertNotNull(s.project.clips[1].audioFadeWindow)
    }

    @Test
    fun `undo and redo restore snapshots and a new edit clears redo`() {
        var s = editor()
        val c = s.project.clips[0]
        s = s.updateClip(c.id) { it.copy(transform = it.transform.copy(x = 30.0)) }
        assertEquals(30.0, s.project.clips[0].transform.x)
        assertTrue(s.dirty)
        s = s.undo()
        assertEquals(0.0, s.project.clips[0].transform.x)
        s = s.redo()
        assertEquals(30.0, s.project.clips[0].transform.x)
        s = s.undo().updateProject { it.copy(name = "Branch") }
        assertEquals(0, s.future.size)
        assertSame(s, s.redo())
        val fresh = editor()
        assertSame(fresh, fresh.undo())
    }

    @Test
    fun `ignores edits on locked tracks and adds no undo entries for no-ops`() {
        var s = editor()
        val c = s.project.clips[0]
        s = s.updateTrack(c.trackId) { it.copy(locked = true) }
        val count = s.past.size
        s = s.select(listOf(c.id))
            .updateClip(c.id) { it.copy(speed = 2.0) }
            .updateSelected { it.copy(volume = 0.2) }
            .moveClips(listOf(c.id), 2.0)
            .trim(c.id, TrimEdge.End, 1.0)
            .split()
            .deleteSelected()
            .duplicate()
            .copySelection()
            .paste()
        assertEquals(count, s.past.size)
        assertEquals(1, s.project.clips.size)
        assertEquals(1.0, s.project.clips[0].speed)
    }

    @Test
    fun `explicit locked or incompatible track does not silently choose another`() {
        var s = editor()
        val p = s.project
        s = s.updateTrack(p.tracks[1].id) { it.copy(locked = true) }
        val before = s.past.size
        s = s.addAssetClip("asset", 0.0, p.tracks[1].id).addAssetClip("asset", 0.0, p.tracks[2].id)
        assertEquals(before, s.past.size)
        assertEquals(1, s.project.clips.size)
    }

    @Test
    fun `cannot move video into audio and preserves group relative offsets at origin`() {
        var s = editor()
        val p = s.project
        val first = p.clips[0]
        s = s.commit(p.copy(clips = listOf(first.copy(start = 2.0), first.copy(id = "second", start = 5.0))))
        s = s.moveClips(listOf(first.id, "second"), -10.0, p.tracks[2].id)
        assertEquals(listOf(0.0, 3.0), s.project.clips.map { it.start })
        assertEquals(listOf(first.trackId, first.trackId), s.project.clips.map { it.trackId })
    }

    @Test
    fun `copy and paste are independent of later edits and cleared on switching projects`() {
        var s = editor()
        val c = s.project.clips[0]
        s = s.select(listOf(c.id)).copySelection().updateClip(c.id) { it.copy(name = "Changed") }.seek(5.0).paste()
        assertEquals("Video", s.project.clips[1].name)
        assertEquals(5.0, s.project.clips[1].start)
        assertNotEquals(c.id, s.project.clips[1].id)
        assertEquals(0, s.setProject(createProject()).clipboard.size)
    }

    @Test
    fun `deduplicates an import batch by path and records it as one transaction`() {
        var s = editor()
        val a = s.project.assets[0]
        s = s.addAssets(listOf(a.copy(id = "fresh", path = "/tmp/fresh.mp4"), a.copy(id = "another", path = "/tmp/fresh.mp4"), a))
        assertEquals(2, s.project.assets.size)
        assertEquals(1, s.past.size)
        assertSame(s, s.addAssets(listOf(a)))
    }

    @Test
    fun `refuses to remove media still on the timeline`() {
        var s = editor()
        assertRejected(EditRejectedException.Reason.AssetInUse) { s.removeAsset("asset") }
        s = s.select(listOf(s.project.clips[0].id)).deleteSelected().removeAsset("asset")
        assertEquals(0, s.project.assets.size)
    }

    @Test
    fun `magnetic delete compresses a union rather than double-counting overlapping deletions`() {
        var s = editor()
        val p = s.project
        val c = p.clips[0]
        s = s.commit(
            p.copy(
                clips = listOf(
                    c.copy(duration = 4.0),
                    c.copy(id = "overlap", start = 2.0, duration = 4.0),
                    c.copy(id = "tail", start = 8.0, duration = 3.0),
                ),
            ),
        )
        s = s.setMagnetic(true).select(listOf(c.id, "overlap")).deleteSelected()
        assertEquals(2.0, s.project.clips[0].start)
        assertEquals(emptyList(), s.selectedIds)
        s = s.undo()
        assertEquals(3, s.project.clips.size)
    }

    @Test
    fun `adds subframe subtitles with positive duration and ignores invalid cues`() {
        val s = editor().addSubtitles(
            listOf(SubtitleCue(1.0, 1.001, "Short"), SubtitleCue(Double.NaN, 4.0, "Broken")),
        )
        assertEquals(2, s.project.clips.size)
        assertClose(1.0 / 30, s.project.clips[1].duration)
        assertEquals("Short", s.project.clips[1].text?.text)
        assertEquals("Subtitles", s.project.tracks[0].name)
    }

    @Test
    fun `adds text at the playhead and selects it`() {
        val s = editor().seek(2.5).addText()
        val text = s.project.clips.last()
        assertEquals(2.5, text.start)
        assertEquals(s.project.tracks[0].id, text.trackId)
        assertEquals(listOf(text.id), s.selectedIds)
    }

    @Test
    fun `splits the clips under the playhead when nothing is selected`() {
        val s = editor().seek(1.0).split()
        assertEquals(listOf(listOf(0.0, 1.0), listOf(1.0, 2.0)), s.project.clips.map { listOf(it.start, it.duration) })
        assertEquals(1, s.past.size)
    }

    @Test
    fun `clamps zoom and ignores non-finite seeks`() {
        val s = editor()
        assertEquals(400.0, s.setZoom(1e6).zoom)
        assertEquals(5.0, s.setZoom(0.0).zoom)
        assertSame(s, s.setZoom(Double.NaN))
        assertSame(s, s.seek(Double.POSITIVE_INFINITY))
        assertEquals(0.0, s.seek(-3.0).playhead)
        assertFalse(s.setPlaying(true).seek(1.0).playing)
    }

    @Test
    fun `history keeps at most the last 150 snapshots`() {
        var s = editor()
        repeat(HISTORY_LIMIT + 10) { i -> s = s.updateProject { it.copy(name = "Edit $i") } }
        assertEquals(HISTORY_LIMIT, s.past.size)
        assertEquals("Edit 9", s.past.first().name)
        assertFalse(s.markSaved("/projects/a.klipa").dirty)
    }

    @Test
    fun `asset metadata updates reach every snapshot without an undo step`() {
        var s = editor().addText().addText()
        s = s.undo()
        s = s.updateAssetMetadata("/tmp/video.mp4") { it.copy(waveform = listOf(0.5)) }
        assertEquals(1, s.past.size)
        assertEquals(1, s.future.size)
        for (project in listOf(s.project) + s.past + s.future) assertEquals(listOf(0.5), project.assets[0].waveform)
        assertEquals(listOf(0.5), s.redo().project.assets[0].waveform)
    }

    // track management

    @Test
    fun `deletes clips with their track in one transaction, keeps media, cleans selection and undoes`() {
        var s = editor()
        val p = s.project
        val c = p.clips[0]
        s = s.addText()
        val text = s.project.clips.first { it.text != null }
        s = s.select(listOf(c.id, text.id))
        val before = s.past.size
        s = s.deleteTrack(c.trackId)
        assertEquals(before + 1, s.past.size)
        assertFalse(s.project.tracks.any { it.id == c.trackId })
        assertEquals(listOf(text.id), s.project.clips.map { it.id })
        assertEquals(p.assets, s.project.assets)
        assertEquals(listOf(text.id), s.selectedIds)
        s = s.undo()
        assertTrue(s.project.clips.any { it.id == c.id })
        assertTrue(s.project.tracks.any { it.id == c.trackId })
    }

    @Test
    fun `allows deleting the last track and later recreating a compatible track on add`() {
        var s = editor()
        val p = s.project
        s = s.commit(p.copy(tracks = listOf(p.tracks[1]))).deleteTrack(p.tracks[1].id)
        assertEquals(0, s.project.tracks.size)
        assertEquals(0, s.project.clips.size)
        s = s.addAssetClip("asset")
        assertEquals(1, s.project.tracks.size)
        assertEquals(1, s.project.clips.size)
    }

    @Test
    fun `reorders one adjacent track with undo and refuses locked tracks and bounds`() {
        var s = editor()
        val initial = s.project.tracks.map { it.id }
        s = s.reorderTrack(initial[1], TrackDirection.Up)
        assertEquals(listOf(initial[1], initial[0], initial[2]), s.project.tracks.map { it.id })
        assertEquals(1, s.past.size)
        s = s.undo()
        assertEquals(initial, s.project.tracks.map { it.id })
        s = s.updateTrack(initial[0]) { it.copy(locked = true) }
        val before = s.past.size
        s = s.reorderTrack(initial[0], TrackDirection.Down)
            .reorderTrack(initial[1], TrackDirection.Up)
            .reorderTrack(initial[2], TrackDirection.Down)
            .deleteTrack(initial[0])
        assertEquals(before, s.past.size)
        assertEquals(initial, s.project.tracks.map { it.id })
    }

    @Test
    fun `trims renamed tracks, rejects empty or locked renames, and still permits unlocking and flags`() {
        var s = editor()
        val track = s.project.tracks[1]
        s = s.updateTrack(track.id) { it.copy(name = "  Shot  ") }
        assertEquals("Shot", s.project.tracks[1].name)
        var before = s.past.size
        s = s.updateTrack(track.id) { it.copy(name = "   ") }
        assertEquals(before, s.past.size)
        s = s.updateTrack(track.id) { it.copy(locked = true) }
        before = s.past.size
        s = s.updateTrack(track.id) { it.copy(name = "locked rename") }
        assertEquals(before, s.past.size)
        assertEquals("Shot", s.project.tracks[1].name)
        s = s.updateTrack(track.id) { it.copy(muted = true, locked = false) }
        assertTrue(s.project.tracks[1].muted)
        assertFalse(s.project.tracks[1].locked)
    }

    @Test
    fun `adds numbered tracks, audio at the bottom and others on top`() {
        val s = editor().addTrack(TrackKind.Video).addTrack(TrackKind.Audio)
        assertEquals("Video 2", s.project.tracks.first().name)
        assertEquals("Audio 2", s.project.tracks.last().name)
    }

    // linked clips through the store

    @Test
    fun `commits placement, movement, trim and unlink atomically and preserves links after reopen`() {
        val p = linkedPair()
        var s = editor(p).seek(2.0)
        s = s.placeAsset("v", p.tracks[1].id, SourceRange(10.0, 1.0), PlacementMode.Insert)
        assertEquals(1, s.past.size)
        s = s.undo()
        assertEquals(p, s.project)
        s = s.redo()
        val inserted = s.project
        s = s.setProject(ProjectJson.decode(ProjectJson.encode(inserted)))
        val right = s.project.clips.first { it.start == 3.0 }
        s = s.select(listOf(right.id))
        assertEquals(2, s.selectedIds.size)
        s = s.moveClips(listOf(right.id), 1.0)
        assertEquals(1, s.past.size)
        s = s.undo()
        assertEquals(inserted, s.project)
        s = s.trim(right.id, TrimEdge.End, 6.0)
        assertTrue(s.project.clips.filter { it.linkId == right.linkId }.all { it.duration == 3.0 })
        s = s.undo().select(listOf(right.id)).unlinkSelected()
        assertEquals(0, s.project.clips.count { it.linkId == right.linkId })
        assertEquals(listOf(right.id), s.selectedIds)
        s = s.undo()
        assertEquals(inserted, s.project)
    }

    @Test
    fun `inspector start, source offset and speed keep a pair synchronized, and duplicate and paste use fresh groups`() {
        var s = editor(linkedPair())
        val c = s.project.clips[0]
        s = s.updateClip(c.id) { it.copy(start = 2.0) }
            .updateClip(c.id) { it.copy(sourceIn = 3.0) }
            .updateClip(c.id) { it.copy(speed = 2.0) }
        assertEquals(
            listOf(listOf(2.0, 3.0, 2.0, 3.0), listOf(2.0, 3.0, 2.0, 3.0)),
            s.project.clips.map { listOf(it.start, it.sourceIn, it.speed, it.duration) },
        )
        s = s.select(listOf(c.id)).duplicate()
        val copied = s.project.clips.drop(2)
        assertEquals(copied[0].linkId, copied[1].linkId)
        assertNotEquals(c.linkId, copied[0].linkId)
        s = s.copySelection().seek(10.0).paste()
        val pasted = s.project.clips.drop(4)
        assertEquals(pasted[0].linkId, pasted[1].linkId)
        assertNotEquals(copied[0].linkId, pasted[0].linkId)
    }

    @Test
    fun `refuses a track change to an incompatible or locked track`() {
        val s = editor(linkedPair())
        val video = s.project.clips[0]
        val audioTrack = s.project.tracks[2]
        assertSame(s, s.updateClip(video.id) { it.copy(trackId = audioTrack.id, volume = 0.5) })
        val locked = s.updateTrack(audioTrack.id) { it.copy(locked = true) }
        assertSame(locked, locked.updateClip(s.project.clips[1].id) { it.copy(trackId = s.project.tracks[1].id) })
    }

    @Test
    fun `rejects magnetic deletion that would desynchronize surviving linked clips`() {
        val p = linkedPair()
        val inserted = placeAsset(p, "v", p.tracks[1].id, 2.0, SourceRange(10.0, 1.0), PlacementMode.Insert)
        val s = editor(inserted.project).setMagnetic(true).select(listOf(inserted.clipId))
        assertRejected(EditRejectedException.Reason.MagneticDeleteDesync) { s.deleteSelected() }
        assertEquals(4, s.setMagnetic(false).deleteSelected().project.clips.size)
    }

    @Test
    fun `copy operations do not create a silent half-pair when a partner track is locked`() {
        val p = linkedPair()
        val s = editor(p).select(listOf(p.clips[0].id)).copySelection().updateTrack(p.tracks[2].id) { it.copy(locked = true) }
        assertSame(s, s.duplicate())
        assertSame(s, s.paste())
    }
}
