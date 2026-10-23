package com.example.klipa.core.edit

import com.example.klipa.core.model.Clip
import com.example.klipa.core.model.MediaAsset
import com.example.klipa.core.model.MediaKind
import com.example.klipa.core.model.Project
import com.example.klipa.core.model.Track
import com.example.klipa.core.model.TrackKind
import com.example.klipa.core.model.accepts
import com.example.klipa.core.model.createClip
import com.example.klipa.core.model.createTrack
import com.example.klipa.core.model.end
import com.example.klipa.core.model.newId
import com.example.klipa.core.model.snapToFrame
import com.example.klipa.core.model.trackKind
import kotlin.math.abs

// Editing actions of ReaCut's editor store. Each one commits a single undo entry, or
// returns the state unchanged when the edit does not apply (locked track, bad input).

/** A subtitle cue in timeline seconds. */
data class SubtitleCue(val start: Double, val end: Double, val text: String)

enum class TrackDirection { Up, Down }

private fun Project.isEditable(clip: Clip): Boolean = tracks.any { it.id == clip.trackId && !it.locked }

private fun Project.accepts(track: Track, clip: Clip): Boolean =
    if (clip.text != null) track.kind == TrackKind.Text else assets.any { it.id == clip.assetId && track.kind.accepts(it.kind) }

/** A copy with fresh clip and keyframe ids and no link. */
private fun Clip.freshCopy(): Clip = copy(id = newId(), linkId = null, keyframes = keyframes.map { it.copy(id = newId()) })

/** Copies of a linked group share one new link id, distinct from the original's. */
private fun copyLink(group: String?, copies: MutableMap<String, String>): String? =
    group?.let { copies.getOrPut(it) { newId() } }

fun EditorState.updateProject(change: (Project) -> Project): EditorState = commit(change(project))

/** Adds imported media, skipping files or ids already in the project. One undo entry. */
fun EditorState.addAssets(assets: List<MediaAsset>): EditorState {
    val paths = project.assets.mapTo(HashSet()) { it.path }
    val ids = project.assets.mapTo(HashSet()) { it.id }
    val fresh = assets.filter { asset ->
        if (asset.path in paths || asset.id in ids) return@filter false
        paths += asset.path
        ids += asset.id
        true
    }
    return if (fresh.isEmpty()) this else commit(project.copy(assets = project.assets + fresh))
}

/** @throws EditRejectedException if a clip still uses the asset. */
fun EditorState.removeAsset(assetId: String): EditorState {
    if (project.clips.any { it.assetId == assetId }) throw EditRejectedException(EditRejectedException.Reason.AssetInUse)
    return commit(project.copy(assets = project.assets.filter { it.id != assetId }))
}

/**
 * Adds an asset as a new clip and selects it. Without a [start] the clip goes after the
 * last clip on its track; without a [trackId] it goes on the first unlocked compatible
 * track, or a new one. An explicit track that is locked or incompatible is never swapped
 * for another: nothing happens.
 *
 * @throws EditRejectedException if [range] is invalid for the asset.
 */
fun EditorState.addAssetClip(
    assetId: String,
    start: Double? = null,
    trackId: String? = null,
    range: SourceRange? = null,
): EditorState {
    var p = project
    val asset = p.assets.find { it.id == assetId }
    if (asset == null || (start != null && !start.isFinite())) return this
    if (range != null && (
            asset.missing == true || !range.sourceIn.isFinite() || !range.duration.isFinite() ||
                range.sourceIn < 0 || range.duration < 1.0 / p.fps - 1e-9 || range.duration > 86400 ||
                (if (asset.kind == MediaKind.Image) range.sourceIn != 0.0 else range.sourceIn + range.duration > asset.duration + 1e-9)
            )
    ) throw EditRejectedException(EditRejectedException.Reason.InvalidSourceRange)
    var track = if (trackId != null) {
        p.tracks.find { it.id == trackId && !it.locked && it.kind.accepts(asset.kind) } ?: return this
    } else {
        p.tracks.find { !it.locked && it.kind.accepts(asset.kind) }
    }
    if (track == null) {
        track = createTrack(asset.kind.trackKind)
        p = p.copy(tracks = p.tracks + track)
    }
    val trackEnd = p.clips.filter { it.trackId == track.id }.fold(0.0) { end, c -> maxOf(end, c.end) }
    var clip = createClip(asset, track.id, start ?: trackEnd, p.fps)
    if (range != null) clip = clip.copy(sourceIn = range.sourceIn, duration = range.duration)
    return commit(p.copy(clips = p.clips + clip)).copy(selectedIds = listOf(clip.id), playhead = clip.start)
}

/** [placeAsset] at the playhead; selects the new clip. */
fun EditorState.placeAsset(assetId: String, trackId: String?, range: SourceRange, mode: PlacementMode): EditorState {
    val result = placeAsset(project, assetId, trackId, playhead, range, mode)
    return commit(result.project).copy(selectedIds = listOf(result.clipId))
}

/** The size overlays start at: a picture-in-picture half the frame. */
const val OVERLAY_SCALE = 0.5

/**
 * Places media as overlays at the playhead, one after another, each [SourceRange] from
 * its start: on the lowest video track above the main (bottom) one that is free for their
 * whole span, or on a new video track added above the others. Overlays start centered at
 * [OVERLAY_SCALE] and the last one is selected, ready to arrange on the monitor. One undo
 * entry.
 *
 * @throws EditRejectedException if a range is invalid.
 */
fun EditorState.placeOverlays(media: List<Pair<String, SourceRange>>): EditorState {
    if (media.isEmpty()) return this
    val from = playhead
    val to = from + media.sumOf { it.second.duration }
    var p = project
    val videoTracks = p.tracks.filter { it.kind == TrackKind.Video }
    val free = videoTracks.dropLast(1).lastOrNull { track ->
        !track.locked && p.clips.none { it.trackId == track.id && it.start < to - 1e-8 && it.end > from + 1e-8 }
    }
    val track = free ?: createTrack(TrackKind.Video, "Video ${videoTracks.size + 1}").also { added ->
        val top = p.tracks.indexOfFirst { it.kind == TrackKind.Video }.coerceAtLeast(0)
        p = p.copy(tracks = p.tracks.toMutableList().apply { add(top, added) })
    }
    var time = from
    var last = ""
    for ((assetId, range) in media) {
        val placed = placeAsset(p, assetId, track.id, time, range, PlacementMode.Overlay)
        p = transformClip(placed.project, placed.clipId, scale = OVERLAY_SCALE)
        last = placed.clipId
        time = p.clips.first { it.id == last }.end
    }
    return commit(p).copy(selectedIds = listOf(last))
}

/**
 * Moves a video clip's sound to a linked audio clip and selects the pair.
 *
 * @throws EditRejectedException if the clip has no sound, is locked or is already linked.
 */
fun EditorState.detachAudio(clipId: String): EditorState {
    val detached = detachLinkedAudio(project, clipId)
    return commit(detached.project).copy(selectedIds = linkedIds(detached.project, listOf(clipId)))
}

fun EditorState.unlinkSelected(): EditorState {
    val next = unlinkClips(project, selectedIds)
    if (next === project) return this
    return commit(next).copy(selectedIds = selectedIds.take(1))
}

/** Adds a default text clip at the playhead on the first unlocked text track (created on top if needed). */
fun EditorState.addText(): EditorState {
    var p = project
    var track = p.tracks.find { it.kind == TrackKind.Text && !it.locked }
    if (track == null) {
        track = createTrack(TrackKind.Text)
        p = p.copy(tracks = listOf(track) + p.tracks)
    }
    val clip = createClip(null, track.id, playhead, p.fps)
    return commit(p.copy(clips = p.clips + clip)).copy(selectedIds = listOf(clip.id))
}

/** Adds cues as text clips on a new subtitle track; invalid cues are skipped. */
fun EditorState.addSubtitles(cues: List<SubtitleCue>): EditorState {
    val p = project
    val track = createTrack(TrackKind.Text, "Subtitles")
    val clips = cues
        .filter { it.start.isFinite() && it.end.isFinite() && it.start >= 0 && it.end > it.start }
        .map { cue ->
            val clip = createClip(null, track.id, cue.start, p.fps)
            clip.copy(
                name = cue.text,
                duration = maxOf(1.0 / p.fps, snapToFrame(cue.end, p.fps) - clip.start),
                transform = clip.transform.copy(y = p.height * 0.36),
                text = clip.text!!.copy(text = cue.text, fontSize = p.height * 0.05),
            )
        }
    if (clips.isEmpty()) return this
    return commit(p.copy(tracks = listOf(track) + p.tracks, clips = p.clips + clips))
        .copy(selectedIds = listOf(clips.first().id))
}

/**
 * Applies an inspector change to one clip. Timing changes go through the linked edits so
 * partners stay in sync: `start` moves, `sourceIn` slips and `speed` re-times the whole
 * group. Changing a fade length drops that channel's cut envelope. A `trackId` change must
 * name an unlocked track that accepts the clip, or the whole change is refused. The id,
 * link and asset of a clip cannot be changed here.
 */
fun EditorState.updateClip(clipId: String, change: (Clip) -> Clip): EditorState = commit(patchClip(project, clipId, change))

/** [updateClip] for every selected clip, as one undo entry. */
fun EditorState.updateSelected(change: (Clip) -> Clip): EditorState {
    var p = project
    for (clipId in selectedIds) p = patchClip(p, clipId, change)
    return commit(p)
}

private fun patchClip(project: Project, clipId: String, change: (Clip) -> Clip): Project {
    val original = project.clips.find { it.id == clipId }
    if (original == null || !project.isEditable(original)) return project
    val changed = change(original)
    if (changed.trackId != original.trackId) {
        val target = project.tracks.find { it.id == changed.trackId && !it.locked }
        if (target == null || !project.accepts(target, changed)) return project
    }
    var p = project
    if (changed.speed != original.speed) p = speedLinked(p, clipId, changed.speed)
    if (changed.start != original.start) p = moveLinked(p, listOf(clipId), changed.start - original.start)
    if (changed.sourceIn != original.sourceIn) p = slipLinked(p, clipId, changed.sourceIn)
    val videoFadeChanged = changed.fadeIn != original.fadeIn || changed.fadeOut != original.fadeOut
    val audioFadeChanged = changed.audioFadeIn != original.audioFadeIn || changed.audioFadeOut != original.audioFadeOut
    return p.copy(
        clips = p.clips.map { current ->
            if (current.id != clipId) return@map current
            // Fields the change left alone keep their current value, which a linked edit above may have updated.
            fun <T> pick(field: (Clip) -> T): T = if (field(changed) != field(original)) field(changed) else field(current)
            current.copy(
                trackId = pick { it.trackId },
                name = pick { it.name },
                duration = pick { it.duration },
                volume = pick { it.volume },
                muted = pick { it.muted },
                transform = pick { it.transform },
                adjustments = pick { it.adjustments },
                text = pick { it.text },
                fadeIn = pick { it.fadeIn },
                fadeOut = pick { it.fadeOut },
                audioFadeIn = pick { it.audioFadeIn },
                audioFadeOut = pick { it.audioFadeOut },
                videoFadeWindow = if (videoFadeChanged) null else pick { it.videoFadeWindow },
                audioFadeWindow = if (audioFadeChanged) null else pick { it.audioFadeWindow },
                keyframes = pick { it.keyframes },
                crop = pick { it.crop },
                chromaKey = pick { it.chromaKey },
                mask = pick { it.mask },
            )
        },
    )
}

fun EditorState.moveClips(ids: List<String>, delta: Double, trackId: String? = null): EditorState =
    commit(moveLinked(project, ids, delta, trackId))

fun EditorState.trim(clipId: String, edge: TrimEdge, time: Double): EditorState =
    commit(trimLinked(project, clipId, edge, time))

/** Splits the selection at the playhead, or every clip under the playhead when nothing is selected. */
fun EditorState.split(): EditorState {
    val ids = selectedIds.ifEmpty {
        project.clips.filter { it.start < playhead && it.end > playhead }.map { it.id }
    }
    return commit(splitLinked(project, ids, playhead))
}

/**
 * Deletes the selection and its linked partners. With [EditorState.magnetic] on, later
 * clips on each affected track move left by the deleted time (overlapping deletions
 * count once).
 *
 * @throws EditRejectedException if closing the gaps would shift linked clips by
 *   different amounts.
 */
fun EditorState.deleteSelected(): EditorState {
    val p = project
    val selected = linkedIds(p, selectedIds).toSet()
    val removed = p.clips.filter { it.id in selected }
    if (removed.isEmpty() || removed.any { !p.isEditable(it) }) return this
    var clips = p.clips.filter { it.id !in selected }
    if (magnetic) {
        val gaps = HashMap<String, MutableList<DoubleArray>>()
        for (deleted in removed.sortedBy { it.start }) {
            val list = gaps.getOrPut(deleted.trackId) { mutableListOf() }
            val last = list.lastOrNull()
            if (last != null && deleted.start <= last[1]) last[1] = maxOf(last[1], deleted.end)
            else list += doubleArrayOf(deleted.start, deleted.end)
        }
        clips = clips.map { c ->
            val shift = gaps[c.trackId].orEmpty().sumOf { (start, end) -> if (c.start >= end) end - start else 0.0 }
            c.copy(start = maxOf(0.0, c.start - shift))
        }
        val offsets = HashMap<String, Double>()
        for (c in clips) {
            val link = c.linkId ?: continue
            val delta = c.start - p.clips.first { it.id == c.id }.start
            val previous = offsets.put(link, delta)
            if (previous != null && abs(previous - delta) > 1e-8) {
                throw EditRejectedException(EditRejectedException.Reason.MagneticDeleteDesync)
            }
        }
    }
    return commit(p.copy(clips = clips)).copy(selectedIds = selectedIds.filter { it !in selected })
}

/** Duplicates the selection (with partners) right after each clip, and selects the copies. */
fun EditorState.duplicate(): EditorState {
    val selected = linkedIds(project, selectedIds).toSet()
    val originals = project.clips.filter { it.id in selected }
    if (originals.isEmpty() || originals.any { !project.isEditable(it) }) return this
    val groups = HashMap<String, String>()
    val copies = originals.map { it.freshCopy().copy(linkId = copyLink(it.linkId, groups), start = it.end) }
    return commit(project.copy(clips = project.clips + copies)).copy(selectedIds = copies.map { it.id })
}

fun EditorState.copySelection(): EditorState = copy(clipboard = project.clips.filter { it.id in selectedIds })

/**
 * Pastes the clipboard at the playhead, keeping the clips' relative timing and tracks.
 * Clips whose track is gone, locked or incompatible are skipped, but a linked group is
 * pasted whole or not at all, so sound never silently loses its picture.
 */
fun EditorState.paste(): EditorState {
    if (clipboard.isEmpty()) return this
    val groups = HashMap<String, String>()
    val earliest = clipboard.minOf { it.start }
    val clips = clipboard
        .filter { c -> project.tracks.any { it.id == c.trackId && !it.locked && project.accepts(it, c) } }
        .map { c ->
            c.freshCopy().copy(
                linkId = copyLink(c.linkId, groups),
                start = maxOf(0.0, snapToFrame(playhead + c.start - earliest, project.fps)),
            )
        }
    if (clips.isEmpty()) return this
    for (group in clipboard.mapNotNullTo(HashSet()) { it.linkId }) {
        val originalCount = clipboard.count { it.linkId == group }
        val pastedCount = groups[group]?.let { pasted -> clips.count { it.linkId == pasted } } ?: 0
        if (pastedCount != originalCount) return this
    }
    return commit(project.copy(clips = project.clips + clips)).copy(selectedIds = clips.map { it.id })
}

/** Adds a numbered track: audio tracks go at the bottom, others on top. */
fun EditorState.addTrack(kind: TrackKind): EditorState {
    val label = when (kind) {
        TrackKind.Video -> "Video"
        TrackKind.Image -> "Image"
        TrackKind.Audio -> "Audio"
        TrackKind.Text -> "Text"
    }
    val track = createTrack(kind, "$label ${project.tracks.count { it.kind == kind } + 1}")
    val tracks = if (kind == TrackKind.Audio) project.tracks + track else listOf(track) + project.tracks
    return commit(project.copy(tracks = tracks))
}

/** Deletes an unlocked track with its clips; the media stays in the project. */
fun EditorState.deleteTrack(trackId: String): EditorState {
    val track = project.tracks.find { it.id == trackId }
    if (track == null || track.locked) return this
    val removed = project.clips.filter { it.trackId == trackId }.mapTo(HashSet()) { it.id }
    return commit(project.copy(tracks = project.tracks - track, clips = project.clips.filter { it.trackId != trackId }))
        .copy(selectedIds = selectedIds.filter { it !in removed })
}

/** Swaps a track with its neighbor; refused at the ends or when either track is locked. */
fun EditorState.reorderTrack(trackId: String, direction: TrackDirection): EditorState {
    val tracks = project.tracks
    val index = tracks.indexOfFirst { it.id == trackId }
    val target = index + if (direction == TrackDirection.Up) -1 else 1
    if (index < 0 || target !in tracks.indices || tracks[index].locked || tracks[target].locked) return this
    val reordered = tracks.toMutableList().apply {
        this[index] = tracks[target]
        this[target] = tracks[index]
    }
    return commit(project.copy(tracks = reordered))
}

/**
 * Changes a track's name or flags. Names are trimmed; blank names and renaming a locked
 * track are ignored, but a locked track can still be unlocked, muted or hidden. The id and
 * kind never change.
 */
fun EditorState.updateTrack(trackId: String, change: (Track) -> Track): EditorState {
    val track = project.tracks.find { it.id == trackId } ?: return this
    val changed = change(track)
    val name = if (changed.name == track.name || track.locked || changed.name.isBlank()) track.name else changed.name.trim()
    val next = changed.copy(id = track.id, kind = track.kind, name = name)
    return commit(project.copy(tracks = project.tracks.map { if (it.id == trackId) next else it }))
}
