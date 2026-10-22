package com.example.klipa.core.edit

import com.example.klipa.core.model.ChromaKey
import com.example.klipa.core.model.CropRect
import com.example.klipa.core.model.FadeChannel
import com.example.klipa.core.model.FadeWindow
import com.example.klipa.core.model.MaskShape
import com.example.klipa.core.model.ShapeMask
import com.example.klipa.core.model.assertClose
import com.example.klipa.core.model.end
import com.example.klipa.core.model.evaluateFade
import com.example.klipa.core.model.evaluateKeyframes
import com.example.klipa.core.model.fixture
import com.example.klipa.core.model.steps
import com.example.klipa.core.model.validateProject
import com.example.klipa.core.model.withFirstClip
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

// Ported from the "timeline semantics" suite of ReaCut tests/model.test.ts.

class ClipEditsTest {
    @Test
    fun `splits at one-frame boundaries despite floating-point subtraction`() {
        for (fps in listOf(24, 25, 30, 50, 60)) {
            for (start in listOf(2.0, 123.0, 80000.0)) {
                val p = fixture().copy(fps = fps).withFirstClip { it.copy(start = start) }
                val original = p.clips[0]
                for (at in listOf(start + 1.0 / fps, start + original.duration - 1.0 / fps)) {
                    val next = splitClip(p, original.id, at)
                    assertEquals(2, next.clips.size)
                    assertClose(1.0 / fps, next.clips.minOf { it.duration }, 8)
                    assertClose(original.duration, next.clips[0].duration + next.clips[1].duration, 8)
                    assertClose(original.sourceIn + next.clips[0].duration * original.speed, next.clips[1].sourceIn, 8)
                    validateProject(next)
                }
                assertSame(p, splitClip(p, original.id, start))
                assertSame(p, splitClip(p, original.id, start + original.duration))
            }
        }
    }

    @Test
    fun `split keeps source ranges and exact animation across both clips`() {
        val p = fixture()
        val original = p.clips[0]
        val next = splitClip(p, original.id, 5.0)
        val (left, right) = next.clips
        assertEquals(3.0, left.duration)
        assertEquals(8.0, right.sourceIn)
        assertEquals(5.0, right.duration)
        assertEquals(0.0, right.fadeIn)
        assertEquals(0.0, left.fadeOut)
        for (t in steps(0.0, 8.0, 0.125)) {
            val value = if (t <= 3) evaluateKeyframes(left, t) else evaluateKeyframes(right, t - 3)
            assertClose(evaluateKeyframes(original, t).transform.x, value.transform.x, 8)
        }
        validateProject(next)
    }

    @Test
    fun `trim retains the exact easing curve, including after repeated trims and split`() {
        val p = fixture()
        val original = p.clips[0]
        var next = trimClip(p, original.id, TrimEdge.Start, 4.0)
        next = trimClip(next, original.id, TrimEdge.End, 8.0)
        for (t in steps(0.0, 4.0, 0.125)) {
            assertClose(evaluateKeyframes(original, t + 2).transform.x, evaluateKeyframes(next.clips[0], t).transform.x, 8)
        }
        next = splitClip(next, original.id, 6.0)
        for (t in steps(0.0, 4.0, 0.125)) {
            val value = if (t <= 2) evaluateKeyframes(next.clips[0], t) else evaluateKeyframes(next.clips[1], t - 2)
            assertClose(evaluateKeyframes(original, t + 2).transform.x, value.transform.x, 8)
        }
        validateProject(next)
    }

    @Test
    fun `split inside overlapping video and audio fades preserves the original envelope on both sides`() {
        val p = fixture().withFirstClip { it.copy(fadeIn = 6.0, fadeOut = 7.0, audioFadeIn = 7.0, audioFadeOut = 6.0) }
        val c = p.clips[0]
        val next = splitClip(p, c.id, c.start + 3)
        for (channel in FadeChannel.entries) {
            for (t in steps(0.0, 8.0, 0.125)) {
                val value = if (t <= 3) evaluateFade(next.clips[0], t, channel) else evaluateFade(next.clips[1], t - 3, channel)
                assertClose(evaluateFade(c, t, channel), value, 12)
            }
        }
        validateProject(next)
    }

    @Test
    fun `repeated trims and split preserve fading while speed rescales all envelope times`() {
        val p = fixture().withFirstClip { it.copy(fadeIn = 6.0, fadeOut = 7.0, audioFadeIn = 7.0, audioFadeOut = 6.0) }
        val c = p.clips[0]
        var next = trimClip(p, c.id, TrimEdge.Start, 4.0)
        next = trimClip(next, c.id, TrimEdge.End, 8.0)
        next = changeClipSpeed(next, c.id, 4.0)
        val part = next.clips[0]
        for (channel in FadeChannel.entries) {
            for (t in steps(0.0, 2.0, 0.0625)) assertClose(evaluateFade(c, t * 2 + 2, channel), evaluateFade(part, t, channel), 12)
        }
        assertEquals(FadeWindow(offset = 1.0, duration = 4.0, fadeIn = 3.0, fadeOut = 3.5), part.videoFadeWindow)
        validateProject(next)
    }

    @Test
    fun `extending the start keeps a negative fade window and delays the original fade onset`() {
        val p = fixture().withFirstClip { it.copy(fadeIn = 4.0) }
        val c = p.clips[0]
        val next = trimClip(p, c.id, TrimEdge.Start, 1.0).clips[0]
        assertEquals(-1.0, next.videoFadeWindow?.offset)
        assertEquals(0.0, evaluateFade(next, 0.5, FadeChannel.Video))
        for (t in steps(0.0, 8.0, 0.125)) {
            assertClose(evaluateFade(c, t, FadeChannel.Video), evaluateFade(next, t + 1, FadeChannel.Video), 12)
        }
        validateProject(p.copy(clips = listOf(next)))
    }

    @Test
    fun `speed preserves source extent and scales keyframes and fades`() {
        val p = fixture()
        val c = p.clips[0]
        val next = changeClipSpeed(p, c.id, 4.0).clips[0]
        assertEquals(c.duration * c.speed, next.duration * next.speed)
        assertEquals(4.0, next.keyframes[1].time)
        assertEquals(1.5, next.fadeOut)
        assertEquals(1.0, next.audioFadeOut)
        for (speed in listOf(0.09, 8.01, Double.NaN, c.speed)) assertSame(p, changeClipSpeed(p, c.id, speed))
    }

    @Test
    fun `clamps all fades when trimming and rejects invalid times`() {
        val p = fixture()
        val c = p.clips[0]
        val trimmed = trimClip(p, c.id, TrimEdge.End, 3.0).clips[0]
        for (fade in listOf(trimmed.fadeIn, trimmed.fadeOut, trimmed.audioFadeIn, trimmed.audioFadeOut)) assertTrue(fade <= 1)
        assertSame(p, splitClip(p, c.id, Double.NaN))
        assertSame(p, trimClip(p, c.id, TrimEdge.Start, Double.POSITIVE_INFINITY))
    }

    @Test
    fun `trims clamp to the source handles`() {
        val p = fixture() // starts at 2 with 2 s of source before it at 2× speed, 20 s source
        val c = p.clips[0]
        assertEquals(1.0, trimClip(p, c.id, TrimEdge.Start, 0.0).clips[0].start)
        assertEquals(0.0, trimClip(p, c.id, TrimEdge.Start, 0.0).clips[0].sourceIn)
        assertEquals(11.0, trimClip(p, c.id, TrimEdge.End, 30.0).clips[0].end)
        assertEquals(1.0 / 30, trimClip(p, c.id, TrimEdge.End, 0.0).clips[0].duration)
        assertSame(p, trimClip(p, c.id, TrimEdge.End, c.start + c.duration))
    }

    @Test
    fun `respects track locks in all model edits and minimum frame split`() {
        val unlocked = fixture()
        val c = unlocked.clips[0]
        val locked = unlocked.copy(tracks = unlocked.tracks.mapIndexed { i, t -> if (i == 1) t.copy(locked = true) else t })
        assertSame(locked, splitClip(locked, c.id, 5.0))
        assertSame(locked, trimClip(locked, c.id, TrimEdge.End, 5.0))
        assertSame(locked, changeClipSpeed(locked, c.id, 4.0))
        assertSame(unlocked, splitClip(unlocked, c.id, c.start))
        assertSame(unlocked, splitClip(unlocked, c.id, c.start + c.duration))
    }

    @Test
    fun `retains source-space visual settings across split, trim and speed`() {
        val crop = CropRect(0.1, 0.2, 0.8, 0.7)
        val chroma = ChromaKey(enabled = true, color = "#00ff00", similarity = 0.15, blend = 0.1)
        val mask = ShapeMask(shape = MaskShape.Ellipse, width = 0.8, height = 0.7, feather = 0.1, invert = false)
        val p = fixture().withFirstClip { it.copy(crop = crop, chromaKey = chroma, mask = mask) }
        validateProject(p)
        val c = p.clips[0]
        for (next in listOf(splitClip(p, c.id, 5.0), trimClip(p, c.id, TrimEdge.Start, 4.0), changeClipSpeed(p, c.id, 4.0))) {
            for (clip in next.clips) {
                assertEquals(crop, clip.crop)
                assertEquals(chroma, clip.chromaKey)
                assertEquals(mask, clip.mask)
            }
            validateProject(next)
        }
    }
}
