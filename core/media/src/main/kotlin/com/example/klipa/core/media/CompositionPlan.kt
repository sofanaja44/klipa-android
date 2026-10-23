package com.example.klipa.core.media

import com.example.klipa.core.model.Adjustments
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

    /**
     * A clip. [removeVideo] drops its picture (audio-only sequences). Sound is never
     * removed from an item, because Media3 can stall on sequences whose items all drop
     * their audio (androidx/media#2854); [muted] items play at zero gain instead.
     */
    data class Item(
        val clip: Clip,
        val asset: MediaAsset,
        override val startUs: Long,
        override val durationUs: Long,
        val removeVideo: Boolean,
        val muted: Boolean,
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

    /** This sequence's sound alone: pictures are dropped and silent media become gaps. */
    fun audioOnly(): PlannedSequence = copy(
        hasVideo = false,
        segments = segments.map { segment ->
            when {
                segment !is Segment.Item -> segment
                segment.asset.carriesSound() -> segment.copy(removeVideo = true)
                else -> Segment.Gap(segment.startUs, segment.durationUs)
            }
        },
    )
}

/**
 * Sequences in compositing order: video sequences first, top layer at index 0 (Media3's
 * compositor draws input 0 last), then audio-only sequences.
 */
data class CompositionPlan(val sequences: List<PlannedSequence>, val durationUs: Long) {
    val videoSequences: List<PlannedSequence> get() = sequences.filter { it.hasVideo }
    val isEmpty: Boolean get() = sequences.isEmpty()

    /**
     * The plan with only the bottom video layer kept as picture; the layers above it
     * keep their sound. Used where only one video sequence can be played.
     */
    fun baseLayerOnly(): CompositionPlan {
        val base = videoSequences.lastOrNull() ?: return this
        val (video, audio) = sequences.map { if (it.hasVideo && it !== base) it.audioOnly() else it }
            .filter { it.hasVideo || it.hasAudio }
            .partition { it.hasVideo }
        return copy(sequences = video + audio)
    }
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
        val showsPicture = track.kind != TrackKind.Audio && !track.hidden
        val lanes = splitOverlaps(clips)
        // Later lanes hold clips that start over earlier ones, so they are stacked above.
        for (lane in lanes.asReversed()) {
            val audible = !track.muted && lane.any { (clip, asset) -> asset.carriesSound() && !clip.muted }
            if (!showsPicture && !audible) continue
            // A sequence declares audio when any of its media has sound, even if muted,
            // so its items' contents always match the sequence's track types.
            val hasAudio = if (showsPicture) lane.any { (_, asset) -> asset.carriesSound() } else true
            val segments = mutableListOf<Segment>()
            var cursor = 0L
            for ((clip, asset) in lane) {
                val start = secondsToUs(clip.start)
                val end = secondsToUs(clip.end)
                if (start > cursor) segments += Segment.Gap(cursor, start - cursor)
                val muted = track.muted || clip.muted || !asset.carriesSound()
                segments += if (!showsPicture && !asset.carriesSound()) {
                    Segment.Gap(start, end - start)
                } else {
                    Segment.Item(clip, asset, start, end - start, removeVideo = !showsPicture, muted = muted)
                }
                cursor = end
            }
            if (cursor < durationUs) segments += Segment.Gap(cursor, durationUs - cursor)
            val sequence = PlannedSequence(track.id, showsPicture, hasAudio, segments)
            if (showsPicture) video += sequence else audio += sequence
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

/**
 * The parts of a project that change what the player plays. Names, timestamps, derived
 * metadata such as waveforms, and text clips (drawn over the player, not by it) are left
 * out, so typing a title or finishing a waveform does not reload playback. Look values
 * and volume are read live (see [LiveProject]): only whether a clip has a look, whether it
 * cuts out, its blur bucket and whether its volume is at unity count, so dragging a color
 * or volume slider does not reload.
 */
fun playbackSignature(project: Project): Project = project.copy(
    name = "",
    createdAt = "",
    updatedAt = "",
    assets = project.assets.map { it.copy(name = "", waveform = null, thumbnail = null, previewUrl = null, previewError = null) },
    clips = project.clips.filter { it.text == null }.map { clip ->
        val look = Look.of(clip)
        clip.copy(
            name = "",
            // A gain stage exists only away from unity volume.
            volume = if (clip.volume == 1.0) 1.0 else 0.0,
            adjustments = Adjustments(
                brightness = if (look.isNeutral) 0.0 else 1.0,
                temperature = if (look.cutsOut) 1.0 else 0.0,
                blur = blurBucket(clip.adjustments.blur).toDouble(),
            ),
            chromaKey = null,
            mask = null,
        )
    },
)
