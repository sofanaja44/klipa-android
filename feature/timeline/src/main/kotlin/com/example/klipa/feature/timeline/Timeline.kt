package com.example.klipa.feature.timeline

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.unit.IntSize
import com.example.klipa.core.media.FrameSource
import com.example.klipa.core.model.MediaAsset
import kotlin.math.floor
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorPainter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.klipa.core.designsystem.Klipa
import com.example.klipa.core.designsystem.KlipaColors
import com.example.klipa.core.designsystem.KlipaShapes
import com.example.klipa.core.designsystem.KlipaSpacing
import com.example.klipa.core.designsystem.KlipaTextStyles
import com.example.klipa.core.edit.EditorState
import com.example.klipa.core.edit.TrackDirection
import com.example.klipa.core.model.Clip
import com.example.klipa.core.model.MediaKind
import com.example.klipa.core.model.Track
import com.example.klipa.core.model.TrackKind
import com.example.klipa.core.model.end
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private object TimelineDimens {
    val ruler = 28.dp
    val header = 44.dp
    val laneVideo = 52.dp
    val laneAudio = 40.dp
    val laneText = 30.dp
    val laneGap = 4.dp
    val handleHit = 22.dp
    val handleVisual = 10.dp
    val snap = 10.dp
    val minMajorTick = 72.dp
    val minMinorTick = 10.dp
}

/** Dimensions resolved to pixels for one density. */
@Immutable
private class TimelinePx(density: Density) {
    val ruler: Float
    val header: Float
    val heights: LaneHeights
    val handleHit: Float
    val handleVisual: Float
    val snap: Float
    val minMajorTick: Float
    val minMinorTick: Float
    val stroke: Float
    val corner: Float
    val pad: Float
    val glyph: Float

    init {
        with(density) {
            ruler = TimelineDimens.ruler.toPx()
            header = TimelineDimens.header.toPx()
            heights = LaneHeights(
                video = TimelineDimens.laneVideo.toPx(),
                audio = TimelineDimens.laneAudio.toPx(),
                text = TimelineDimens.laneText.toPx(),
                gap = TimelineDimens.laneGap.toPx(),
            )
            handleHit = TimelineDimens.handleHit.toPx()
            handleVisual = TimelineDimens.handleVisual.toPx()
            snap = TimelineDimens.snap.toPx()
            minMajorTick = TimelineDimens.minMajorTick.toPx()
            minMinorTick = TimelineDimens.minMinorTick.toPx()
            stroke = 2.dp.toPx()
            corner = 5.dp.toPx()
            pad = 6.dp.toPx()
            glyph = 12.dp.toPx()
        }
    }
}

/** Everything a gesture needs to interpret a touch, refreshed every composition. */
private class TimelineFrame(
    val state: EditorState,
    val viewport: TimelineViewport,
    val lanes: List<Lane>,
    val scroll: Float,
    val maxScroll: Float,
)

private enum class TouchMode { Pending, Scrub, Pinch, Move }

/**
 * The multi-track timeline. The playhead is fixed in the middle; drag to scrub (with
 * fling), pinch to zoom, tap a clip to select it, drag a selected clip's edge to trim,
 * and long-press a clip to move it in time or onto another track.
 */
@Composable
fun Timeline(viewModel: EditorViewModel, onAddMedia: () -> Unit, modifier: Modifier = Modifier, frames: FrameSource? = null) {
    val state by viewModel.state.collectAsState()
    val ready by viewModel.ready.collectAsState()
    TimelineContent(state, ready, viewModel, frames, onAddMedia, modifier)
}

@Composable
private fun TimelineContent(
    state: EditorState,
    ready: Boolean,
    viewModel: EditorViewModel,
    frames: FrameSource?,
    onAddMedia: () -> Unit,
    modifier: Modifier,
) {
    val colors = Klipa.colors
    val styles = Klipa.textStyles
    val density = LocalDensity.current
    val px = remember(density) { TimelinePx(density) }
    val textMeasurer = rememberTextMeasurer()
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var scrollY by remember { mutableFloatStateOf(0f) }
    var fling by remember { mutableStateOf<Job?>(null) }
    val thumbnails = remember(frames, px) { frames?.let { ThumbnailCache(it, scope, px.heights.video.roundToInt()) } }
    val glyphs = TimelineGlyphs(
        link = rememberVectorPainter(Icons.Rounded.Link),
        muted = rememberVectorPainter(Icons.AutoMirrored.Rounded.VolumeOff),
    )
    val description = stringResource(R.string.timeline_description)

    BoxWithConstraints(modifier.background(colors.panel).clipToBounds().semantics { contentDescription = description }) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        val viewport = TimelineViewport(state.playhead, (state.zoom * density.density).toFloat(), width / 2)
        val lanes = remember(state.project.tracks, px) { layoutLanes(state.project.tracks, px.heights) }
        val maxScroll = maxOf(0f, lanesHeight(lanes, px.heights) - (height - px.ruler))
        val scroll = scrollY.coerceIn(0f, maxScroll)
        val frame by rememberUpdatedState(TimelineFrame(state, viewport, lanes, scroll, maxScroll))

        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(px) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        fling?.cancel()
                        val start = frame
                        val inTracks = down.position.y >= px.ruler
                        val hit = if (inTracks && down.position.x >= px.header) {
                            hitTest(
                                down.position.x,
                                down.position.y - px.ruler + start.scroll,
                                start.state.project,
                                start.lanes,
                                start.viewport,
                                start.state.selectedIds,
                                px.handleHit,
                            )
                        } else {
                            TimelineHit.Nothing
                        }
                        val project = start.state.project
                        fun lockedClip(id: String) =
                            project.tracks.find { t -> t.id == project.clips.find { it.id == id }?.trackId }?.locked != false

                        if (hit is TimelineHit.ClipEdge && !lockedClip(hit.clipId)) {
                            viewModel.beginGesture()
                            var total = 0f
                            drag(down.id) { change ->
                                total += change.positionChange().x
                                change.consume()
                                val pxPerSecond = frame.viewport.pxPerSecond
                                viewModel.dragTrim(hit.clipId, hit.edge, (total / pxPerSecond).toDouble(), (px.snap / pxPerSecond).toDouble())
                            }
                            viewModel.endGesture()
                            return@awaitEachGesture
                        }

                        val movable = (hit as? TimelineHit.ClipBody)?.clipId?.takeUnless(::lockedClip)
                        val deadline = down.uptimeMillis + viewConfiguration.longPressTimeoutMillis
                        val velocity = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }
                        var mode = TouchMode.Pending
                        var pan = Offset.Zero
                        var moved = 0f
                        var lastTime = down.uptimeMillis
                        while (true) {
                            val event = if (mode == TouchMode.Pending && movable != null) {
                                withTimeoutOrNull(maxOf(1L, deadline - lastTime)) { awaitPointerEvent() }
                            } else {
                                awaitPointerEvent()
                            }
                            if (event == null) {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                if (movable !in frame.state.selectedIds) viewModel.select(movable!!)
                                viewModel.beginGesture()
                                mode = TouchMode.Move
                                continue
                            }
                            val pressed = event.changes.filter { it.pressed }
                            val primary = event.changes.first()
                            lastTime = primary.uptimeMillis
                            if (pressed.isEmpty()) {
                                when (mode) {
                                    TouchMode.Pending -> when {
                                        !inTracks -> viewModel.seek(frame.viewport.timeAt(down.position.x))
                                        hit is TimelineHit.ClipBody -> viewModel.select(hit.clipId)
                                        hit is TimelineHit.ClipEdge -> viewModel.select(hit.clipId)
                                        else -> viewModel.clearSelection()
                                    }
                                    TouchMode.Scrub -> {
                                        val pxPerSecond = frame.viewport.pxPerSecond
                                        val vx = velocity.calculateVelocity().x
                                        if (abs(vx) > MIN_FLING_PX_PER_SECOND) {
                                            fling = scope.launch {
                                                Animatable(frame.state.playhead.toFloat()).animateDecay(-vx / pxPerSecond, exponentialDecay()) {
                                                    viewModel.seek(value.toDouble())
                                                }
                                            }
                                        }
                                    }
                                    TouchMode.Move -> viewModel.endGesture()
                                    TouchMode.Pinch -> Unit
                                }
                                break
                            }
                            if (pressed.size > 1 && mode != TouchMode.Move) mode = TouchMode.Pinch
                            val delta = primary.positionChange()
                            when (mode) {
                                TouchMode.Pending -> {
                                    pan += delta
                                    if (pan.getDistance() > viewConfiguration.touchSlop) mode = TouchMode.Scrub
                                }
                                TouchMode.Scrub -> {
                                    velocity.addPosition(primary.uptimeMillis, primary.position)
                                    val current = frame
                                    viewModel.seek(current.state.playhead - delta.x / current.viewport.pxPerSecond)
                                    scrollY = (current.scroll - delta.y).coerceIn(0f, current.maxScroll)
                                }
                                TouchMode.Pinch -> {
                                    val zoom = event.calculateZoom()
                                    if (zoom != 1f) viewModel.setZoom(frame.state.zoom * zoom)
                                }
                                TouchMode.Move -> {
                                    moved += delta.x
                                    val current = frame
                                    val pxPerSecond = current.viewport.pxPerSecond
                                    val laneY = primary.position.y - px.ruler + current.scroll
                                    val target = current.lanes.firstOrNull { laneY >= it.top && laneY < it.bottom }?.track?.id
                                    viewModel.dragMove(movable!!, (moved / pxPerSecond).toDouble(), target, (px.snap / pxPerSecond).toDouble())
                                }
                            }
                            event.changes.forEach { it.consume() }
                        }
                    }
                },
        ) {
            drawTracks(frame, px, colors, styles, textMeasurer, glyphs, thumbnails)
            drawRuler(state, viewport, px, colors, styles, textMeasurer)
            drawPlayhead(px, colors)
        }

        TrackHeaders(
            lanes = lanes,
            scroll = scroll,
            topPx = px.ruler,
            onTrackChange = viewModel::updateTrack,
            onReorder = viewModel::reorderTrack,
            onDelete = viewModel::deleteTrack,
        )

        if (ready && state.project.clips.isEmpty()) {
            EmptyTimeline(
                onAddMedia = onAddMedia,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = TimelineDimens.header, top = TimelineDimens.ruler),
            )
        }
    }
}

private const val MIN_FLING_PX_PER_SECOND = 400f
private val PLAYHEAD_CAP_HALF_WIDTH = 6.dp

private class TimelineGlyphs(val link: VectorPainter, val muted: VectorPainter)

private fun KlipaColors.clipFill(kind: MediaKind?): Color = when (kind) {
    MediaKind.Video -> clipVideo
    MediaKind.Image -> clipImage
    MediaKind.Audio -> clipAudio
    null -> clipText
}

private fun DrawScope.drawTracks(
    frame: TimelineFrame,
    px: TimelinePx,
    colors: KlipaColors,
    styles: KlipaTextStyles,
    textMeasurer: TextMeasurer,
    glyphs: TimelineGlyphs,
    thumbnails: ThumbnailCache?,
) {
    val project = frame.state.project
    val viewport = frame.viewport
    val zeroX = viewport.xOf(0.0)
    clipRect(top = px.ruler) {
        translate(top = px.ruler - frame.scroll) {
            // Before the start of the timeline reads as "outside", not as empty track.
            if (zeroX > 0) drawRect(colors.canvas, Offset(0f, -px.ruler), Size(zeroX, size.height + frame.scroll + px.ruler))
            for (lane in frame.lanes) {
                val left = maxOf(0f, zeroX)
                drawRect(colors.raised.copy(alpha = 0.45f), Offset(left, lane.top), Size(size.width - left, lane.height))
                if (lane.track.locked) drawHatch(Rect(left, lane.top, size.width, lane.bottom), colors.outline, px.pad * 1.5f)
            }
            for (lane in frame.lanes) {
                for (clip in project.clips) {
                    if (clip.trackId != lane.track.id) continue
                    drawClip(clip, lane, frame, px, colors, styles, textMeasurer, glyphs, thumbnails)
                }
            }
        }
    }
}

private fun DrawScope.drawHatch(area: Rect, color: Color, spacing: Float) {
    clipRect(area.left, area.top, area.right, area.bottom) {
        var x = area.left - area.height
        while (x < area.right) {
            drawLine(color, Offset(x, area.bottom), Offset(x + area.height, area.top), strokeWidth = 1f)
            x += spacing
        }
    }
}

private fun DrawScope.drawClip(
    clip: Clip,
    lane: Lane,
    frame: TimelineFrame,
    px: TimelinePx,
    colors: KlipaColors,
    styles: KlipaTextStyles,
    textMeasurer: TextMeasurer,
    glyphs: TimelineGlyphs,
    thumbnails: ThumbnailCache?,
) {
    val viewport = frame.viewport
    val left = viewport.xOf(clip.start) + 0.5f
    val right = viewport.xOf(clip.end) - 0.5f
    if (right < 0 || left > size.width || right <= left) return
    val project = frame.state.project
    val asset = project.assets.find { it.id == clip.assetId }
    val selected = clip.id in frame.state.selectedIds
    val alpha = if (lane.track.hidden) 0.4f else 1f
    val top = lane.top
    val height = lane.height
    val corner = CornerRadius(px.corner)
    drawRoundRect(colors.clipFill(asset?.kind), Offset(left, top), Size(right - left, height), corner, alpha = alpha)

    val shape = Path().apply { addRoundRect(RoundRect(left, top, right, top + height, corner)) }
    clipPath(shape) {
        if (thumbnails != null && asset != null && asset.kind != MediaKind.Audio) {
            drawThumbnails(clip, asset, left, right, top, height, viewport, thumbnails, alpha)
        }
        val waveform = asset?.waveform
        if (asset?.kind == MediaKind.Audio && !waveform.isNullOrEmpty() && asset.duration > 0) {
            drawWaveform(clip, waveform, asset.duration, left, right, top, height, viewport, colors.clipDetail.copy(alpha = 0.35f * alpha))
        }
        val audioOnly = asset?.kind == MediaKind.Audio
        val fadeIn = if (audioOnly) clip.audioFadeIn else clip.fadeIn
        val fadeOut = if (audioOnly) clip.audioFadeOut else clip.fadeOut
        drawFade(left, top, height, (fadeIn * viewport.pxPerSecond).toFloat(), rising = true, colors)
        drawFade(right, top, height, (fadeOut * viewport.pxPerSecond).toFloat(), rising = false, colors)

        // Labels stick to the visible part of the clip and clear the trim handle.
        val labelStart = maxOf(left + if (selected) px.handleVisual else 0f, px.header) + px.pad
        var x = labelStart
        val glyphTop = top + px.pad * 0.6f
        for ((show, glyph) in listOf((clip.linkId != null) to glyphs.link, clip.muted to glyphs.muted)) {
            if (!show || x + px.glyph > right - px.pad) continue
            translate(x, glyphTop) {
                with(glyph) { draw(Size(px.glyph, px.glyph), alpha = alpha, colorFilter = ColorFilter.tint(colors.onClip)) }
            }
            x += px.glyph + px.pad * 0.5f
        }
        val label = buildString {
            append(clip.text?.text?.lineSequence()?.firstOrNull()?.ifBlank { null } ?: clip.name)
            if (clip.speed != 1.0) append("  ").append(formatSpeed(clip.speed))
        }
        drawLabel(textMeasurer, label, styles.clipLabel.copy(color = colors.onClip.copy(alpha = alpha)), x, top + px.pad * 0.4f, right - px.pad - x)
        if (height >= px.heights.video) {
            drawLabel(
                textMeasurer,
                formatDuration(clip.duration),
                styles.timecodeSmall.copy(color = colors.clipDetail),
                labelStart,
                top + height - px.pad - 12.dp.toPx(),
                right - px.pad - labelStart,
            )
        }
    }

    if (selected) {
        drawRoundRect(colors.accent, Offset(left, top), Size(right - left, height), corner, style = Stroke(px.stroke))
        val handle = minOf(px.handleVisual, (right - left) / 3)
        for (edgeX in listOf(left, right - handle)) {
            drawRoundRect(colors.accent, Offset(edgeX, top), Size(handle, height), corner)
            val gripX = edgeX + handle / 2
            drawLine(colors.onAccent, Offset(gripX, top + height * 0.35f), Offset(gripX, top + height * 0.65f), strokeWidth = px.stroke * 0.75f)
        }
    }
}

/** A filmstrip of thumbnails, with a shade at the top so the label stays readable. */
private fun DrawScope.drawThumbnails(
    clip: Clip,
    asset: MediaAsset,
    left: Float,
    right: Float,
    top: Float,
    height: Float,
    viewport: TimelineViewport,
    thumbnails: ThumbnailCache,
    alpha: Float,
) {
    // Reading the version redraws the timeline as thumbnails arrive.
    thumbnails.version
    val aspect = if (asset.width > 0 && asset.height > 0) asset.width.toFloat() / asset.height else 16f / 9f
    val tile = (height * aspect).coerceAtLeast(8f)
    val step = thumbnails.step(tile / viewport.pxPerSecond * clip.speed)
    var x = left + floor((maxOf(left, 0f) - left) / tile) * tile
    while (x < minOf(right, size.width)) {
        val source = clip.sourceIn + (viewport.timeAt(x) - clip.start) * clip.speed
        thumbnails.get(asset, source, step)?.let { image ->
            drawImage(image, dstOffset = IntOffset(x.roundToInt(), top.roundToInt()), dstSize = IntSize(tile.roundToInt(), height.roundToInt()), alpha = alpha)
        }
        x += tile
    }
    drawRect(
        Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.55f), 0.45f to Color.Transparent, 0.7f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.45f), startY = top, endY = top + height),
        topLeft = Offset(left, top),
        size = Size(right - left, height),
    )
}

private fun DrawScope.drawFade(edgeX: Float, top: Float, height: Float, widthPx: Float, rising: Boolean, colors: KlipaColors) {
    if (widthPx < 1f) return
    val far = if (rising) edgeX + widthPx else edgeX - widthPx
    val shade = Path().apply {
        moveTo(edgeX, top)
        lineTo(far, top)
        lineTo(edgeX, top + height)
        close()
    }
    drawPath(shade, Color.Black.copy(alpha = 0.28f))
    drawLine(colors.clipDetail, Offset(edgeX, top + height), Offset(far, top), strokeWidth = 1.5f)
}

private fun DrawScope.drawWaveform(
    clip: Clip,
    waveform: List<Double>,
    sourceDuration: Double,
    left: Float,
    right: Float,
    top: Float,
    height: Float,
    viewport: TimelineViewport,
    color: Color,
) {
    val step = 3f
    val center = top + height / 2
    var x = maxOf(left, 0f)
    while (x < minOf(right, size.width)) {
        val source = clip.sourceIn + (viewport.timeAt(x) - clip.start) * clip.speed
        val index = (source / sourceDuration * waveform.size).toInt().coerceIn(0, waveform.lastIndex)
        val amplitude = (waveform[index] * height * 0.42).toFloat().coerceAtLeast(0.5f)
        drawLine(color, Offset(x, center - amplitude), Offset(x, center + amplitude), strokeWidth = 1.5f)
        x += step
    }
}

private fun DrawScope.drawLabel(textMeasurer: TextMeasurer, text: String, style: TextStyle, x: Float, y: Float, maxWidth: Float) {
    if (maxWidth < 16f) return
    drawText(
        textMeasurer = textMeasurer,
        text = text,
        topLeft = Offset(x, y),
        style = style,
        overflow = TextOverflow.Ellipsis,
        softWrap = false,
        maxLines = 1,
        size = Size(maxWidth, (style.lineHeight.toPx()).coerceAtLeast(1f)),
    )
}

private fun DrawScope.drawRuler(
    state: EditorState,
    viewport: TimelineViewport,
    px: TimelinePx,
    colors: KlipaColors,
    styles: KlipaTextStyles,
    textMeasurer: TextMeasurer,
) {
    drawRect(colors.raised, Offset.Zero, Size(size.width, px.ruler))
    drawLine(colors.outline, Offset(0f, px.ruler - 0.5f), Offset(size.width, px.ruler - 0.5f), strokeWidth = 1f)
    val fps = state.project.fps
    val scale = rulerScale(viewport.pxPerSecond, fps, px.minMajorTick, px.minMinorTick)
    val labelStyle = styles.timecodeSmall.copy(color = colors.textSecondary)
    // The playhead cap covers the ruler around the center; labels there would be cut off.
    val capReach = PLAYHEAD_CAP_HALF_WIDTH.toPx() + 2.dp.toPx()
    for ((time, major) in rulerTicks(scale, viewport.timeAt(px.header), viewport.timeAt(size.width))) {
        val x = viewport.xOf(time)
        if (x < px.header) continue
        val tickTop = if (major) px.ruler * 0.55f else px.ruler * 0.78f
        drawLine(if (major) colors.textSecondary else colors.textTertiary, Offset(x, tickTop), Offset(x, px.ruler), strokeWidth = 1f)
        if (!major) continue
        val label = rulerLabel(time, fps, scale.major)
        val labelX = x + 3.dp.toPx()
        val labelWidth = textMeasurer.measure(label, labelStyle).size.width
        if (labelX < viewport.playheadX + capReach && labelX + labelWidth > viewport.playheadX - capReach) continue
        drawLabel(textMeasurer, label, labelStyle, labelX, 3.dp.toPx(), px.minMajorTick - 6.dp.toPx())
    }
}

private fun DrawScope.drawPlayhead(px: TimelinePx, colors: KlipaColors) {
    val x = (size.width / 2).roundToInt().toFloat()
    drawLine(colors.accent, Offset(x, 0f), Offset(x, size.height), strokeWidth = px.stroke)
    val w = PLAYHEAD_CAP_HALF_WIDTH.toPx()
    val cap = Path().apply {
        moveTo(x - w, 0f)
        lineTo(x + w, 0f)
        lineTo(x + w, w * 1.2f)
        lineTo(x, w * 2.2f)
        lineTo(x - w, w * 1.2f)
        close()
    }
    drawPath(cap, colors.accent)
}

/** `0.5×`, `2×`, `1.25×`. */
fun formatSpeed(speed: Double): String =
    String.format(Locale.ROOT, "%s×", if (speed % 1.0 == 0.0) speed.toLong().toString() else "%.2f".format(Locale.ROOT, speed).trimEnd('0').trimEnd('.'))

/** `2.4s` under a minute, `1:05` above. */
internal fun formatDuration(seconds: Double): String {
    if (seconds < 60) return String.format(Locale.ROOT, "%.1fs", seconds)
    val whole = seconds.roundToInt()
    return String.format(Locale.ROOT, "%d:%02d", whole / 60, whole % 60)
}

@Composable
private fun TrackHeaders(
    lanes: List<Lane>,
    scroll: Float,
    topPx: Float,
    onTrackChange: (String, (Track) -> Track) -> Unit,
    onReorder: (String, TrackDirection) -> Unit,
    onDelete: (String) -> Unit,
) {
    val colors = Klipa.colors
    val density = LocalDensity.current
    Box(
        Modifier
            .offset { IntOffset(0, topPx.roundToInt()) }
            .width(TimelineDimens.header)
            .fillMaxHeight()
            .background(colors.panel)
            .clipToBounds(),
    ) {
        Box(Modifier.align(Alignment.TopEnd).width(1.dp).fillMaxHeight().background(colors.outline))
        for ((index, lane) in lanes.withIndex()) {
            TrackHeader(
                track = lane.track,
                canMoveUp = index > 0,
                canMoveDown = index < lanes.lastIndex,
                onTrackChange = onTrackChange,
                onReorder = onReorder,
                onDelete = onDelete,
                modifier = Modifier
                    .offset { IntOffset(0, (lane.top - scroll).roundToInt()) }
                    .width(TimelineDimens.header)
                    .height(with(density) { lane.height.toDp() }),
            )
        }
    }
}

@Composable
private fun TrackHeader(
    track: Track,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onTrackChange: (String, (Track) -> Track) -> Unit,
    onReorder: (String, TrackDirection) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier,
) {
    val colors = Klipa.colors
    var menu by remember { mutableStateOf(false) }
    val (icon, label) = when (track.kind) {
        TrackKind.Video -> Icons.Rounded.Movie to R.string.track_video
        TrackKind.Image -> Icons.Rounded.Image to R.string.track_image
        TrackKind.Audio -> Icons.Rounded.MusicNote to R.string.track_audio
        TrackKind.Text -> Icons.Rounded.TextFields to R.string.track_text
    }
    val name = stringResource(label)
    val options = stringResource(R.string.track_options, track.name.ifBlank { name })
    Box(modifier.clickable(onClickLabel = options) { menu = true }, contentAlignment = Alignment.Center) {
        Row(horizontalArrangement = Arrangement.spacedBy(KlipaSpacing.xxs), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = name, tint = if (track.hidden) colors.textTertiary else colors.textSecondary, modifier = Modifier.size(16.dp))
            val status: ImageVector? = when {
                track.locked -> Icons.Rounded.Lock
                track.muted -> Icons.AutoMirrored.Rounded.VolumeOff
                track.hidden -> Icons.Rounded.VisibilityOff
                else -> null
            }
            if (status != null) Icon(status, contentDescription = null, tint = colors.accent, modifier = Modifier.size(11.dp))
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            fun run(action: () -> Unit): () -> Unit = {
                menu = false
                action()
            }
            if (track.kind != TrackKind.Text) {
                TrackMenuItem(if (track.muted) R.string.track_unmute else R.string.track_mute, onClick = run { onTrackChange(track.id) { it.copy(muted = !it.muted) } })
            }
            if (track.kind != TrackKind.Audio) {
                TrackMenuItem(if (track.hidden) R.string.track_show else R.string.track_hide, onClick = run { onTrackChange(track.id) { it.copy(hidden = !it.hidden) } })
            }
            TrackMenuItem(if (track.locked) R.string.track_unlock else R.string.track_lock, onClick = run { onTrackChange(track.id) { it.copy(locked = !it.locked) } })
            TrackMenuItem(R.string.track_move_up, enabled = canMoveUp && !track.locked, onClick = run { onReorder(track.id, TrackDirection.Up) })
            TrackMenuItem(R.string.track_move_down, enabled = canMoveDown && !track.locked, onClick = run { onReorder(track.id, TrackDirection.Down) })
            TrackMenuItem(R.string.track_delete, enabled = !track.locked, onClick = run { onDelete(track.id) })
        }
    }
}

@Composable
private fun TrackMenuItem(text: Int, enabled: Boolean = true, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(stringResource(text)) }, enabled = enabled, onClick = onClick)
}

@Composable
private fun EmptyTimeline(onAddMedia: () -> Unit, modifier: Modifier) {
    val colors = Klipa.colors
    // A solid card, so the playhead line passes behind the message rather than through it.
    Box(modifier.padding(KlipaSpacing.lg), contentAlignment = Alignment.Center) {
    Column(
        Modifier
            .background(colors.raised, KlipaShapes.control)
            .border(1.dp, colors.outline, KlipaShapes.control)
            .padding(horizontal = KlipaSpacing.xl, vertical = KlipaSpacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Rounded.VideoLibrary, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(KlipaSpacing.sm))
        Text(stringResource(R.string.timeline_empty_title), style = MaterialTheme.typography.titleSmall, color = colors.textPrimary)
        Spacer(Modifier.height(KlipaSpacing.xxs))
        Text(stringResource(R.string.timeline_empty_body), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        Spacer(Modifier.height(KlipaSpacing.md))
        Button(onClick = onAddMedia) {
            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(KlipaSpacing.xs))
            Text(stringResource(R.string.timeline_add_media))
        }
    }
    }
}
