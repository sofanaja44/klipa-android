package com.example.klipa.core.media

import com.example.klipa.core.model.Clip
import com.example.klipa.core.model.MediaAsset
import com.example.klipa.core.model.MediaKind
import com.example.klipa.core.model.Project
import com.example.klipa.core.model.TrackKind
import com.example.klipa.core.model.end
import kotlin.math.roundToLong

// How a project maps onto Media3's model, decided without touching Media3 so it can be
// unit tested. A Composition plays sequences in parallel; within a sequence, items play
// back to back. So each track becomes a sequence with explicit gaps between its clips.

/** One stretch of a sequence: a clip, or silence/transparency. */
sealed interface Segment {
    val startUs: Long
    val durationUs: Long
    val endUs: Long get() = startUs + durationUs

    data class Gap(override val startUs: Long, override val durationUs: Long) : Segment

    data class Item(
        val clip: Clip,
        val asset: MediaAsset,
        override val startUs: Long,
        override val durationUs: Long,
        val removeVideo: Boolean,
        val removeAudio: Boolean,
    ) : Segment
}

/**
 * A planned sequence. [hasVideo] sequences are composited as layers; [hasAudio] ones are
 * mixed. Every sequence spans the whole composition.
 */
data class PlannedSequence(
    val trackId: String,
    val hasVideo: Boolean,
    val hasAudio: Boolean,
    val segments: List<Segment>,
) {
    /** The clip playing at [timeUs], if any. */
    fun itemAt(timeUs: Long): Segment.Item? =
        segments.firstOrNull { it is Segment.Item && timeUs >= it.startUs && timeUs < it.endUs } as Segment.Item?
}

/**
 * Sequences in compositing order: video sequences first, top layer at index 0 (Media3's
 * compositor draws input 0 last), then audio-only sequences.
 */
data class CompositionPlan(val sequences: List<PlannedSequence>, val durationUs: Long) {
    val videoSequences: List<PlannedSequence> get() = sequences.filter { it.hasVideo }
    val isEmpty: Boolean get() = sequences.isEmpty()
}

fun secondsToUs(seconds: Double): Long = (seconds * 1_000_000).roundToLong()

/**
 * Plans the composition for [project]. Text tracks are drawn separately and are not part
 * of it; hidden tracks lose their picture and muted tracks their sound. Clips whose media
 * is missing play as gaps. Clips that overlap on one track go to extra sequences stacked
 * above it, so the later clip stays on top as on the timeline.
 */
fun planComposition(project: Project): CompositionPlan {
    val assets = project.assets.associateBy { it.id }
    val durationUs = project.clips.maxOfOrNull { secondsToUs(it.end) } ?: 0L
    val video = mutableListOf<PlannedSequence>()
    val audio = mutableListOf<PlannedSequence>()
    for (track in project.tracks) {
        if (track.kind == TrackKind.Text) continue
        val clips = project.clips
            .filter { it.trackId == track.id }
            .mapNotNull { clip -> assets[clip.assetId]?.takeIf { it.missing != true }?.let { clip to it } }
            .sortedBy { it.first.start }
        if (clips.isEmpty()) continue
        val isAudioTrack = track.kind == TrackKind.Audio
        val sequenceHasVideo = !isAudioTrack && !track.hidden
        val lanes = splitOverlaps(clips)
        // Later lanes hold clips that start over earlier ones, so they are stacked above.
        for (lane in lanes.asReversed()) {
            val contributesAudio = !track.muted && lane.any { (clip, asset) -> asset.carriesSound() && !clip.muted }
            if (!sequenceHasVideo && !contributesAudio) continue
            val segments = mutableListOf<Segment>()
            var cursor = 0L
            for ((clip, asset) in lane) {
                val start = secondsToUs(clip.start)
                val end = secondsToUs(clip.end)
                if (start > cursor) segments += Segment.Gap(cursor, start - cursor)
                val removeAudio = !contributesAudio || clip.muted || !asset.carriesSound()
                segments += if (!sequenceHasVideo && removeAudio) {
                    Segment.Gap(start, end - start)
                } else {
                    Segment.Item(clip, asset, start, end - start, removeVideo = !sequenceHasVideo || asset.kind == MediaKind.Audio, removeAudio = removeAudio)
                }
                cursor = end
            }
            if (cursor < durationUs) segments += Segment.Gap(cursor, durationUs - cursor)
            val sequence = PlannedSequence(track.id, sequenceHasVideo, contributesAudio, segments)
            if (sequenceHasVideo) video += sequence else audio += sequence
        }
    }
    return CompositionPlan(video + audio, durationUs)
}

private fun MediaAsset.carriesSound(): Boolean = hasAudio && kind != MediaKind.Image

/** Greedy interval partitioning: each lane holds clips that do not overlap. */
private fun splitOverlaps(clips: List<Pair<Clip, MediaAsset>>): List<List<Pair<Clip, MediaAsset>>> {
    val lanes = mutableListOf<MutableList<Pair<Clip, MediaAsset>>>()
    for (entry in clips) {
        val startUs = secondsToUs(entry.first.start)
        val lane = lanes.firstOrNull { secondsToUs(it.last().first.end) <= startUs }
        if (lane != null) lane += entry else lanes += mutableListOf(entry)
    }
    return lanes
}
