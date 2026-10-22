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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.viewinterop.AndroidView
import android.view.SurfaceView
import com.example.klipa.core.media.FrameSource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
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
import com.example.klipa.core.model.TextAlign as ClipTextAlign

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
 * [frames]; those still frames show transform, opacity and fades, but not crop or color
 * adjustments. Text layers always draw above media.
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
            val maxPx = with(LocalDensity.current) { maxOf(frameWidth, frameHeight).roundToPx() }
            val textMeasurer = rememberTextMeasurer()
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
                        Canvas(Modifier.fillMaxSize()) { drawTextLayer(layer, project, textMeasurer) }
                    } else if (asset != null && !playerShowsMedia) {
                        MediaLayer(layer, asset, project, frames, maxPx, exact = !playing)
                    }
                }
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
    Canvas(Modifier.fillMaxSize()) {
        withClipTransform(clip, project) { scale ->
            val contain = minOf(project.width.toFloat() / image.width, project.height.toFloat() / image.height) * scale
            val w = (image.width * contain).roundToInt()
            val h = (image.height * contain).roundToInt()
            drawImage(image, dstOffset = IntOffset(-w / 2, -h / 2), dstSize = IntSize(w, h), alpha = layer.alpha)
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

private fun DrawScope.drawTextLayer(layer: MonitorLayer, project: Project, textMeasurer: TextMeasurer) {
    val style = layer.clip.text ?: return
    withClipTransform(layer.clip, project) { scale ->
        val fontPx = (style.fontSize * scale).toFloat()
        if (fontPx < 1f) return@withClipTransform
        val textStyle = TextStyle(
            color = parseColor(style.color) ?: Color.White,
            fontSize = fontPx.toSp(),
            lineHeight = 1.3.em,
            fontWeight = if (style.bold) FontWeight.Bold else FontWeight.Normal,
            textAlign = style.align.toCompose(),
        )
        val measured = textMeasurer.measure(style.text, textStyle)
        val padding = fontPx * 0.25f + (style.strokeWidth * scale).toFloat()
        val w = measured.size.width + padding * 2
        val h = measured.size.height + padding * 2
        val topLeft = Offset(-w / 2 + padding, -h / 2 + padding)
        parseColor(style.background)?.takeIf { it.alpha > 0f }?.let {
            drawRect(it, Offset(-w / 2, -h / 2), Size(w, h), alpha = layer.alpha)
        }
        val stroke = parseColor(style.stroke)
        if (style.strokeWidth > 0 && stroke != null && stroke.alpha > 0f) {
            drawText(
                textMeasurer.measure(style.text, textStyle.copy(color = stroke, drawStyle = Stroke(width = (style.strokeWidth * 2 * scale).toFloat()))),
                topLeft = topLeft,
                alpha = layer.alpha,
            )
        }
        drawText(measured, topLeft = topLeft, alpha = layer.alpha)
    }
}

private fun ClipTextAlign.toCompose(): TextAlign = when (this) {
    ClipTextAlign.Left -> TextAlign.Left
    ClipTextAlign.Center -> TextAlign.Center
    ClipTextAlign.Right -> TextAlign.Right
}

/** Parses the colors a project may contain: `#rrggbb`, `#rrggbbaa` and a few names. */
fun parseColor(value: String): Color? {
    when (value.lowercase()) {
        "black" -> return Color.Black
        "white" -> return Color.White
        "red" -> return Color.Red
        "green" -> return Color(0xFF008000)
        "blue" -> return Color.Blue
        "transparent" -> return Color.Transparent
    }
    if (!value.startsWith("#") || (value.length != 7 && value.length != 9)) return null
    val rgb = value.substring(1, 7).toLongOrNull(16) ?: return null
    val alpha = if (value.length == 9) value.substring(7, 9).toLongOrNull(16) ?: return null else 0xFF
    return Color((alpha shl 24) or rgb)
}
