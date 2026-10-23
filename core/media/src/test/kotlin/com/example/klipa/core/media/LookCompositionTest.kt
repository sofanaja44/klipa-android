// Media3's effect APIs are marked unstable; Klipa pins the Media3 version.
@file:OptIn(UnstableApi::class)

package com.example.klipa.core.media

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.audio.AudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GaussianFunction
import androidx.media3.effect.Presentation
import com.example.klipa.core.model.Adjustments
import com.example.klipa.core.model.ChromaKey
import com.example.klipa.core.model.Clip
import com.example.klipa.core.model.Project
import com.example.klipa.core.model.createClip
import com.example.klipa.core.model.createProject
import com.example.klipa.core.model.testAsset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Which look effects each clip gets, in which order, and that they follow live edits. */
@RunWith(RobolectricTestRunner::class)
class LookCompositionTest {
    private val green = ChromaKey(enabled = true, color = "#00ff00", similarity = 0.1, blend = 0.05)

    /** A clip on the video track, optionally with an overlay clip on a second video track. */
    private fun project(clip: (Clip) -> Clip, overlay: Boolean = false): Project {
        val base = createProject().copy(assets = listOf(testAsset))
        val main = clip(createClip(testAsset, base.tracks[1].id, 0.0, base.fps).copy(duration = 4.0))
        if (!overlay) return base.copy(clips = listOf(main))
        val track = base.tracks[1].copy(id = "overlay", name = "Video 2")
        return base.copy(
            tracks = listOf(base.tracks[0], track) + base.tracks.drop(1),
            clips = listOf(createClip(testAsset, base.tracks[1].id, 0.0, base.fps).copy(duration = 4.0), main.copy(trackId = "overlay")),
        )
    }

    private fun effectsOf(p: Project, clipId: String, live: LiveProject? = null): List<Effect> {
        val built = assertNotNull(buildComposition(p, live = live))
        val index = built.plan.sequences.indexOfFirst { sequence -> sequence.segments.any { it is Segment.Item && it.clip.id == clipId } }
        val segment = built.plan.sequences[index].segments.indexOfFirst { it is Segment.Item && it.clip.id == clipId }
        return built.composition.sequences[index].editedMediaItems[segment].effects.videoEffects
    }

    @Test
    fun `clips without a look get no extra pass`() {
        val p = project({ it })
        assertTrue(effectsOf(p, p.clips.single().id).none { it is LookEffect || it is LiveBlur })
    }

    @Test
    fun `a single layer draws cut-out parts over black, then blurs and fits`() {
        val p = project({ it.copy(chromaKey = green, adjustments = Adjustments(blur = 10.0)) })
        val effects = effectsOf(p, p.clips.single().id)
        assertEquals(LookOutput.OverBlack, assertIs<LookEffect>(effects[0]).output)
        assertIs<LiveBlur>(effects[1])
        assertIs<Presentation>(effects[2])
    }

    @Test
    fun `a composited layer blurs premultiplied and hands straight alpha to the compositor`() {
        val p = project({ it.copy(chromaKey = green, adjustments = Adjustments(blur = 10.0)) }, overlay = true)
        val effects = effectsOf(p, p.clips.last().id)
        assertEquals(LookOutput.Premultiplied, assertIs<LookEffect>(effects[0]).output)
        assertIs<LiveBlur>(effects[1])
        assertEquals(UnpremultiplyEffect, effects[2])
        assertIs<Presentation>(effects[3])
        // Without blur, straight alpha goes to the compositor directly.
        val sharp = project({ it.copy(chromaKey = green) }, overlay = true)
        assertEquals(LookOutput.Straight, assertIs<LookEffect>(effectsOf(sharp, sharp.clips.last().id)[0]).output)
    }

    @Test
    fun `looks and blur follow live edits`() {
        val p = project({ it.copy(adjustments = Adjustments(saturation = 0.5, blur = 3.0)) })
        val clip = p.clips.single()
        val live = LiveProject(p)
        val effects = effectsOf(p, clip.id, live)
        val look = assertIs<LookEffect>(effects[0])
        val blur = assertIs<LiveBlur>(effects[1])
        assertEquals(0.5, look.look().saturation)
        // The source fills the 1920 × 1080 project, so a blur of 3 project pixels is 3 source pixels.
        assertEquals(GaussianFunction(3f, 3f), blur.getConvolution(0))
        // Bucket (2, 4] keeps 4-pixel blurs sharp: output at full size.
        assertEquals(1f, blur.scale)

        live.update(p.copy(clips = listOf(clip.copy(adjustments = Adjustments(saturation = 1.8, blur = 4.0)))))
        assertEquals(1.8, look.look().saturation)
        assertEquals(GaussianFunction(4f, 3f), blur.getConvolution(0))
    }

    @Test
    fun `volume follows live edits`() {
        val p = project({ it.copy(volume = 0.5) })
        val clip = p.clips.single()
        val live = LiveProject(p)
        val built = assertNotNull(buildComposition(p, live = live))
        val gain = assertIs<GainAudioProcessor>(built.composition.sequences[0].editedMediaItems.single().effects.audioProcessors.single())
        gain.configure(AudioProcessor.AudioFormat(44_100, 1, C.ENCODING_PCM_16BIT))
        gain.flush(AudioProcessor.StreamMetadata.DEFAULT)
        fun level(): Short {
            val input = ByteBuffer.allocateDirect(2).order(ByteOrder.nativeOrder()).putShort(10_000)
            input.flip()
            gain.queueInput(input)
            return gain.output.order(ByteOrder.nativeOrder()).short
        }
        assertEquals(5_000, level().toInt())
        live.update(p.copy(clips = listOf(clip.copy(volume = 1.5))))
        assertEquals(15_000, level().toInt())
    }

    @Test
    fun `wide blurs run at a lower resolution`() {
        val p = project({ it.copy(adjustments = Adjustments(blur = 100.0)) })
        val blur = assertIs<LiveBlur>(effectsOf(p, p.clips.single().id)[0])
        assertEquals(1f / 16, blur.scale)
        assertEquals(GaussianFunction(100f, 3f), blur.getConvolution(0))
        // A 4K source fitted into 1080p: 100 project pixels are 200 source pixels.
        val uhd = testAsset.copy(width = 3840, height = 2160)
        val p4k = project({ it.copy(adjustments = Adjustments(blur = 100.0)) }).let { it.copy(assets = listOf(uhd)) }
        assertEquals(GaussianFunction(200f, 3f), assertIs<LiveBlur>(effectsOf(p4k, p4k.clips.single().id)[0]).getConvolution(0))
    }
}
