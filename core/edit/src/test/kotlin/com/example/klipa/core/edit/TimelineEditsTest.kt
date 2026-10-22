package com.example.klipa.core.edit

import com.example.klipa.core.model.Easing
import com.example.klipa.core.model.FadeChannel
import com.example.klipa.core.model.InvalidProjectException
import com.example.klipa.core.model.Keyframe
import com.example.klipa.core.model.KeyframeProperty
import com.example.klipa.core.model.MediaAsset
import com.example.klipa.core.model.MediaKind
import com.example.klipa.core.model.Project
import com.example.klipa.core.model.ProjectJson
import com.example.klipa.core.model.TrackKind
import com.example.klipa.core.model.assertClose
import com.example.klipa.core.model.createClip
import com.example.klipa.core.model.createProject
import com.example.klipa.core.model.evaluateFade
import com.example.klipa.core.model.evaluateKeyframes
import com.example.klipa.core.model.toJsonTree
import com.example.klipa.core.model.validateProject
import com.example.klipa.core.model.with
import com.example.klipa.core.model.withFirstClip
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive

// Ported from the timeline-edit cases of ReaCut tests/timeline-edits.test.ts. The store,
// assistant and transition cases there belong to later milestones.

class TimelineEditsTest {
    private val source = MediaAsset(
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
    private fun fixture(): Project {
        val project = createProject("Linked rough cut")
        val clip = createClip(source, project.tracks[1].id, 0.0, project.fps).copy(duration = 6.0, sourceIn = 2.0)
        return project.copy(assets = listOf(source), clips = listOf(clip))
    }

    /** The fixture with its sound detached: clips[0] is the muted video, clips[1] the linked audio. */
    private fun pair(): Project {
        val project = fixture()
        return detachLinkedAudio(project, project.clips[0].id).project
    }

    private fun Project.lockTrack(index: Int) =
        copy(tracks = tracks.mapIndexed { i, t -> if (i == index) t.copy(locked = true) else t })

    /** `[start, duration, sourceIn]` of each clip on a track, in timeline order. */
    private fun Project.ranges(trackId: String = tracks[1].id): List<List<Double>> =
        clips.filter { it.trackId == trackId }.sortedBy { it.start }.map { listOf(it.start, it.duration, it.sourceIn) }

    private fun assertRejected(reason: EditRejectedException.Reason, block: () -> Unit) {
        assertEquals(reason, assertFailsWith<EditRejectedException> { block() }.reason)
    }

    // insert / overwrite with linked sound

    @Test
    fun `inserts a source range through a pair, ripples later tracks and preserves the original source`() {
        val base = pair()
        val title = createClip(null, base.tracks[0].id, 5.0, 30).copy(duration = 1.0)
        val p = base.copy(clips = base.clips + title)
        val r = placeAsset(p, "v", p.tracks[1].id, 2.0, SourceRange(10.0, 1.0), PlacementMode.Insert).project
        assertEquals(listOf(listOf(0.0, 2.0, 2.0), listOf(2.0, 1.0, 10.0), listOf(3.0, 4.0, 4.0)), r.ranges())
        assertEquals(listOf(listOf(0.0, 2.0, 2.0), listOf(3.0, 4.0, 4.0)), r.ranges(p.tracks[2].id))
        assertEquals(6.0, r.clips.first { it.id == title.id }.start)
        assertEquals(2, r.clips.count { it.linkId == p.clips[0].linkId })
        val right = r.clips.first { it.start == 3.0 }
        assertEquals(2, linkedIds(r, listOf(right.id)).size)
        assertNotEquals(p.clips[0].linkId, right.linkId)
    }

    @Test
    fun `overwrites only the target and linked partners, retaining both sides without doubling sound`() {
        val base = pair()
        val music = base.clips[1].copy(id = "music", linkId = null)
        val p = base.copy(clips = base.clips + music)
        val r = placeAsset(p, "v", p.tracks[1].id, 2.0, SourceRange(10.0, 1.0), PlacementMode.Overwrite).project
        assertEquals(listOf(listOf(0.0, 2.0, 2.0), listOf(2.0, 1.0, 10.0), listOf(3.0, 3.0, 5.0)), r.ranges())
        assertEquals(music, r.clips.first { it.id == "music" })
        assertEquals(
            listOf(listOf(0.0, 2.0, 2.0), listOf(3.0, 3.0, 5.0)),
            r.clips.filter { it.trackId == p.tracks[2].id && it.id != "music" }.map { listOf(it.start, it.duration, it.sourceIn) },
        )
    }

    @Test
    fun `covers multiple overlapping clips and retains only valid outside intervals`() {
        val base = fixture()
        val p = base.copy(clips = base.clips + base.clips[0].copy(id = "overlap", start = 1.0, duration = 2.0))
        val r = placeAsset(p, "v", p.tracks[1].id, 0.0, SourceRange(10.0, 4.0), PlacementMode.Overwrite).project
        assertEquals(listOf(listOf(0.0, 4.0, 10.0), listOf(4.0, 2.0, 6.0)), r.ranges())
    }

    @Test
    fun `preserves keyframe and fade phases at retained boundaries`() {
        val p = fixture().withFirstClip {
            it.copy(
                fadeIn = 4.0,
                audioFadeOut = 3.0,
                keyframes = listOf(
                    Keyframe(id = "k1", time = 0.0, property = KeyframeProperty.Opacity, value = 0.0, easing = Easing.Linear),
                    Keyframe(id = "k2", time = 6.0, property = KeyframeProperty.Opacity, value = 1.0, easing = Easing.EaseInOut),
                ),
            )
        }
        val c = p.clips[0]
        val r = placeAsset(p, "v", c.trackId, 2.0, SourceRange(10.0, 1.0), PlacementMode.Overwrite).project
        val right = r.clips.first { it.start == 3.0 }
        for (t in listOf(0.0, 0.3, 1.0, 2.5)) {
            assertClose(evaluateKeyframes(c, t + 3).transform.opacity, evaluateKeyframes(right, t).transform.opacity, 6)
            assertClose(evaluateFade(c, t + 3, FadeChannel.Video), evaluateFade(right, t, FadeChannel.Video), 6)
            assertClose(evaluateFade(c, t + 3, FadeChannel.Audio), evaluateFade(right, t, FadeChannel.Audio), 6)
        }
    }

    @Test
    fun `rejects a locked linked partner atomically and ignores unrelated locked tracks for overwrite`() {
        val p = pair().lockTrack(2)
        for (mode in listOf(PlacementMode.Insert, PlacementMode.Overwrite)) {
            assertRejected(EditRejectedException.Reason.TouchesLockedTrack) {
                placeAsset(p, "v", p.tracks[1].id, 2.0, SourceRange(0.0, 1.0), mode)
            }
        }
        val base = fixture().lockTrack(0)
        val q = base.copy(clips = base.clips + createClip(null, base.tracks[0].id, 0.0, 30))
        placeAsset(q, "v", q.tracks[1].id, 2.0, SourceRange(0.0, 1.0), PlacementMode.Overwrite)
        assertRejected(EditRejectedException.Reason.TouchesLockedTrack) {
            placeAsset(q, "v", q.tracks[1].id, 2.0, SourceRange(0.0, 1.0), PlacementMode.Insert)
        }
    }

    @Test
    fun `inserts at boundaries and in gaps without creating zero-length clips`() {
        val p = fixture().withFirstClip { it.copy(start = 2.0) }
        val r = placeAsset(p, "v", p.tracks[1].id, 2.0, SourceRange(0.0, 1.0), PlacementMode.Insert).project
        assertEquals(listOf(listOf(2.0, 1.0, 0.0), listOf(3.0, 6.0, 2.0)), r.ranges())
        val q = placeAsset(p, "v", p.tracks[1].id, 10.0, SourceRange(0.0, 1.0), PlacementMode.Insert).project
        assertEquals(listOf(listOf(2.0, 6.0, 2.0), listOf(10.0, 1.0, 0.0)), q.ranges())
    }

    @Test
    fun `keeps overlay behavior and validates range, destination, duration and link metadata`() {
        val p = pair()
        val r = placeAsset(p, "v", p.tracks[1].id, 1.0, SourceRange(2.0, 1.0), PlacementMode.Overlay).project
        assertEquals(p.clips, r.clips.take(2))
        assertRejected(EditRejectedException.Reason.IncompatibleTrack) {
            placeAsset(p, "v", p.tracks[2].id, 0.0, SourceRange(0.0, 1.0), PlacementMode.Insert)
        }
        assertRejected(EditRejectedException.Reason.InvalidSourceRange) {
            placeAsset(p, "v", null, 0.0, SourceRange(19.0, 2.0), PlacementMode.Insert)
        }
        assertFailsWith<InvalidProjectException> {
            placeAsset(p, "v", null, 86400.0, SourceRange(0.0, 1.0), PlacementMode.Insert)
        }
        assertFailsWith<InvalidProjectException> {
            validateProject(p.toJsonTree().with("clips", 0, "linkId", value = JsonPrimitive(42)))
        }
    }

    @Test
    fun `adds a compatible track when none is free and never rounds a partial frame up`() {
        val p = fixture().lockTrack(1)
        val placed = placeAsset(p, "v", null, 0.0, SourceRange(0.0, 1.02), PlacementMode.Overlay)
        val clip = placed.project.clips.first { it.id == placed.clipId }
        assertEquals(TrackKind.Video, placed.project.tracks.first { it.id == clip.trackId }.kind)
        assertEquals(4, placed.project.tracks.size)
        assertEquals(1.0, clip.duration)
        val image = p.copy(assets = p.assets + source.copy(id = "still", kind = MediaKind.Image, duration = 0.0))
        assertRejected(EditRejectedException.Reason.InvalidSourceRange) {
            placeAsset(image, "still", null, 0.0, SourceRange(1.0, 3.0), PlacementMode.Overlay)
        }
        assertEquals(3.0, placeAsset(image, "still", null, 0.0, SourceRange(0.0, 3.0), PlacementMode.Overlay).project.clips.last().duration)
    }

    // linked timeline edits

    @Test
    fun `selects a pair, moves and clamps together and trims by the most restrictive source handles`() {
        val p = pair()
        val ids = linkedIds(p, listOf(p.clips[1].id))
        assertEquals(p.clips[1].id, ids[0])
        assertEquals(2, ids.size)
        val moved = moveLinked(p, listOf(p.clips[0].id), 2.0)
        assertEquals(listOf(2.0, 2.0), moved.clips.map { it.start })
        val trimmed = trimLinked(moved, p.clips[0].id, TrimEdge.Start, 3.0)
        assertEquals(listOf(listOf(3.0, 3.0, 5.0), listOf(3.0, 3.0, 5.0)), trimmed.clips.map { listOf(it.start, it.sourceIn, it.duration) })
        val q = trimmed.copy(assets = trimmed.assets.mapIndexed { i, a -> if (i == 1) a.copy(duration = 9.0) else a })
        val limited = trimLinked(q, q.clips[0].id, TrimEdge.End, 20.0)
        assertEquals(listOf(6.0, 6.0), limited.clips.map { it.duration })
        assertEquals(listOf(0.0, 0.0), moveLinked(moved, listOf(p.clips[0].id), -5.0).clips.map { it.start })
        assertEquals(limited, ProjectJson.decode(ProjectJson.encode(limited)))
    }

    @Test
    fun `splits into independent linked pairs and unlink preserves rendered clip timing`() {
        val p = pair()
        val r = splitLinked(p, listOf(p.clips[0].id), 2.0)
        assertEquals(4, r.clips.size)
        val right = r.clips.first { it.start == 2.0 }
        assertEquals(2, linkedIds(r, listOf(right.id)).size)
        val u = unlinkClips(r, listOf(right.id))
        assertEquals(2, u.clips.count { it.linkId != null })
        assertEquals(r.clips.map { listOf(it.start, it.duration, it.sourceIn) }, u.clips.map { listOf(it.start, it.duration, it.sourceIn) })
    }

    @Test
    fun `locked partners block movement, trim, split and unlink without a partial edit`() {
        val p = pair().lockTrack(2)
        val c = p.clips[0]
        assertSame(p, moveLinked(p, listOf(c.id), 1.0))
        assertSame(p, trimLinked(p, c.id, TrimEdge.End, 3.0))
        assertSame(p, splitLinked(p, listOf(c.id), 2.0))
        assertSame(p, unlinkClips(p, listOf(c.id)))
        assertSame(p, speedLinked(p, c.id, 2.0))
        assertSame(p, slipLinked(p, c.id, 3.0))
    }

    @Test
    fun `start, source offset and speed keep a pair synchronized`() {
        var p = pair()
        val c = p.clips[0]
        p = moveLinked(p, listOf(c.id), 2.0 - c.start)
        p = slipLinked(p, c.id, 3.0)
        p = speedLinked(p, c.id, 2.0)
        assertEquals(listOf(listOf(2.0, 3.0, 2.0, 3.0), listOf(2.0, 3.0, 2.0, 3.0)), p.clips.map { listOf(it.start, it.sourceIn, it.speed, it.duration) })
        assertSame(p, slipLinked(p, c.id, 15.0))
        assertSame(p, slipLinked(p, c.id, -1.0))
    }

    @Test
    fun `moves clips onto a compatible target track only`() {
        val base = fixture()
        val p = base.copy(tracks = base.tracks + base.tracks[1].copy(id = "video2", name = "Video 2"))
        val c = p.clips[0]
        assertEquals("video2", moveLinked(p, listOf(c.id), 0.0, "video2").clips[0].trackId)
        assertEquals(c.trackId, moveLinked(p, listOf(c.id), 0.0, p.tracks[2].id).clips[0].trackId)
        assertSame(p, moveLinked(p, listOf(c.id), Double.NaN))
        assertSame(p, moveLinked(p, listOf("nothing"), 1.0))
    }

    @Test
    fun `detaches sound into a muted video and a linked audio clip`() {
        val base = fixture().withFirstClip {
            it.copy(
                keyframes = listOf(
                    Keyframe(id = "v1", time = 0.0, property = KeyframeProperty.Volume, value = 0.2, easing = Easing.Linear),
                    Keyframe(id = "x1", time = 0.0, property = KeyframeProperty.X, value = 10.0, easing = Easing.Linear),
                ),
            )
        }
        val detached = detachLinkedAudio(base, base.clips[0].id)
        val (video, audio) = detached.project.clips
        assertEquals(detached.audioClipId, audio.id)
        assertTrue(video.muted)
        assertEquals(video.linkId, audio.linkId)
        assertEquals(base.tracks[2].id, audio.trackId)
        assertEquals(listOf(KeyframeProperty.Volume), audio.keyframes.map { it.property })
        assertNotEquals("v1", audio.keyframes[0].id)
        val audioAsset = detached.project.assets.first { it.id == audio.assetId }
        assertEquals(MediaKind.Audio, audioAsset.kind)
        assertEquals(source.path, audioAsset.path)
        validateProject(detached.project)

        assertRejected(EditRejectedException.Reason.AlreadyLinked) { detachLinkedAudio(detached.project, video.id) }
        assertRejected(EditRejectedException.Reason.TrackLocked) { detachLinkedAudio(base.lockTrack(1), base.clips[0].id) }
        val silent = base.copy(assets = listOf(source.copy(hasAudio = false)))
        assertRejected(EditRejectedException.Reason.NoAudioToDetach) { detachLinkedAudio(silent, base.clips[0].id) }
        val newTrack = detachLinkedAudio(base.lockTrack(2), base.clips[0].id).project
        assertEquals(4, newTrack.tracks.size)
        assertEquals(TrackKind.Audio, newTrack.tracks.last().kind)
    }
}
