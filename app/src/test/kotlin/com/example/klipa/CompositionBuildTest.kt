// CompositionPlayer is Media3's only multi-track preview and is marked experimental.
// Klipa pins the Media3 version and falls back to still frames if the player fails.
@file:OptIn(UnstableApi::class, ExperimentalApi::class)

package com.example.klipa

import androidx.annotation.OptIn
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.effect.MultipleInputVideoGraph
import androidx.media3.transformer.CompositionPlayer
import com.example.klipa.core.media.buildComposition
import com.example.klipa.core.model.MediaKind
import com.example.klipa.core.model.Project
import com.example.klipa.core.model.createClip
import com.example.klipa.core.model.createProject
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
    fun `the sample edit builds into layered video and mixed audio sequences`() {
        val built = assertNotNull(buildComposition(sampleProject()))
        val sequences = built.composition.sequences
        assertEquals(3, sequences.size)
        // The main video track carries the unextracted shot's sound too.
        assertEquals(setOf(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_AUDIO), sequences[0].trackTypes)
        assertEquals(setOf(C.TRACK_TYPE_AUDIO), sequences[1].trackTypes)
        assertEquals(setOf(C.TRACK_TYPE_AUDIO), sequences[2].trackTypes)
        loadIntoPlayer(built.composition)
    }

    @Test
    fun `sequences may start with a gap and mix stills, speed changes and muted clips`() {
        val p = project {
            listOf(
                createClip(video, it.tracks[1].id, 2.0, it.fps).copy(duration = 3.0, speed = 2.0),
                createClip(still, it.tracks[1].id, 5.0, it.fps).copy(duration = 2.0),
                createClip(video, it.tracks[1].id, 7.0, it.fps).copy(duration = 1.0, muted = true, sourceIn = 9.0),
                createClip(music, it.tracks[2].id, 1.0, it.fps).copy(duration = 4.0, volume = 0.5, audioFadeOut = 1.0),
            )
        }
        val built = assertNotNull(buildComposition(p))
        // Gaps are items too: gap, shot, still, shot / gap, music, gap.
        assertEquals(listOf(4, 3), built.composition.sequences.map { it.editedMediaItems.size })
        assertEquals(setOf(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_AUDIO), built.composition.sequences[0].trackTypes)
        loadIntoPlayer(built.composition)
    }

    @Test
    fun `audio-only and overlapping edits build too`() {
        loadIntoPlayer(assertNotNull(buildComposition(project { listOf(createClip(music, it.tracks[2].id, 0.0, it.fps).copy(duration = 3.0)) })).composition)
        val overlap = project {
            listOf(createClip(video, it.tracks[1].id, 0.0, it.fps).copy(duration = 4.0), createClip(video, it.tracks[1].id, 2.0, it.fps).copy(duration = 4.0))
        }
        loadIntoPlayer(assertNotNull(buildComposition(overlap)).composition)
    }

    @Test
    fun `a project with only text has nothing for the player`() {
        assertNull(buildComposition(project { listOf(createClip(null, it.tracks[0].id, 0.0, it.fps)) }))
    }

    private fun loadIntoPlayer(composition: androidx.media3.transformer.Composition) {
        val player = CompositionPlayer.Builder(RuntimeEnvironment.getApplication())
            .setVideoGraphFactory(MultipleInputVideoGraph.Factory())
            .build()
        try {
            player.setComposition(composition)
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(player.playerError)
        } finally {
            player.release()
        }
    }
}
