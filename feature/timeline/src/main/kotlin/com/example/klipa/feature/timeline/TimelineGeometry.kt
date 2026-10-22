package com.example.klipa.feature.timeline

import com.example.klipa.core.edit.TrimEdge
import com.example.klipa.core.model.Clip
import com.example.klipa.core.model.Project
import com.example.klipa.core.model.Track
import com.example.klipa.core.model.TrackKind
import com.example.klipa.core.model.end
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

// Pure layout math for the timeline, kept free of Compose so it can be unit tested.
// All positions are in pixels; times are timeline seconds.

/**
 * Maps time to x. The playhead stays fixed at [playheadX] and the timeline moves under
 * it, as in mobile editors: scrubbing is scrolling.
 */
data class TimelineViewport(val playhead: Double, val pxPerSecond: Float, val playheadX: Float) {
    fun xOf(time: Double): Float = playheadX + ((time - playhead) * pxPerSecond).toFloat()

    fun timeAt(x: Float): Double = playhead + (x - playheadX) / pxPerSecond

    fun secondsFor(px: Float): Double = (px / pxPerSecond).toDouble()
}

/** Ruler spacing: a labeled tick every [major] seconds, split into [minorPerMajor] parts. */
data class RulerScale(val major: Double, val minorPerMajor: Int)

private val MajorSteps = listOf(0.5, 1.0, 2.0, 5.0, 10.0, 15.0, 30.0, 60.0, 120.0, 300.0, 600.0, 900.0, 1800.0, 3600.0)

/**
 * The finest ruler whose labeled ticks are at least [minMajorPx] apart. Zoomed in far
 * enough, ticks fall on whole frames.
 */
fun rulerScale(pxPerSecond: Float, fps: Int, minMajorPx: Float, minMinorPx: Float): RulerScale {
    val frameSteps = listOf(1, 2, 5, 10).filter { it < fps }.map { it.toDouble() / fps }
    val major = (frameSteps + MajorSteps).firstOrNull { it * pxPerSecond >= minMajorPx } ?: MajorSteps.last()
    val majorFrames = major * fps
    val minor = listOf(10, 5, 4, 2).firstOrNull { parts ->
        major / parts * pxPerSecond >= minMinorPx &&
            // Below a second, only split on whole frames.
            (major >= 1 || abs(majorFrames / parts - Math.round(majorFrames / parts)) < 1e-9)
    } ?: 1
    return RulerScale(major, minor)
}

/** Tick times in [from]..[to] for [scale], with whether each one is labeled. */
fun rulerTicks(scale: RulerScale, from: Double, to: Double): List<Pair<Double, Boolean>> {
    val step = scale.major / scale.minorPerMajor
    val first = maxOf(0L, floor(from / step).toLong())
    val last = ceil(to / step).toLong()
    if (last < first) return emptyList()
    return (first..last).map { i -> i * step to (i % scale.minorPerMajor == 0L) }
}

/** `m:ss` for whole-second ticks, `m:ss:ff` below a second; hours when needed. */
fun rulerLabel(time: Double, fps: Int, major: Double): String {
    val frames = Math.round(time * fps)
    val totalSeconds = frames / fps
    val clock = if (totalSeconds >= 3600) {
        String.format(Locale.ROOT, "%d:%02d:%02d", totalSeconds / 3600, totalSeconds / 60 % 60, totalSeconds % 60)
    } else {
        String.format(Locale.ROOT, "%d:%02d", totalSeconds / 60, totalSeconds % 60)
    }
    return if (major >= 1) clock else String.format(Locale.ROOT, "%s:%02d", clock, frames % fps)
}

/** One track's horizontal band, relative to the top of the track area. */
data class Lane(val track: Track, val top: Float, val height: Float) {
    val bottom: Float get() = top + height
    val centerY: Float get() = top + height / 2
}

/** Lane heights in px for each track kind. */
data class LaneHeights(val video: Float, val audio: Float, val text: Float, val gap: Float) {
    fun of(kind: TrackKind): Float = when (kind) {
        TrackKind.Video, TrackKind.Image -> video
        TrackKind.Audio -> audio
        TrackKind.Text -> text
    }
}

/** Lanes top to bottom in project track order (the first track is the top layer). */
fun layoutLanes(tracks: List<Track>, heights: LaneHeights): List<Lane> {
    var y = heights.gap
    return tracks.map { track ->
        val lane = Lane(track, y, heights.of(track.kind))
        y += lane.height + heights.gap
        lane
    }
}

fun lanesHeight(lanes: List<Lane>, heights: LaneHeights): Float = (lanes.lastOrNull()?.bottom ?: 0f) + heights.gap

sealed interface TimelineHit {
    data class ClipBody(val clipId: String) : TimelineHit

    /** A trim handle of a selected clip. */
    data class ClipEdge(val clipId: String, val edge: TrimEdge) : TimelineHit

    data class EmptyLane(val trackId: String) : TimelineHit

    data object Nothing : TimelineHit
}

/**
 * What is under ([x], [y]) in the track area. Selected clips expose trim handles of
 * [handlePx] on each edge, reaching half a handle outside the clip so short clips stay
 * grabbable. Upper layers win where clips overlap.
 */
fun hitTest(
    x: Float,
    y: Float,
    project: Project,
    lanes: List<Lane>,
    viewport: TimelineViewport,
    selectedIds: Collection<String>,
    handlePx: Float,
): TimelineHit {
    val lane = lanes.firstOrNull { y >= it.top && y < it.bottom } ?: return TimelineHit.Nothing
    val clips = project.clips.filter { it.trackId == lane.track.id }
    for (clip in clips.filter { it.id in selectedIds }) {
        val left = viewport.xOf(clip.start)
        val right = viewport.xOf(clip.end)
        val reach = minOf(handlePx, (right - left) / 2)
        if (x >= left - handlePx / 2 && x <= left + reach) return TimelineHit.ClipEdge(clip.id, TrimEdge.Start)
        if (x <= right + handlePx / 2 && x >= right - reach) return TimelineHit.ClipEdge(clip.id, TrimEdge.End)
    }
    val time = viewport.timeAt(x)
    // Later clips in the list are drawn over earlier ones on the same track.
    val clip = clips.lastOrNull { time >= it.start && time < it.end }
    return if (clip != null) TimelineHit.ClipBody(clip.id) else TimelineHit.EmptyLane(lane.track.id)
}

/** Times an edge or the playhead can snap to: 0, the playhead, and every other clip's edges. */
fun snapTargets(project: Project, playhead: Double, exclude: Set<String>): List<Double> =
    buildList {
        add(0.0)
        add(playhead)
        for (clip in project.clips) if (clip.id !in exclude) {
            add(clip.start)
            add(clip.end)
        }
    }

/** [time] moved onto the nearest target within [threshold] seconds, or unchanged. */
fun snap(time: Double, targets: List<Double>, threshold: Double): Double {
    val nearest = targets.minByOrNull { abs(it - time) } ?: return time
    return if (abs(nearest - time) <= threshold) nearest else time
}

/**
 * Snaps a move of [clips] by [delta] so that the earliest start or latest end lands on a
 * target, whichever is closer. Returns the adjusted delta.
 */
fun snapMove(clips: List<Clip>, delta: Double, targets: List<Double>, threshold: Double): Double {
    if (clips.isEmpty()) return delta
    val start = clips.minOf { it.start } + delta
    val end = clips.maxOf { it.end } + delta
    val startSnap = snap(start, targets, threshold) - start
    val endSnap = snap(end, targets, threshold) - end
    val correction = when {
        startSnap == 0.0 -> endSnap
        endSnap == 0.0 -> startSnap
        else -> if (abs(startSnap) <= abs(endSnap)) startSnap else endSnap
    }
    return delta + correction
}
