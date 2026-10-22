// Media3's composition, effect and audio-processing APIs are marked unstable; Klipa
// pins the Media3 version and builds on them deliberately.
@file:OptIn(UnstableApi::class)

package com.example.klipa.core.media

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import android.util.Pair
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.OverlaySettings
import androidx.media3.common.VideoCompositorSettings
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.util.Size
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.Crop
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import com.example.klipa.core.model.Clip
import com.example.klipa.core.model.FadeChannel
import com.example.klipa.core.model.KeyframeProperty
import com.example.klipa.core.model.MediaAsset
import com.example.klipa.core.model.MediaKind
import com.example.klipa.core.model.Project
import com.example.klipa.core.model.evaluateFade
import com.example.klipa.core.model.evaluateKeyframe
import com.example.klipa.core.model.evaluateKeyframes
import kotlin.math.roundToInt

// Builds the Media3 Composition for a project. Preview (CompositionPlayer) and export
// (Transformer) both play this, so what you see is what renders.
//
// Per clip, decoding effects crop, color-correct and fit each frame to its "contain"
// size in the project frame; per frame, the compositor places it (position, scale,
// rotation, opacity and fades, with keyframes evaluated at that time).

/** A project's composition plus the plan it was built from. */
class ProjectComposition(val composition: Composition, val plan: CompositionPlan)

/** Returns null when nothing in the project has media to play. */
fun buildComposition(project: Project): ProjectComposition? {
    val plan = planComposition(project)
    if (plan.isEmpty) return null
    val sequences = plan.sequences.map { sequence ->
        val trackTypes = buildSet {
            if (sequence.hasVideo) add(C.TRACK_TYPE_VIDEO)
            if (sequence.hasAudio) add(C.TRACK_TYPE_AUDIO)
        }
        // Declaring the track types makes gaps (including a leading one) render as
        // silence or blank frames.
        val builder = EditedMediaItemSequence.Builder(trackTypes)
        for (segment in sequence.segments) {
            when (segment) {
                is Segment.Gap -> builder.addGap(segment.durationUs)
                is Segment.Item -> builder.addItem(editedItem(project, segment))
            }
        }
        builder.build()
    }
    val composition = Composition.Builder(sequences)
        .setVideoCompositorSettings(ProjectCompositorSettings(project, plan))
        .build()
    return ProjectComposition(composition, plan)
}

private fun editedItem(project: Project, item: Segment.Item): EditedMediaItem {
    val clip = item.clip
    val asset = item.asset
    val mediaItem = MediaItem.Builder().setUri(asset.url)
    if (asset.kind == MediaKind.Image) {
        mediaItem.setImageDurationMs(item.durationUs / 1000)
    } else {
        val startUs = secondsToUs(clip.sourceIn)
        val endSeconds = clip.sourceIn + clip.duration * clip.speed
        // Within a millisecond of the source end, play to the end rather than past it.
        val endUs = if (endSeconds >= asset.duration - 0.001) C.TIME_END_OF_SOURCE else secondsToUs(endSeconds)
        mediaItem.setClippingConfiguration(
            MediaItem.ClippingConfiguration.Builder().setStartPositionUs(startUs).setEndPositionUs(endUs).build(),
        )
    }
    val builder = EditedMediaItem.Builder(mediaItem.build())
        .setRemoveVideo(item.removeVideo)
        .setRemoveAudio(item.removeAudio)
    if (asset.kind == MediaKind.Image) {
        builder.setDurationUs(item.durationUs).setFrameRate(project.fps)
    } else {
        // CompositionPlayer lays out the timeline before probing media, so it needs the
        // full source duration; clipping and speed are applied on top of it.
        builder.setDurationUs(secondsToUs(asset.duration))
        if (clip.speed != 1.0) builder.setSpeed(ConstantSpeed(clip.speed.toFloat()))
    }
    val audio = if (item.removeAudio) emptyList() else listOfNotNull(gainProcessor(clip))
    val video = if (item.removeVideo) emptyList() else videoEffects(project, clip, asset)
    if (audio.isNotEmpty() || video.isNotEmpty()) builder.setEffects(Effects(audio, video))
    return builder.build()
}

private class ConstantSpeed(private val speed: Float) : SpeedProvider {
    override fun getSpeed(timeUs: Long): Float = speed

    override fun getNextSpeedChangeTimeUs(timeUs: Long): Long = C.TIME_UNSET
}

/** Volume, volume keyframes and audio fades; null when the clip plays at unity gain. */
private fun gainProcessor(clip: Clip): AudioProcessor? {
    val animated = clip.keyframes.any { it.property == KeyframeProperty.Volume } ||
        clip.audioFadeIn > 0 || clip.audioFadeOut > 0 || clip.audioFadeWindow != null
    if (!animated && clip.volume == 1.0) return null
    if (!animated) {
        val gain = clip.volume.toFloat()
        return GainAudioProcessor { gain }
    }
    return GainAudioProcessor { positionUs ->
        val local = positionUs / 1_000_000.0 / clip.speed
        val volume = evaluateKeyframe(clip, KeyframeProperty.Volume, local) ?: clip.volume
        (volume * evaluateFade(clip, local, FadeChannel.Audio)).toFloat()
    }
}

/** Crop, color and the fit to the project frame, in that order. */
private fun videoEffects(project: Project, clip: Clip, asset: MediaAsset): List<Effect> = buildList {
    clip.crop?.let { crop ->
        // Crop takes normalized device coordinates: x and y in -1..1, y pointing up.
        add(Crop((crop.x * 2 - 1).toFloat(), ((crop.x + crop.width) * 2 - 1).toFloat(), (1 - (crop.y + crop.height) * 2).toFloat(), (1 - crop.y * 2).toFloat()))
    }
    val adjust = clip.adjustments
    if (adjust.brightness != 0.0) add(Brightness(adjust.brightness.toFloat().coerceIn(-1f, 1f)))
    // ReaCut contrast and saturation are multipliers around 1; Media3 takes offsets from 0.
    if (adjust.contrast != 1.0) add(Contrast((adjust.contrast - 1).toFloat().coerceIn(-1f, 1f)))
    if (adjust.saturation != 1.0) add(HslAdjustment.Builder().adjustSaturation(((adjust.saturation - 1) * 100).toFloat().coerceIn(-100f, 100f)).build())
    fitSize(project, clip, asset)?.let { (width, height) ->
        add(Presentation.createForWidthAndHeight(width, height, Presentation.LAYOUT_STRETCH_TO_FIT))
    }
}

/**
 * The size, in project pixels, of the clip's (cropped) frame fitted inside the project
 * frame. Frames are resized to it up front so the compositor's scale is the clip's own.
 */
internal fun fitSize(project: Project, clip: Clip, asset: MediaAsset): kotlin.Pair<Int, Int>? {
    val sourceWidth = asset.width * (clip.crop?.width ?: 1.0)
    val sourceHeight = asset.height * (clip.crop?.height ?: 1.0)
    if (sourceWidth <= 0 || sourceHeight <= 0) return null
    val contain = minOf(project.width / sourceWidth, project.height / sourceHeight)
    return maxOf(2, (sourceWidth * contain).roundToInt()) to maxOf(2, (sourceHeight * contain).roundToInt())
}

/**
 * Places each layer per frame. Input ids are sequence indices; video sequences come
 * first in the plan, so they match. Layers with no clip at a time are fully transparent,
 * so blank gap frames never hide the tracks below.
 */
internal class ProjectCompositorSettings(private val project: Project, plan: CompositionPlan) : VideoCompositorSettings {
    private val layers = plan.videoSequences

    override fun getOutputSize(inputSizes: List<Size>): Size = Size(project.width, project.height)

    override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): OverlaySettings {
        val item = layers.getOrNull(inputId)?.itemAt(presentationTimeUs) ?: return Hidden
        val local = (presentationTimeUs - item.startUs) / 1_000_000.0
        return layerOverlay(project, item.clip, local)
    }
}

/** The overlay for [clip] at clip-local [local] seconds. */
internal fun layerOverlay(project: Project, clip: Clip, local: Double): OverlaySettings {
    val evaluated = evaluateKeyframes(clip, local)
    val t = evaluated.transform
    val alpha = (t.opacity * evaluateFade(clip, local, FadeChannel.Video)).coerceIn(0.0, 1.0).toFloat()
    // ReaCut offsets are project pixels from the center with y down; Media3 anchors are
    // normalized with y up, and its rotation is counter-clockwise.
    return LayerOverlay(
        alpha = alpha,
        anchorX = (2 * t.x / project.width).toFloat(),
        anchorY = (-2 * t.y / project.height).toFloat(),
        scaleX = (t.scale * if (t.flipX) -1 else 1).toFloat(),
        scaleY = (t.scale * if (t.flipY) -1 else 1).toFloat(),
        rotation = (-t.rotation).toFloat(),
    )
}

internal data class LayerOverlay(
    val alpha: Float,
    val anchorX: Float,
    val anchorY: Float,
    val scaleX: Float,
    val scaleY: Float,
    val rotation: Float,
) : OverlaySettings {
    override fun getAlphaScale(): Float = alpha

    override fun getBackgroundFrameAnchor(): Pair<Float, Float> = Pair.create(anchorX, anchorY)

    override fun getScale(): Pair<Float, Float> = Pair.create(scaleX, scaleY)

    override fun getRotationDegrees(): Float = rotation
}

private val Hidden = LayerOverlay(alpha = 0f, anchorX = 0f, anchorY = 0f, scaleX = 1f, scaleY = 1f, rotation = 0f)
