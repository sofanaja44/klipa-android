package com.example.klipa.core.edit

import com.example.klipa.core.model.KeyframeProperty
import com.example.klipa.core.model.evaluateKeyframes
import com.example.klipa.core.model.fixture
import com.example.klipa.core.model.validateProject
import com.example.klipa.core.model.withFirstClip
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class TransformEditsTest {
    @Test
    fun `moves, scales and turns a clip`() {
        val p = fixture().withFirstClip { it.copy(keyframes = emptyList()) }
        val clip = p.clips[0]
        val moved = transformClip(p, clip.id, dx = 120.0, dy = -40.0, scale = 0.5, rotation = 15.0).clips[0].transform
        assertEquals(120.0, moved.x)
        assertEquals(-40.0, moved.y)
        assertEquals(0.5, moved.scale)
        assertEquals(15.0, moved.rotation)
        assertEquals(clip.transform.opacity, moved.opacity)
    }

    @Test
    fun `keyframed properties move their whole animation`() {
        // The fixture animates x from 0 to 800 over 8 s.
        val p = fixture()
        val clip = p.clips[0]
        val before = evaluateKeyframes(clip, 3.0).transform.x
        val moved = transformClip(p, clip.id, dx = 100.0).clips[0]
        assertEquals(listOf(100.0, 900.0), moved.keyframes.filter { it.property == KeyframeProperty.X }.map { it.value })
        assertEquals(before + 100, evaluateKeyframes(moved, 3.0).transform.x, 1e-9)
        validateProject(transformClip(p, clip.id, dx = 100.0))
    }

    @Test
    fun `scale stays within ReaCut's bounds`() {
        val p = fixture()
        val id = p.clips[0].id
        assertEquals(10.0, transformClip(p, id, scale = 50.0).clips[0].transform.scale)
        assertEquals(0.000001, transformClip(p, id, scale = 1e-9).clips[0].transform.scale)
    }

    @Test
    fun `nothing moves on a locked track or for an empty gesture`() {
        val p = fixture()
        val id = p.clips[0].id
        assertSame(p, transformClip(p, id))
        assertSame(p, transformClip(p, id, scale = 0.0))
        val locked = p.copy(tracks = p.tracks.map { it.copy(locked = true) })
        assertSame(locked, transformClip(locked, id, dx = 10.0))
    }
}
