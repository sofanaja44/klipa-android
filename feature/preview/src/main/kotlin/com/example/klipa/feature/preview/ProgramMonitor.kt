package com.example.klipa.feature.preview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.viewinterop.AndroidView
import android.view.SurfaceView
import com.example.klipa.core.media.FrameSource
import com.example.klipa.core.media.Look
import com.example.klipa.core.media.colorMatrix
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import com.example.klipa.core.media.parseArgb
import com.example.klipa.core.media.renderText
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.klipa.core.designsystem.Klipa
import com.example.klipa.core.model.Clip
import com.example.klipa.core.model.FadeChannel
import com.example.klipa.core.model.MediaAsset
import com.example.klipa.core.model.Project
import com.example.klipa.core.model.TrackKind
import com.example.klipa.core.model.end
import com.example.klipa.core.model.evaluateFade
import com.example.klipa.core.model.evaluateKeyframes
import kotlin.math.floor
import kotlin.math.roundToInt

/** A clip visible at the playhead, with its keyframes evaluated. */
data class MonitorLayer(val clip: Clip, val asset: MediaAsset?, val local: Double, val alpha: Float)

/**
 * Visible layers at [time], bottom first: lower tracks are drawn before the tracks above
 * them, as in ReaCut. Hidden tracks and audio are skipped.
 */
fun monitorLayers(project: Project, time: Double): List<MonitorLayer> =
    project.tracks.asReversed().flatMap { track ->
        if (track.hidden || track.kind == TrackKind.Audio) return@flatMap emptyList()
        project.clips
            .filter { it.trackId == track.id && time >= it.start && time < it.end }
            .map { clip ->
                val local = time - clip.start
                val evaluated = evaluateKeyframes(clip, local)
                val alpha = (evaluated.transform.opacity * evaluateFade(clip, local, FadeChannel.Video)).coerceIn(0.0, 1.0)
                MonitorLayer(evaluated, project.assets.find { it.id == clip.assetId }, local, alpha.toFloat())
            }
    }

/**
 * The program monitor: the project frame at [time], fitted into the available space.
 *
 * With a live [player], media plays through its surface and text is drawn on top. Without
 * one (or until its first frame arrives) media layers are decoded still by still from
 * [frames]; those still frames show crop, transform, opacity, fades and color adjustments,
 * but not chroma key, masks or blur. Text layers always draw above media, rendered by the
 * same code as export.
 *
 * With [actions], clips can be selected and arranged by touch; [selectedId] is outlined.
 */
@Composable
fun ProgramMonitor(
    project: Project,
    time: Double,
    frames: FrameSource,
    contentDescription: String,
    modifier: Modifier = Modifier,
    player: PreviewPlayer? = null,
    playing: Boolean = false,
    selectedId: String? = null,
    actions: MonitorActions? = null,
) {
    val colors = Klipa.colors
    BoxWithConstraints(modifier.background(colors.canvas), contentAlignment = Alignment.Center) {
        val aspect = project.width.toFloat() / project.height
        val frameWidth = minOf(maxWidth, maxHeight * aspect)
        val frameHeight = frameWidth / aspect
        val background = parseColor(project.background) ?: Color.Black
        Box(
            Modifier
                .size(frameWidth, frameHeight)
                .border(1.dp, colors.outline)
                .background(background)
                .clipToBounds()
                .semantics { this.contentDescription = contentDescription },
        ) {
            val density = LocalDensity.current
            val maxPx = with(density) { maxOf(frameWidth, frameHeight).roundToPx() }
            // Screen pixels per project pixel, for text rendered at screen resolution.
            val textScale = with(density) { frameWidth.toPx() } / project.width
            val live = player != null && player.active && !player.failed
            if (live) {
                AndroidView(
                    factory = { context -> SurfaceView(context).also { player.attach(it) } },
                    onRelease = { player.detach(it) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            val playerShowsMedia = live && player.showingVideo
            for (layer in monitorLayers(project, time)) {
                key(layer.clip.id) {
                    val asset = layer.asset
                    if (layer.clip.text != null) {
                        TextLayer(layer, project, textScale)
                    } else if (asset != null && !playerShowsMedia) {
                        MediaLayer(layer, asset, project, frames, maxPx, exact = !playing)
                    }
                }
            }
            if (actions != null) {
                MonitorEditing(project, time, selectedId, actions, projectPerPx = 1 / textScale)
            }
        }
    }
}

@Composable
private fun MediaLayer(layer: MonitorLayer, asset: MediaAsset, project: Project, frames: FrameSource, maxPx: Int, exact: Boolean) {
    val clip = layer.clip
    // Quantize to source frames so tiny playhead moves reuse the decoded frame.
    val sourceFps = if (asset.fps > 0) asset.fps else project.fps.toDouble()
    val sourceFrame = floor((clip.sourceIn + layer.local * clip.speed) * sourceFps + 1e-6).toLong()
    var bitmap by remember(asset.id) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(asset.id, sourceFrame, maxPx) {
        // The previous frame stays up until the new one is decoded, so scrubbing never flashes.
        // While playing without a real-time player, key frames keep up; paused, show the exact frame.
        frames.frame(asset, (sourceFrame + 0.5) / sourceFps, maxPx, exact)?.let { bitmap = it }
    }
    val image = bitmap ?: return
    val colorFilter = remember(clip.adjustments) { Look.of(clip).colorMatrix()?.let { ColorFilter.colorMatrix(ColorMatrix(it)) } }
    Canvas(Modifier.fillMaxSize()) {
        withClipTransform(clip, project) { scale ->
            // The crop, in the decoded frame's pixels; it is what gets fitted, as in export.
            val crop = clip.crop
            val srcX = crop?.let { (it.x * image.width).roundToInt().coerceIn(0, image.width - 1) } ?: 0
            val srcY = crop?.let { (it.y * image.height).roundToInt().coerceIn(0, image.height - 1) } ?: 0
            val srcW = crop?.let { (it.width * image.width).roundToInt().coerceIn(1, image.width - srcX) } ?: image.width
            val srcH = crop?.let { (it.height * image.height).roundToInt().coerceIn(1, image.height - srcY) } ?: image.height
            val contain = minOf(project.width.toFloat() / srcW, project.height.toFloat() / srcH) * scale
            val w = (srcW * contain).roundToInt()
            val h = (srcH * contain).roundToInt()
            drawImage(
                image,
                srcOffset = IntOffset(srcX, srcY),
                srcSize = IntSize(srcW, srcH),
                dstOffset = IntOffset(-w / 2, -h / 2),
                dstSize = IntSize(w, h),
                alpha = layer.alpha,
                colorFilter = colorFilter,
            )
        }
    }
}

/**
 * Applies a clip's transform: [x], [y] are offsets from the frame center in project
 * pixels, rotation is in degrees. [draw] receives the project-to-screen scale and draws
 * centered on the origin.
 */
private fun DrawScope.withClipTransform(clip: Clip, project: Project, draw: DrawScope.(Float) -> Unit) {
    val scale = size.width / project.width
    val t = clip.transform
    withTransform({
        translate(size.width / 2 + (t.x * scale).toFloat(), size.height / 2 + (t.y * scale).toFloat())
        rotate(t.rotation.toFloat(), pivot = Offset.Zero)
        scale((t.scale * if (t.flipX) -1 else 1).toFloat(), (t.scale * if (t.flipY) -1 else 1).toFloat(), pivot = Offset.Zero)
    }) { draw(scale) }
}

@Composable
private fun TextLayer(layer: MonitorLayer, project: Project, scale: Float) {
    val style = layer.clip.text ?: return
    val image = remember(style, scale) { renderText(style, scale)?.asImageBitmap() } ?: return
    Canvas(Modifier.fillMaxSize()) {
        withClipTransform(layer.clip, project) {
            drawImage(image, topLeft = Offset(-image.width / 2f, -image.height / 2f), alpha = layer.alpha)
        }
    }
}

/** Parses the colors a project may contain: `#rrggbb`, `#rrggbbaa` and a few names. */
fun parseColor(value: String): Color? = parseArgb(value)?.let { Color(it) }
