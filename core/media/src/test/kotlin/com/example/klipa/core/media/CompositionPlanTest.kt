package com.example.klipa.core.media

import com.example.klipa.core.model.Easing
import com.example.klipa.core.model.Keyframe
import com.example.klipa.core.model.KeyframeProperty
import com.example.klipa.core.model.MediaKind
import com.example.klipa.core.model.Project
import com.example.klipa.core.model.Transform
import com.example.klipa.core.model.createClip
import com.example.klipa.core.model.createProject
import com.example.klipa.core.model.testAsset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CompositionPlanTest {
    private val music = testAsset.copy(id = "music", path = "/music.m4a", url = "file:///music.m4a", kind = MediaKind.Audio, width = 0, height = 0)
    private val still = testAsset.copy(id = "still", path = "/still.jpg", url = "file:///still.jpg", kind = MediaKind.Image, duration = 0.0, hasAudio = false)

    /** Tracks: text, video, audio. */
    private fun project(build: (Project) -> List<com.example.klipa.core.model.Clip>): Project {
        val base = createProject().copy(assets = listOf(testAsset, music, still))
        return base.copy(clips = build(base))
    }

    private fun Project.video(start: Double, duration: Double) = createClip(testAsset, tracks[1].id, start, fps).copy(duration = duration)

    private fun Segment.describe() = when (this) {
        is Segment.Gap -> "gap $startUs+$durationUs"
        is Segment.Item -> "${clip.name} $startUs+$durationUs" + (if (removeVideo) " -v" else "") + (if (muted) " muted" else "")
    }

    @Test
    fun `an empty project or one with only text has nothing to play`() {
        assertTrue(planComposition(createProject()).isEmpty)
        val text = project { listOf(createClip(null, it.tracks[0].id, 0.0, it.fps)) }
        assertTrue(planComposition(text).isEmpty)
    }

    @Test
    fun `clips become items with gaps between them and every sequence spans the whole project`() {
        val p = project { listOf(it.video(1.0, 2.0), it.video(4.0, 1.0), createClip(music, it.tracks[2].id, 0.0, it.fps).copy(duration = 7.0)) }
        val plan = planComposition(p)
        assertEquals(7_000_000, plan.durationUs)
        assertEquals(listOf(true, false), plan.sequences.map { it.hasVideo })
        assertEquals(
            listOf("gap 0+1000000", "Video 1000000+2000000", "gap 3000000+1000000", "Video 4000000+1000000", "gap 5000000+2000000"),
            plan.sequences[0].segments.map { it.describe() },
        )
        assertEquals(listOf("Video 0+7000000 -v"), plan.sequences[1].segments.map { it.describe() })
        assertEquals(p.clips[1].id, plan.sequences[0].itemAt(4_500_000)?.clip?.id)
        assertNull(plan.sequences[0].itemAt(3_500_000))
    }

    @Test
    fun `video sequences come first, top track first`() {
        val base = createProject().copy(assets = listOf(testAsset, music))
        val overlay = base.tracks[1].copy(id = "overlay", name = "Video 2")
        val p = base.copy(
            tracks = listOf(base.tracks[0], overlay) + base.tracks.drop(1),
            clips = listOf(
                createClip(music, base.tracks[2].id, 0.0, 30).copy(duration = 2.0),
                createClip(testAsset, base.tracks[1].id, 0.0, 30).copy(duration = 2.0),
                createClip(testAsset, "overlay", 0.0, 30).copy(duration = 1.0),
            ),
        )
        assertEquals(listOf("overlay", p.tracks[2].id, p.tracks[3].id), planComposition(p).sequences.map { it.trackId })
    }

    @Test
    fun `hidden tracks keep their sound, muted tracks play silently, and stills are silent`() {
        val p = project { listOf(it.video(0.0, 2.0), createClip(still, it.tracks[1].id, 2.0, it.fps).copy(duration = 1.0)) }
        val hidden = p.copy(tracks = p.tracks.map { if (it.id == p.tracks[1].id) it.copy(hidden = true) else it })
        val audioOnly = planComposition(hidden).sequences.single()
        assertEquals(listOf(false, true), listOf(audioOnly.hasVideo, audioOnly.hasAudio))
        assertEquals(listOf("Video 0+2000000 -v", "gap 2000000+1000000"), audioOnly.segments.map { it.describe() })

        // Muting never removes audio from items (androidx/media#2854): they play at zero gain.
        val muted = p.copy(tracks = p.tracks.map { if (it.id == p.tracks[1].id) it.copy(muted = true) else it })
        val silent = planComposition(muted).sequences.single()
        assertEquals(listOf(true, true), listOf(silent.hasVideo, silent.hasAudio))
        assertEquals(listOf("Video 0+2000000 muted", "Video 2000000+1000000 muted"), silent.segments.map { it.describe() })

        val mixed = planComposition(p).sequences.single()
        assertEquals(listOf("Video 0+2000000", "Video 2000000+1000000 muted"), mixed.segments.map { it.describe() })
    }

    @Test
    fun `a muted clip after audio extraction plays silently and a silent track declares no audio`() {
        val p = project { listOf(it.video(0.0, 2.0).copy(muted = true)) }
        val sequence = planComposition(p).sequences.single()
        assertEquals(listOf(true, true), listOf(sequence.hasVideo, sequence.hasAudio))
        assertEquals(listOf("Video 0+2000000 muted"), sequence.segments.map { it.describe() })
        val stills = project { listOf(createClip(still, it.tracks[1].id, 0.0, it.fps).copy(duration = 1.0)) }
        assertEquals(listOf(true, false), planComposition(stills).sequences.single().let { listOf(it.hasVideo, it.hasAudio) })
    }

    @Test
    fun `base-layer plans keep the bottom picture and only the sound of layers above`() {
        val base = createProject().copy(assets = listOf(testAsset, still))
        val overlay = base.tracks[1].copy(id = "overlay", name = "Video 2")
        val p = base.copy(
            tracks = listOf(base.tracks[0], overlay) + base.tracks.drop(1),
            clips = listOf(
                createClip(testAsset, base.tracks[1].id, 0.0, 30).copy(duration = 3.0, name = "Base"),
                createClip(testAsset, "overlay", 0.0, 30).copy(duration = 1.0, name = "Over"),
                createClip(still, "overlay", 1.0, 30).copy(duration = 1.0, name = "Sticker"),
            ),
        )
        val plan = planComposition(p).baseLayerOnly()
        assertEquals(listOf(p.tracks[2].id, "overlay"), plan.sequences.map { it.trackId })
        assertEquals(listOf(true, false), plan.sequences.map { it.hasVideo })
        assertEquals(listOf("Over 0+1000000 -v", "gap 1000000+1000000", "gap 2000000+1000000"), plan.sequences[1].segments.map { it.describe() })
    }

    @Test
    fun `playback signature ignores names, timestamps and waveforms`() {
        val p = project { listOf(it.video(0.0, 2.0)) }
        val renamed = p.copy(name = "Other", updatedAt = "2030-01-01T00:00:00Z", clips = p.clips.map { it.copy(name = "Shot") })
            .let { q -> q.copy(assets = q.assets.map { it.copy(waveform = listOf(0.5)) }) }
        assertEquals(playbackSignature(p), playbackSignature(renamed))
        val titled = p.copy(clips = p.clips + createClip(null, p.tracks[0].id, 0.0, p.fps))
        assertEquals(playbackSignature(p), playbackSignature(titled))
        assertTrue(playbackSignature(p) != playbackSignature(p.copy(clips = p.clips.map { it.copy(volume = 0.5) })))
    }

    @Test
    fun `base-layer motion maps project pixels into normalized coordinates`() {
        val p = createProject() // 1920 x 1080
        assertMatrix(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f), motionValues(p, Transform()))
        val moved = motionValues(p, Transform(x = 480.0, y = 270.0, scale = 0.5))
        assertMatrix(floatArrayOf(0.5f, 0f, 0.5f, 0f, 0.5f, -0.5f, 0f, 0f, 1f), moved)
        // A quarter turn clockwise on screen sends the right edge down, without distortion.
        val turned = motionValues(createProject().copy(width = 1000, height = 1000), Transform(rotation = 90.0))
        assertEquals(0f, turned[0] + 0f, 1e-6f)
        assertEquals(1f, turned[1], 1e-6f)
        assertEquals(-1f, turned[3], 1e-6f)
    }

    private fun assertMatrix(expected: FloatArray, actual: FloatArray) {
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) assertEquals(expected[i], actual[i], 1e-6f, "value $i")
    }

    @Test
    fun `overlapping clips on one track are split into stacked sequences with the later clip on top`() {
        val p = project { listOf(it.video(0.0, 4.0), it.video(2.0, 4.0).copy(name = "Later"), it.video(6.0, 1.0)) }
        val sequences = planComposition(p).sequences
        assertEquals(2, sequences.size)
        assertEquals("Later", sequences[0].itemAt(3_000_000)?.clip?.name)
        assertEquals("Video", sequences[1].itemAt(3_000_000)?.clip?.name)
        assertEquals("Video", sequences[1].itemAt(6_500_000)?.clip?.name)
    }

    @Test
    fun `clips whose media is missing play as gaps`() {
        val p = project { listOf(it.video(0.0, 2.0)) }.let { it.copy(assets = it.assets.map { a -> if (a.id == testAsset.id) a.copy(missing = true) else a }) }
        assertTrue(planComposition(p).isEmpty)
    }

    @Test
    fun `frames are fitted inside the project frame, after cropping`() {
        val p = createProject()
        val clip = p.video(0.0, 1.0)
        assertEquals(1920 to 1080, fitSize(p, clip, testAsset))
        assertEquals(608 to 1080, fitSize(p, clip, testAsset.copy(width = 1080, height = 1920)))
        assertEquals(1920 to 1080, fitSize(p, clip.copy(crop = com.example.klipa.core.model.CropRect(0.0, 0.0, 0.5, 0.5)), testAsset))
        assertNull(fitSize(p, clip, testAsset.copy(width = 0)))
    }

    @Test
    fun `layer overlays map offsets, rotation, flips and animated opacity`() {
        val p = createProject()
        val clip = p.video(0.0, 4.0).copy(
            transform = Transform(x = 480.0, y = 270.0, scale = 0.5, rotation = 30.0, flipX = true),
            fadeIn = 1.0,
            keyframes = listOf(
                Keyframe("a", 0.0, KeyframeProperty.Opacity, 1.0, Easing.Linear),
                Keyframe("b", 4.0, KeyframeProperty.Opacity, 0.0, Easing.Linear),
            ),
        )
        val overlay = layerOverlay(p, clip, 2.0) as LayerOverlay
        assertEquals(LayerOverlay(alpha = 0.5f, anchorX = 0.5f, anchorY = -0.5f, scaleX = -0.5f, scaleY = 0.5f, rotation = -30f), overlay)
        // Keyframed opacity 0.875 times the fade-in at its midpoint.
        assertEquals(0.4375f, (layerOverlay(p, clip, 0.5) as LayerOverlay).alpha)
    }
}
