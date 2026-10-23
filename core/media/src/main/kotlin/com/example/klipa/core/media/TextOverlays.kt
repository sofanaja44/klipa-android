// Media3's overlay APIs are marked unstable; Klipa pins the Media3 version and builds on
// them deliberately.
@file:OptIn(UnstableApi::class)

package com.example.klipa.core.media

import android.graphics.Bitmap
import androidx.annotation.OptIn
import androidx.core.graphics.createBitmap
import androidx.media3.common.OverlaySettings
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.TextureOverlay
import com.example.klipa.core.model.Clip
import com.example.klipa.core.model.Project
import com.example.klipa.core.model.TextStyle
import com.example.klipa.core.model.TrackKind
import com.example.klipa.core.model.end

// Text clips are drawn over the composited frame by one OverlayEffect. Each overlay is a
// lane of text clips that never overlap, so a project with hundreds of subtitles needs
// only as many overlays (and bitmaps in memory) as there are titles on screen at once.

/**
 * Text clips grouped into lanes, in drawing order (bottom first): lower tracks before the
 * tracks above them, and on one track, clips that start over another one above it. Hidden
 * and audio tracks are left out, as on the program monitor.
 */
fun planTextLanes(project: Project): List<List<Clip>> =
    project.tracks.asReversed().flatMap { track ->
        if (track.hidden || track.kind == TrackKind.Audio) return@flatMap emptyList()
        val clips = project.clips.filter { it.trackId == track.id && it.text != null }.sortedBy { it.start }
        val lanes = mutableListOf<MutableList<Clip>>()
        for (clip in clips) {
            val startUs = secondsToUs(clip.start)
            val lane = lanes.firstOrNull { secondsToUs(it.last().end) <= startUs }
            if (lane != null) lane += clip else lanes += mutableListOf(clip)
        }
        lanes
    }

/**
 * The effect drawing every text clip of [project], or null when it has none. [render]
 * turns a style into its bitmap at project scale.
 */
fun textOverlayEffect(project: Project, render: (TextStyle) -> Bitmap? = { renderText(it) }): OverlayEffect? {
    val lanes = planTextLanes(project)
    if (lanes.isEmpty()) return null
    return OverlayEffect(lanes.map { lane -> TextLaneOverlay(project, lane, render) as TextureOverlay })
}

/**
 * One lane of text clips. Media3 asks for the bitmap and the settings of every frame with
 * its composition timestamp; the bitmap is rendered when a clip comes on screen and kept
 * while it stays there, so it is uploaded to the GPU once per clip.
 */
internal class TextLaneOverlay(
    private val project: Project,
    private val clips: List<Clip>,
    private val render: (TextStyle) -> Bitmap?,
) : BitmapOverlay() {
    private var current: Clip? = null
    private var currentBitmap: Bitmap? = null

    /** The clip on screen at [timeUs] whose text could be rendered. */
    private fun clipAt(timeUs: Long): Clip? {
        val clip = clips.firstOrNull { timeUs >= secondsToUs(it.start) && timeUs < secondsToUs(it.end) } ?: return null
        if (clip !== current) {
            current = clip
            currentBitmap = clip.text?.let(render)
        }
        return clip.takeIf { currentBitmap != null }
    }

    override fun getBitmap(presentationTimeUs: Long): Bitmap =
        if (clipAt(presentationTimeUs) != null) currentBitmap!! else Blank

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
        val clip = clipAt(presentationTimeUs) ?: return HiddenOverlay
        return layerOverlay(project, clip, ((presentationTimeUs - secondsToUs(clip.start)) / 1_000_000.0).coerceIn(0.0, clip.duration))
    }

    private companion object {
        /** Shown, fully transparent, while no clip of the lane is on screen. */
        val Blank: Bitmap by lazy { createBitmap(1, 1) }
    }
}
