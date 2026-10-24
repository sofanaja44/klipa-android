// CompositionPlayer is Media3's only multi-track preview and is marked experimental.
// Klipa pins the Media3 version and falls back to still frames if the player fails.
@file:OptIn(UnstableApi::class, ExperimentalApi::class)

package com.example.klipa

import androidx.annotation.OptIn
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import com.example.klipa.core.media.LiveProject
import com.example.klipa.core.media.ProjectComposition
import com.example.klipa.core.model.Adjustments
import com.example.klipa.core.model.ChromaKey
import com.example.klipa.core.model.MaskShape
import com.example.klipa.core.model.ShapeMask
import com.example.klipa.core.media.VideoLayering
import com.example.klipa.core.media.buildComposition
import com.example.klipa.core.model.Transform
import com.example.klipa.feature.preview.newCompositionPlayer
import com.example.klipa.core.model.MediaKind
import com.example.klipa.core.model.Project
import com.example.klipa.core.model.createClip
import com.example.klipa.core.model.createProject
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * Builds real Media3 Compositions so Media3's own structural checks (gaps, track types,
 * sequence rules, player setup) run on the JVM. Decoding and GL need a device.
 */
@RunWith(RobolectricTestRunner::class)
class CompositionBuildTest {
    private val video = asset("v", "Shot.mp4", MediaKind.Video, 10.0)
    private val still = asset("s", "Still.jpg", MediaKind.Image, 0.0).copy(width = 1080, height = 1350, hasAudio = false)
    private val music = asset("m", "Music.m4a", MediaKind.Audio, 30.0)

    private fun project(build: (Project) -> List<com.example.klipa.core.model.Clip>): Project {
        val base = createProject().copy(assets = listOf(video, still, music))
        return base.copy(clips = build(base))
    }

    @Test
    fun `a single video track plays through the default single-input graph`() {
        val built = assertNotNull(buildComposition(sampleProject()))
        assertEquals(VideoLayering.BaseLayer, built.layering)
        val sequences = built.composition.sequences
        assertEquals(3, sequences.size)
        // The main video track carries its shots' sound; the extracted one plays at zero gain.
        assertEquals(setOf(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_AUDIO), sequences[0].trackTypes)
        assertEquals(setOf(C.TRACK_TYPE_AUDIO), sequences[1].trackTypes)
        assertEquals(setOf(C.TRACK_TYPE_AUDIO), sequences[2].trackTypes)
        // No item drops its audio (androidx/media#2854).
        assertTrue(sequences.flatMap { it.editedMediaItems }.none { it.removeAudio })
        load(built)
    }

    @Test
    fun `sequences may start with a gap and mix stills, speed changes and muted clips`() {
        val p = project {
            listOf(
                createClip(video, it.tracks[1].id, 2.0, it.fps).copy(duration = 3.0, speed = 2.0, fadeIn = 1.0),
                createClip(still, it.tracks[1].id, 5.0, it.fps).copy(duration = 2.0, transform = Transform(x = 100.0, scale = 0.5, rotation = 10.0)),
                createClip(video, it.tracks[1].id, 7.0, it.fps).copy(duration = 1.0, muted = true, sourceIn = 9.0),
                createClip(music, it.tracks[2].id, 1.0, it.fps).copy(duration = 4.0, volume = 0.5, audioFadeOut = 1.0),
            )
        }
        val built = assertNotNull(buildComposition(p))
        assertEquals(VideoLayering.BaseLayer, built.layering)
        // Gaps are items too: gap, shot, still, shot / gap, music, gap.
        assertEquals(listOf(4, 3), built.composition.sequences.map { it.editedMediaItems.size })
        assertEquals(setOf(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_AUDIO), built.composition.sequences[0].trackTypes)
        load(built)
    }

    @Test
    fun `audio-only edits build too`() {
        load(assertNotNull(buildComposition(project { listOf(createClip(music, it.tracks[2].id, 0.0, it.fps).copy(duration = 3.0)) })))
    }

    @Test
    fun `several video layers use the compositor, or only the bottom layer when that is not allowed`() {
        val overlap = project {
            listOf(createClip(video, it.tracks[1].id, 0.0, it.fps).copy(duration = 4.0), createClip(video, it.tracks[1].id, 2.0, it.fps).copy(duration = 4.0))
        }
        val layered = assertNotNull(buildComposition(overlap))
        assertEquals(VideoLayering.Layered, layered.layering)
        assertEquals(2, layered.plan.videoSequences.size)
        load(layered)
        val base = assertNotNull(buildComposition(overlap, allowLayering = false))
        assertEquals(VideoLayering.BaseLayer, base.layering)
        assertEquals(1, base.plan.videoSequences.size)
        load(base)
    }

    @Test
    fun `looks, chroma keys, masks and blur play on one layer and composited`() {
        val green = ChromaKey(enabled = true, color = "#00ff00", similarity = 0.1, blend = 0.05)
        val mask = ShapeMask(MaskShape.Ellipse, width = 0.8, height = 0.8, feather = 0.1, invert = false)
        val styled = project {
            listOf(
                createClip(video, it.tracks[1].id, 0.0, it.fps).copy(duration = 4.0, adjustments = Adjustments(contrast = 1.2, temperature = 0.3, blur = 6.0)),
                createClip(video, it.tracks[1].id, 2.0, it.fps).copy(duration = 4.0, chromaKey = green, mask = mask, adjustments = Adjustments(blur = 2.0)),
                createClip(still, it.tracks[1].id, 6.0, it.fps).copy(duration = 2.0, mask = mask),
            )
        }
        val live = LiveProject(styled)
        val layered = assertNotNull(buildComposition(styled, live = live))
        assertEquals(VideoLayering.Layered, layered.layering)
        load(layered)
        load(assertNotNull(buildComposition(styled, allowLayering = false, live = live)))
    }

    @Test
    fun `a project with only text has nothing for the player`() {
        assertNull(buildComposition(project { listOf(createClip(null, it.tracks[0].id, 0.0, it.fps)) }))
    }

    /** Loads [built] into a player configured exactly as the app configures it. */
    private fun load(built: ProjectComposition) {
        val player = newCompositionPlayer(RuntimeEnvironment.getApplication(), built.layering == VideoLayering.Layered)
        try {
            player.setComposition(built.composition)
            player.prepare()
            shadowOf(Looper.getMainLooper()).idle()
            // The sample media do not exist on the JVM, so reading them may already have
            // failed; any other error means Media3 rejected the composition.
            player.playerError?.let { error ->
                assertEquals(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND, error.errorCode, "unexpected player error: $error (${error.cause})")
            }
        } finally {
            player.release()
        }
    }
}
