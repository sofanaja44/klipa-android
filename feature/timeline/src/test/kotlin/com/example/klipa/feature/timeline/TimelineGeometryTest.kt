package com.example.klipa.feature.timeline

import com.example.klipa.core.edit.TrimEdge
import com.example.klipa.core.model.createProject
import com.example.klipa.core.model.fixture
import kotlin.test.Test
import kotlin.test.assertEquals

class TimelineGeometryTest {
    private val heights = LaneHeights(video = 50f, audio = 40f, text = 30f, gap = 4f)

    @Test
    fun `viewport keeps the playhead fixed and maps time both ways`() {
        val viewport = TimelineViewport(playhead = 10.0, pxPerSecond = 50f, playheadX = 200f)
        assertEquals(200f, viewport.xOf(10.0))
        assertEquals(250f, viewport.xOf(11.0))
        assertEquals(9.0, viewport.timeAt(150f))
        assertEquals(0.5, viewport.secondsFor(25f))
    }

    @Test
    fun `ruler picks the finest readable step and frame-based steps when zoomed in`() {
        assertEquals(RulerScale(2.0, 10), rulerScale(60f, 30, minMajorPx = 72f, minMinorPx = 10f))
        assertEquals(RulerScale(10.0 / 30, 10), rulerScale(400f, 30, minMajorPx = 72f, minMinorPx = 10f))
        assertEquals(RulerScale(60.0, 5), rulerScale(1.5f, 30, minMajorPx = 72f, minMinorPx = 10f))
        assertEquals(3600.0, rulerScale(0.001f, 30, minMajorPx = 72f, minMinorPx = 10f).major)
    }

    @Test
    fun `ruler ticks start at zero and mark labeled ticks`() {
        val ticks = rulerTicks(RulerScale(1.0, 2), from = -3.0, to = 1.6)
        assertEquals(listOf(0.0 to true, 0.5 to false, 1.0 to true, 1.5 to false, 2.0 to true), ticks)
    }

    @Test
    fun `ruler labels show frames only below a second`() {
        assertEquals("1:05", rulerLabel(65.0, 30, 1.0))
        assertEquals("1:02:05", rulerLabel(3725.0, 30, 10.0))
        assertEquals("0:01:15", rulerLabel(1.5, 30, 0.5))
    }

    @Test
    fun `lanes stack in track order with kind heights`() {
        val lanes = layoutLanes(createProject().tracks, heights)
        assertEquals(listOf(4f, 38f, 92f), lanes.map { it.top })
        assertEquals(listOf(30f, 50f, 40f), lanes.map { it.height })
        assertEquals(136f, lanesHeight(lanes, heights))
    }

    @Test
    fun `hit test finds clip bodies, selected clip handles and empty lanes`() {
        val project = fixture() // one clip on the video track from 2 s to 10 s
        val clip = project.clips[0]
        val lanes = layoutLanes(project.tracks, heights)
        val video = lanes[1]
        val viewport = TimelineViewport(playhead = 0.0, pxPerSecond = 10f, playheadX = 0f)
        fun hit(x: Float, y: Float = video.centerY, selected: List<String> = emptyList()) =
            hitTest(x, y, project, lanes, viewport, selected, handlePx = 12f)
        assertEquals(TimelineHit.ClipBody(clip.id), hit(50f))
        assertEquals(TimelineHit.ClipBody(clip.id), hit(21f))
        assertEquals(TimelineHit.ClipEdge(clip.id, TrimEdge.Start), hit(21f, selected = listOf(clip.id)))
        assertEquals(TimelineHit.ClipEdge(clip.id, TrimEdge.End), hit(103f, selected = listOf(clip.id)))
        assertEquals(TimelineHit.EmptyLane(video.track.id), hit(150f))
        assertEquals(TimelineHit.EmptyLane(lanes[0].track.id), hit(50f, y = lanes[0].centerY))
        assertEquals(TimelineHit.Nothing, hit(50f, y = 1000f))
        assertEquals(TimelineHit.Nothing, hit(50f, y = video.top - 1f))
    }

    @Test
    fun `snapping prefers the nearest target within the threshold`() {
        val targets = listOf(0.0, 2.0, 5.0)
        assertEquals(2.0, snap(2.08, targets, threshold = 0.1))
        assertEquals(2.2, snap(2.2, targets, threshold = 0.1))
        val project = fixture()
        val clip = project.clips[0] // 2 s to 10 s
        assertEquals(-2.0, snapMove(listOf(clip), -1.95, targets, threshold = 0.1))
        assertEquals(-5.0, snapMove(listOf(clip), -5.04, targets, threshold = 0.1))
        assertEquals(0.5, snapMove(listOf(clip), 0.5, targets, threshold = 0.1))
        assertEquals(listOf(0.0, 3.0, 2.0, 10.0), snapTargets(project, 3.0, emptySet()))
        assertEquals(listOf(0.0, 3.0), snapTargets(project, 3.0, setOf(clip.id)))
    }

    @Test
    fun `formats speed and duration compactly`() {
        assertEquals("2×", formatSpeed(2.0))
        assertEquals("0.5×", formatSpeed(0.5))
        assertEquals("1.25×", formatSpeed(1.25))
        assertEquals("2.4s", formatDuration(2.4))
        assertEquals("1:05", formatDuration(65.2))
    }
}
