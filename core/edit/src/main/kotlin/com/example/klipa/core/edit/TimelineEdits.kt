package com.example.klipa.core.edit

import com.example.klipa.core.model.Clip
import com.example.klipa.core.model.KeyframeProperty
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
import com.example.klipa.core.model.validateProject
import kotlin.math.floor

// Timeline edits that keep linked clips (a video and its detached audio) in sync, ported
// from ReaCut src/core/timelineEdits.ts. An edit that would touch a clip on a locked track
// changes nothing: it returns the same project, or throws EditRejectedException.

/** How [placeAsset] treats clips already on the timeline. */
enum class PlacementMode {
    /** Ripple: everything at or after the insert point, on every track, moves later. */
    Insert,

    /** Cut the destination track's clips (and their linked partners) under the new clip. */
    Overwrite,

    /** Place without touching existing clips. */
    Overlay,
}

/** A range of a source asset, in source seconds. */
data class SourceRange(val sourceIn: Double, val duration: Double)

data class PlacedClip(val project: Project, val clipId: String)

data class DetachedAudio(val project: Project, val audioClipId: String)

class EditRejectedException(val reason: Reason) : IllegalStateException(reason.message) {
    enum class Reason(val message: String) {
        NoAudioToDetach("Select a video clip that has sound"),
        TrackLocked("Unlock the track first"),
        AlreadyLinked("This clip is already linked; unlink it first"),
        InvalidSourceRange("The source range is invalid or outside the source media"),
        IncompatibleTrack("The target track is locked or does not accept this media"),
        TouchesLockedTrack("The edit would change clips on a locked track; unlock those tracks first"),
    }
}

// A clip whose track is missing is treated as locked.
private fun Project.isLocked(clip: Clip): Boolean = tracks.find { it.id == clip.trackId }?.locked ?: true

/** Whether [clip] may live on [track]: text on text tracks, media on tracks that accept its kind. */
private fun Project.accepts(track: Track, clip: Clip): Boolean =
    if (clip.text != null) {
        track.kind == TrackKind.Text
    } else {
        assets.any { it.id == clip.assetId && track.kind.accepts(it.kind) }
    }

/** [ids] plus every clip linked to one of them. The given ids come first, in order. */
fun linkedIds(project: Project, ids: List<String>): List<String> {
    val selected = ids.toSet()
    val groups = project.clips.filter { it.id in selected && it.linkId != null }.mapTo(HashSet()) { it.linkId }
    return (ids + project.clips.filter { it.linkId != null && it.linkId in groups }.map { it.id }).distinct()
}

fun unlinkClips(project: Project, ids: List<String>): Project {
    val members = linkedIds(project, ids).toSet()
    if (project.clips.any { it.id in members && project.isLocked(it) }) return project
    return project.copy(clips = project.clips.map { if (it.id in members) it.copy(linkId = null) else it })
}

/**
 * Splits a video clip's sound onto an audio track as a linked clip and mutes the video.
 * The audio clip gets a new audio asset pointing at the same file, and keeps the video's
 * volume keyframes.
 */
fun detachLinkedAudio(project: Project, clipId: String): DetachedAudio {
    val video = project.clips.find { it.id == clipId }
    val asset = video?.assetId?.let { id -> project.assets.find { it.id == id } }
    if (video == null || asset == null || asset.kind != MediaKind.Video || !asset.hasAudio) {
        throw EditRejectedException(EditRejectedException.Reason.NoAudioToDetach)
    }
    if (project.isLocked(video)) throw EditRejectedException(EditRejectedException.Reason.TrackLocked)
    if (video.linkId != null) throw EditRejectedException(EditRejectedException.Reason.AlreadyLinked)
    val audio = asset.copy(
        id = newId(),
        kind = MediaKind.Audio,
        name = asset.name + " · Audio",
        width = 0,
        height = 0,
        thumbnail = null,
    )
    val existingTrack = project.tracks.find { it.kind == TrackKind.Audio && !it.locked }
    val track = existingTrack ?: createTrack(TrackKind.Audio, "Detached audio")
    val linkId = newId()
    val clip = video.copy(
        id = newId(),
        assetId = audio.id,
        trackId = track.id,
        name = audio.name,
        linkId = linkId,
        keyframes = video.keyframes.filter { it.property == KeyframeProperty.Volume }.map { it.copy(id = newId()) },
    )
    return DetachedAudio(
        audioClipId = clip.id,
        project = project.copy(
            assets = project.assets + audio,
            tracks = if (existingTrack != null) project.tracks else project.tracks + track,
            clips = project.clips.map { if (it.id == video.id) it.copy(muted = true, linkId = linkId) else it } + clip,
        ),
    )
}

/**
 * Trims [edge] of a clip and its linked partners by the same amount. The requested delta
 * is clamped once against every member's source handles, so the group stays aligned.
 */
fun trimLinked(project: Project, clipId: String, edge: TrimEdge, time: Double): Project {
    val primary = project.clips.find { it.id == clipId }
    if (primary == null || !time.isFinite()) return project
    val ids = linkedIds(project, listOf(clipId)).toSet()
    val members = project.clips.filter { it.id in ids }
    if (members.any { project.isLocked(it) }) return project
    fun edgeAt(clip: Clip) = if (edge == TrimEdge.Start) clip.start else clip.end
    val requested = snapToFrame(time, project.fps) - edgeAt(primary)
    var delta = requested
    for (c in members) {
        val next = trimClip(project, c.id, edge, edgeAt(c) + requested).clips.first { it.id == c.id }
        val allowed = edgeAt(next) - edgeAt(c)
        delta = if (requested > 0) minOf(delta, allowed) else maxOf(delta, allowed)
    }
    var result = project
    for (c in members) result = trimClip(result, c.id, edge, edgeAt(c) + delta)
    return result
}

/** Splits clips and their partners at [time]. Each split group becomes independent left and right groups. */
fun splitLinked(project: Project, ids: List<String>, time: Double): Project {
    val selected = linkedIds(project, ids).toSet()
    val members = project.clips.filter { it.id in selected }
    if (members.any { project.isLocked(it) }) return project
    val originalIds = project.clips.mapTo(HashSet()) { it.id }
    val rightGroups = HashMap<String, String>()
    var result = project
    for (c in members) {
        val next = splitClip(result, c.id, time)
        val link = c.linkId
        if (link != null && next !== result) {
            val group = rightGroups.getOrPut(link) { newId() }
            result = next.copy(
                clips = next.clips.map { if (it.id !in originalIds && it.linkId == link) it.copy(linkId = group) else it },
            )
            result.clips.mapTo(originalIds) { it.id }
        } else {
            result = next
        }
    }
    return result
}

/** The part of [clip] inside [from]..[to], cut the same way a manual trim would; null if under a frame. */
private fun retain(project: Project, clip: Clip, from: Double, to: Double): Clip? {
    if (to - from < 1.0 / project.fps - 1e-8) return null
    var part = project.copy(clips = listOf(clip))
    if (to < clip.end - 1e-8) part = trimClip(part, clip.id, TrimEdge.End, to)
    if (from > clip.start + 1e-8) part = trimClip(part, clip.id, TrimEdge.Start, from)
    return part.clips[0]
}

/**
 * Places [range] of an asset on a track at [time]. Without a [trackId] the first unlocked
 * compatible track is used, or a new one is added. The result is validated.
 *
 * @throws EditRejectedException if the range or destination is invalid, or the edit
 *   would change a clip on a locked track.
 */
fun placeAsset(
    project: Project,
    assetId: String,
    trackId: String?,
    time: Double,
    range: SourceRange,
    mode: PlacementMode,
): PlacedClip {
    var p = project
    val asset = p.assets.find { it.id == assetId }
    if (asset == null || asset.missing == true || !time.isFinite() || time < 0 ||
        !range.sourceIn.isFinite() || !range.duration.isFinite() || range.sourceIn < 0 ||
        range.duration < 1.0 / p.fps - 1e-9 || range.duration > 86400 ||
        (if (asset.kind == MediaKind.Image) range.sourceIn != 0.0 else range.sourceIn + range.duration > asset.duration + 1e-8)
    ) throw EditRejectedException(EditRejectedException.Reason.InvalidSourceRange)
    var track = if (trackId != null) {
        p.tracks.find { it.id == trackId }
    } else {
        p.tracks.find { it.kind.accepts(asset.kind) && !it.locked }
    }
    if (trackId != null && (track == null || track.locked || !track.kind.accepts(asset.kind))) {
        throw EditRejectedException(EditRejectedException.Reason.IncompatibleTrack)
    }
    if (track == null) {
        track = createTrack(asset.kind.trackKind)
        p = p.copy(tracks = p.tracks + track)
    }
    val trackIdValue = track.id
    // Do not extend the selected source range to round a fractional final frame up.
    val clip = createClip(asset, trackIdValue, time, p.fps).copy(
        sourceIn = range.sourceIn,
        duration = floor((range.duration + 1e-9) * p.fps) / p.fps,
    )
    val from = clip.start
    val to = clip.end
    val targetIds = linkedIds(
        p,
        p.clips.filter { it.trackId == trackIdValue && it.start < to - 1e-8 && it.end > from + 1e-8 }.map { it.id },
    ).toSet()
    fun affected(c: Clip) = when (mode) {
        PlacementMode.Insert -> c.end > from + 1e-8
        PlacementMode.Overwrite -> c.id in targetIds
        PlacementMode.Overlay -> false
    }
    if (p.clips.any { affected(it) && p.isLocked(it) }) {
        throw EditRejectedException(EditRejectedException.Reason.TouchesLockedTrack)
    }
    val rightGroups = HashMap<String, String>()
    val base = p
    val clips = p.clips.flatMap { c ->
        if (!affected(c)) return@flatMap listOf(c)
        if (mode == PlacementMode.Insert && c.start >= from - 1e-8) return@flatMap listOf(c.copy(start = c.start + clip.duration))
        val left = retain(base, c, c.start, minOf(from, c.end))
        var right = retain(base, c, maxOf(if (mode == PlacementMode.Insert) from else to, c.start), c.end)
        if (right != null) {
            if (left != null) right = right.copy(id = newId(), keyframes = right.keyframes.map { it.copy(id = newId()) })
            right = right.copy(linkId = c.linkId?.let { link -> rightGroups.getOrPut(link) { newId() } })
            if (mode == PlacementMode.Insert) right = right.copy(start = right.start + clip.duration)
        }
        listOfNotNull(left, right)
    }
    return PlacedClip(validateProject(p.copy(clips = clips + clip)), clip.id)
}

/**
 * Moves clips and their partners by [delta] (snapped to a frame, never before 0). With a
 * [trackId], each clip that the track accepts moves onto it; partners keep their tracks.
 */
fun moveLinked(project: Project, ids: List<String>, delta: Double, trackId: String? = null): Project {
    if (!delta.isFinite()) return project
    val selected = linkedIds(project, ids).toSet()
    val members = project.clips.filter { it.id in selected }
    if (members.isEmpty() || members.any { project.isLocked(it) }) return project
    val target = trackId?.let { id -> project.tracks.find { it.id == id && !it.locked } }
    val adjusted = maxOf(snapToFrame(delta, project.fps), -members.minOf { it.start })
    return project.copy(
        clips = project.clips.map { c ->
            if (c.id !in selected) {
                c
            } else {
                c.copy(
                    start = c.start + adjusted,
                    trackId = if (target != null && project.accepts(target, c)) target.id else c.trackId,
                )
            }
        },
    )
}

fun speedLinked(project: Project, clipId: String, speed: Double): Project {
    val ids = linkedIds(project, listOf(clipId)).toSet()
    val members = project.clips.filter { it.id in ids }
    if (members.any { project.isLocked(it) }) return project
    var result = project
    for (c in members) result = changeClipSpeed(result, c.id, speed)
    return result
}

/** Slips the source range under a clip and its partners; refused if any would leave its source. */
fun slipLinked(project: Project, clipId: String, sourceIn: Double): Project {
    val primary = project.clips.find { it.id == clipId }
    if (primary == null || !sourceIn.isFinite()) return project
    val ids = linkedIds(project, listOf(clipId)).toSet()
    val members = project.clips.filter { it.id in ids }
    if (members.any { project.isLocked(it) }) return project
    val delta = sourceIn - primary.sourceIn
    val outOfSource = members.any { c ->
        val asset = project.assets.find { it.id == c.assetId }
        c.sourceIn + delta < 0 ||
            (asset != null && asset.kind != MediaKind.Image && c.sourceIn + delta + c.duration * c.speed > asset.duration + 1e-8)
    }
    if (outOfSource) return project
    return project.copy(clips = project.clips.map { if (it.id in ids) it.copy(sourceIn = it.sourceIn + delta) else it })
}
