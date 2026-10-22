package com.example.klipa.core.model

import kotlin.test.Test
import kotlin.test.assertEquals

class TimingTest {
    @Test
    fun `snaps to the nearest frame with ties toward positive infinity`() {
        assertEquals(8.0 / 60, snapToFrame(0.125, 60))
        assertEquals(-7.0 / 60, snapToFrame(-0.125, 60))
        assertEquals(2.0, snapToFrame(2.01, 30))
        assertEquals(5.0 / 24, snapToFrame(0.2, 24))
        assertEquals(Double.POSITIVE_INFINITY, snapToFrame(Double.POSITIVE_INFINITY, 30))
    }

    @Test
    fun `formats timecode as hours minutes seconds frames`() {
        assertEquals("00:00:00:00", formatTime(0.0))
        assertEquals("01:02:03:15", formatTime(3723.5, 30))
        assertEquals("00:00:01:00", formatTime(0.999, 25))
        assertEquals("00:00:00:00", formatTime(-3.0))
    }

    @Test
    fun `project duration is the end of the last clip`() {
        assertEquals(0.0, projectDuration(createProject()))
        assertEquals(10.0, projectDuration(fixture()))
    }

    @Test
    fun `new clips span their source and snap their start`() {
        val project = createProject()
        val video = createClip(testAsset, project.tracks[1].id, 1.01, 30)
        assertEquals(1.0, video.start)
        assertEquals(20.0, video.duration)
        assertEquals(5.0, createClip(testAsset.copy(kind = MediaKind.Image), project.tracks[1].id, 0.0, 30).duration)
        val text = createClip(null, project.tracks[0].id, -4.0, 30)
        assertEquals(0.0, text.start)
        assertEquals(TextStyle(), text.text)
    }
}
