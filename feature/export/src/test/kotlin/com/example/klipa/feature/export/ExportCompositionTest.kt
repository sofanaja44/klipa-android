// Transformer's composition export is marked unstable; Klipa pins the Media3 version.
@file:OptIn(UnstableApi::class)

package com.example.klipa.feature.export

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.Transformer
import com.example.klipa.core.media.VideoLayering
import com.example.klipa.core.model.MediaKind
import com.example.klipa.core.model.Project
import com.example.klipa.core.model.TextStyle
import com.example.klipa.core.model.createClip
import com.example.klipa.core.model.createProject
import com.example.klipa.core.model.testAsset
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Builds real export Compositions and hands them to Transformer, so Media3's structural
 * checks run on the JVM. Decoding, GL and encoding need a device.
 */
@RunWith(RobolectricTestRunner::class)
class ExportCompositionTest {
    // File URIs: Robolectric's media provider knows no content URIs, and Transformer asks
    // it whether each item is a photo.
    private val video = testAsset
    private val music = testAsset.copy(id = "music", path = "/tmp/music.m4a", url = "file:///tmp/music.m4a", kind = MediaKind.Audio, width = 0, height = 0, fps = 0.0)

    /** A shot on the video track, a title over it and music under it. Tracks: text, video, audio. */
    private fun project(asset: com.example.klipa.core.model.MediaAsset = video): Project {
        val base = createProject().copy(assets = listOf(asset, music))
        return base.copy(
            clips = listOf(
                createClip(asset, base.tracks[1].id, 0.0, base.fps).copy(duration = 5.0),
                createClip(null, base.tracks[0].id, 1.0, base.fps).copy(duration = 2.0, text = TextStyle(text = "Lisbon")),
                createClip(music, base.tracks[2].id, 0.0, base.fps).copy(duration = 6.0, audioFadeOut = 2.0),
            ),
        )
    }

    @Test
    fun `one video track exports through the single-input path with its text drawn over it`() {
        val built = assertNotNull(buildExportComposition(project(), ExportSettings(1080)))
        assertEquals(VideoLayering.BaseLayer, built.layering)
        assertEquals(listOf(setOf(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_AUDIO), setOf(C.TRACK_TYPE_AUDIO)), built.composition.sequences.map { it.trackTypes })
        assertIs<OverlayEffect>(built.composition.effects.videoEffects.single())
        assertEquals(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL, built.composition.hdrMode)
    }

    @Test
    fun `a lower resolution scales the finished frame, text included`() {
        val effects = assertNotNull(buildExportComposition(project(), ExportSettings(720))).composition.effects.videoEffects
        assertIs<OverlayEffect>(effects[0])
        val scale = assertIs<Presentation>(effects[1])
        assertEquals(Size(1280, 720), scale.configure(1920, 1080))
    }

    @Test
    fun `media faster than the project is thinned to its frame rate before anything else`() {
        val fast = video.copy(fps = 60.0)
        val effects = assertNotNull(buildExportComposition(project(fast), ExportSettings(1080))).composition.effects.videoEffects
        assertIs<FrameDropEffect>(effects.first())
    }

    @Test
    fun `several video tracks are composited`() {
        val p = project().let { base ->
            val overlay = base.tracks[1].copy(id = "overlay", name = "Video 2")
            base.copy(
                tracks = listOf(base.tracks[0], overlay) + base.tracks.drop(1),
                clips = base.clips + createClip(video, "overlay", 1.0, base.fps).copy(duration = 2.0),
            )
        }
        val built = assertNotNull(buildExportComposition(p, ExportSettings(1080)))
        assertEquals(VideoLayering.Layered, built.layering)
        assertEquals(2, built.plan.videoSequences.size)
    }

    @Test
    fun `projects without a picture have nothing to export`() {
        val base = createProject().copy(assets = listOf(music))
        val textOnly = base.copy(clips = listOf(createClip(null, base.tracks[0].id, 0.0, base.fps).copy(text = TextStyle())))
        assertNull(buildExportComposition(textOnly, ExportSettings(1080)))
        val audioOnly = base.copy(clips = listOf(createClip(music, base.tracks[2].id, 0.0, base.fps)))
        assertNull(buildExportComposition(audioOnly, ExportSettings(1080)))
    }

    @Test
    fun `Transformer accepts the export composition`() {
        val context = RuntimeEnvironment.getApplication()
        for (p in listOf(project(), project(video.copy(fps = 60.0)))) {
            val built = assertNotNull(buildExportComposition(p, ExportSettings(720)))
            val transformer = Transformer.Builder(context).build()
            transformer.start(built.composition, File(context.cacheDir, "export-test.mp4").path)
            transformer.cancel()
        }
    }
}
