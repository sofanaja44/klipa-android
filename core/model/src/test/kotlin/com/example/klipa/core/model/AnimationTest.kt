package com.example.klipa.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnimationTest {
    @Test
    fun `evaluates very narrow easing windows without numerical cancellation`() {
        val clip = fixture().clips[0].let { c ->
            c.copy(keyframes = listOf(c.keyframes[0], c.keyframes[1].copy(easingWindow = EasingWindow(1 - 1e-12, 1.0))))
        }
        val value = evaluateKeyframes(clip, 4.0).transform.x
        assertTrue(value.isFinite())
        assertTrue(value > 0)
        assertTrue(value < 800)
    }

    @Test
    fun `uses destination easing and last duplicate timestamp consistently`() {
        val clip = fixture().clips[0]
        assertEquals(125.0, evaluateKeyframes(clip, 2.0).transform.x)
        val duplicate = Keyframe(id = "duplicate", property = KeyframeProperty.X, time = 8.0, value = 1600.0, easing = Easing.Linear)
        assertEquals(400.0, evaluateKeyframes(clip.copy(keyframes = clip.keyframes + duplicate), 2.0).transform.x)
    }

    @Test
    fun `holds the first and last key outside the animated range`() {
        val clip = fixture().clips[0].let { c -> c.copy(keyframes = c.keyframes.map { it.copy(time = it.time / 2 + 2) }) }
        assertEquals(0.0, evaluateKeyframes(clip, 1.0).transform.x)
        assertEquals(800.0, evaluateKeyframes(clip, 7.0).transform.x)
        assertEquals(0.0, evaluateKeyframes(clip, Double.NaN).transform.x)
    }

    @Test
    fun `leaves properties without keyframes untouched`() {
        val clip = fixture().clips[0].copy(volume = 0.5)
        val evaluated = evaluateKeyframes(clip, 3.0)
        assertEquals(0.5, evaluated.volume)
        assertEquals(clip.transform.copy(x = evaluated.transform.x), evaluated.transform)
        assertNull(evaluateKeyframe(clip, KeyframeProperty.Opacity, 3.0))
    }

    @Test
    fun `fades multiply the incoming and outgoing ramps`() {
        val clip = fixture().clips[0] // 8 s long, video fades 2 s in and 3 s out
        assertEquals(0.0, evaluateFade(clip, 0.0, FadeChannel.Video))
        assertEquals(0.5, evaluateFade(clip, 1.0, FadeChannel.Video))
        assertEquals(1.0, evaluateFade(clip, 4.0, FadeChannel.Video))
        assertEquals(0.5, evaluateFade(clip, 6.5, FadeChannel.Video))
        assertEquals(1.0, evaluateFade(clip.copy(fadeIn = 0.0, fadeOut = 0.0), 0.0, FadeChannel.Video))
        assertEquals(0.5, evaluateFade(clip, 0.5, FadeChannel.Audio))
    }
}
