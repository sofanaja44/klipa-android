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
        is Segment.Item -> "${clip.name} $startUs+$durationUs" + (if (removeVideo) " -v" else "") + (if (removeAudio) " -a" else "")
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
    fun `hidden tracks keep their sound, muted tracks their picture, and silent pictures carry no audio`() {
        val p = project { listOf(it.video(0.0, 2.0), createClip(still, it.tracks[1].id, 2.0, it.fps).copy(duration = 1.0)) }
        val hidden = p.copy(tracks = p.tracks.map { if (it.id == p.tracks[1].id) it.copy(hidden = true) else it })
        val audioOnly = planComposition(hidden).sequences.single()
        assertEquals(listOf(false, true), listOf(audioOnly.hasVideo, audioOnly.hasAudio))
        assertEquals(listOf("Video 0+2000000 -v", "gap 2000000+1000000"), audioOnly.segments.map { it.describe() })

        val muted = p.copy(tracks = p.tracks.map { if (it.id == p.tracks[1].id) it.copy(muted = true) else it })
        val pictureOnly = planComposition(muted).sequences.single()
        assertEquals(listOf(true, false), listOf(pictureOnly.hasVideo, pictureOnly.hasAudio))

        val mixed = planComposition(p).sequences.single()
        assertEquals(listOf("Video 0+2000000", "Video 2000000+1000000 -a"), mixed.segments.map { it.describe() })
    }

    @Test
    fun `a muted clip after audio extraction leaves its sound to the linked audio clip`() {
        val p = project { listOf(it.video(0.0, 2.0).copy(muted = true)) }
        val sequence = planComposition(p).sequences.single()
        assertEquals(listOf(true, false), listOf(sequence.hasVideo, sequence.hasAudio))
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
