package com.example.klipa

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.klipa.core.designsystem.Klipa
import com.example.klipa.core.designsystem.KlipaSpacing
import com.example.klipa.core.edit.CropCorner
import com.example.klipa.core.edit.FullCrop
import com.example.klipa.core.edit.centeredCrop
import com.example.klipa.core.edit.moveCrop
import com.example.klipa.core.edit.resizeCrop
import com.example.klipa.core.edit.storedCrop
import com.example.klipa.core.media.FrameSource
import com.example.klipa.core.model.Clip
import com.example.klipa.core.model.CropRect
import com.example.klipa.core.model.MediaAsset
import com.example.klipa.core.model.MediaKind
import kotlin.math.abs
import kotlin.math.hypot

/** Shapes offered for a crop, as width / height of the cropped picture; null is free. */
private val CropShapes: List<Pair<String?, Double?>> = listOf(
    null to null,
    "9:16" to 9.0 / 16,
    "16:9" to 16.0 / 9,
    "1:1" to 1.0,
    "4:5" to 4.0 / 5,
)

/** Reach, on screen, of a crop corner handle. */
private val CornerReach = 28.dp

/**
 * Crops a clip: its frame with the crop on top, to move by dragging inside it and resize
 * by dragging a corner, and shapes to start from. Everything is one undo step.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CropSheet(clip: Clip, asset: MediaAsset, sourceTime: Double, frames: FrameSource, onChange: ((Clip) -> Clip) -> Unit, onDismiss: () -> Unit) {
    val colors = Klipa.colors
    val crop = clip.crop ?: FullCrop
    // The chosen shape stays locked while corners are dragged; "Free" unlocks it.
    var shapeLabel by rememberSaveable { mutableStateOf<String?>(null) }
    val pixelShape = CropShapes.firstOrNull { it.first == shapeLabel }?.second
    // In source fractions, a pixel shape r is r × height / width.
    val fractionShape = pixelShape?.let { it * asset.height / asset.width.coerceAtLeast(1) }
    fun set(next: CropRect) = onChange { it.copy(crop = storedCrop(next)) }

    // Fully open: the shapes sit below the frame.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = colors.raised) {
        Column(Modifier.fillMaxWidth().padding(horizontal = KlipaSpacing.lg).padding(bottom = KlipaSpacing.xl)) {
            Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.crop_title), style = MaterialTheme.typography.titleMedium, color = colors.textPrimary, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    shapeLabel = null
                    set(FullCrop)
                }, enabled = clip.crop != null) { Text(stringResource(R.string.adjust_reset)) }
            }
            Text(stringResource(R.string.crop_hint), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
            Spacer(Modifier.height(KlipaSpacing.md))
            CropEditor(asset, sourceTime, frames, crop, fractionShape, onCrop = ::set, modifier = Modifier.fillMaxWidth().height(280.dp))
            Spacer(Modifier.height(KlipaSpacing.md))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(KlipaSpacing.sm)) {
                val chip = FilterChipDefaults.filterChipColors(selectedContainerColor = colors.accentMuted, selectedLabelColor = colors.accent)
                for ((label, shape) in CropShapes) {
                    FilterChip(
                        selected = shapeLabel == label,
                        onClick = {
                            shapeLabel = label
                            if (shape != null) set(centeredCrop(asset.width.toDouble(), asset.height.toDouble(), shape))
                        },
                        label = { if (label == null) Text(stringResource(R.string.crop_free)) else Text(label, style = Klipa.textStyles.timecode) },
                        colors = chip,
                    )
                }
            }
        }
    }
}

@Composable
private fun CropEditor(
    asset: MediaAsset,
    sourceTime: Double,
    frames: FrameSource,
    crop: CropRect,
    shape: Double?,
    onCrop: (CropRect) -> Unit,
    modifier: Modifier,
) {
    val colors = Klipa.colors
    val maxPx = with(LocalDensity.current) { 480.dp.roundToPx() }
    var image by remember(asset.id) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(asset.id, sourceTime) {
        frames.frame(asset, if (asset.kind == MediaKind.Image) 0.0 else sourceTime, maxPx)?.let { image = it }
    }
    val current by rememberUpdatedState(crop)
    val currentShape by rememberUpdatedState(shape)
    val description = stringResource(R.string.crop_title)
    BoxWithConstraints(modifier.background(colors.canvas), contentAlignment = Alignment.Center) {
        val aspect = asset.width.coerceAtLeast(1).toFloat() / asset.height.coerceAtLeast(1)
        val frameWidth = minOf(maxWidth, maxHeight * aspect)
        Box(Modifier.size(frameWidth, frameWidth / aspect)) {
            image?.let { Image(it, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize()) }
            Canvas(
                Modifier
                    .fillMaxSize()
                    .semantics { contentDescription = description }
                    .pointerInput(Unit) {
                        val reach = CornerReach.toPx()
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            val start = current
                            val rect = start.toRect(size.width.toFloat(), size.height.toFloat())
                            val at = down.position
                            val corner = mapOf(
                                CropCorner.TopLeft to rect.topLeft,
                                CropCorner.TopRight to rect.topRight,
                                CropCorner.BottomRight to rect.bottomRight,
                                CropCorner.BottomLeft to rect.bottomLeft,
                            ).entries.filter { (_, point) -> hypot(point.x - at.x, point.y - at.y) <= reach }
                                .minByOrNull { (_, point) -> abs(point.x - at.x) + abs(point.y - at.y) }?.key
                            if (corner == null && !rect.contains(at)) return@awaitEachGesture
                            // The crop follows the finger from where it went down, touch slop included.
                            fun follow(change: PointerInputChange) {
                                change.consume()
                                val dx = (change.position.x - at.x).toDouble() / size.width
                                val dy = (change.position.y - at.y).toDouble() / size.height
                                onCrop(if (corner != null) resizeCrop(start, corner, dx, dy, currentShape) else moveCrop(start, dx, dy))
                            }
                            val first = awaitTouchSlopOrCancellation(down.id) { change, _ -> follow(change) } ?: return@awaitEachGesture
                            drag(first.id, ::follow)
                        }
                    },
            ) {
                val rect = crop.toRect(size.width, size.height)
                val shade = Color.Black.copy(alpha = 0.55f)
                // Dim everything outside the crop.
                drawRect(shade, Offset.Zero, Size(size.width, rect.top))
                drawRect(shade, Offset(0f, rect.bottom), Size(size.width, size.height - rect.bottom))
                drawRect(shade, Offset(0f, rect.top), Size(rect.left, rect.height))
                drawRect(shade, Offset(rect.right, rect.top), Size(size.width - rect.right, rect.height))
                // Thirds help placing the subject.
                for (i in 1..2) {
                    val x = rect.left + rect.width * i / 3
                    val y = rect.top + rect.height * i / 3
                    drawLine(Color.White.copy(alpha = 0.35f), Offset(x, rect.top), Offset(x, rect.bottom), 1.dp.toPx())
                    drawLine(Color.White.copy(alpha = 0.35f), Offset(rect.left, y), Offset(rect.right, y), 1.dp.toPx())
                }
                drawRect(colors.accent, rect.topLeft, rect.size, style = Stroke(width = 2.dp.toPx()))
                for (point in listOf(rect.topLeft, rect.topRight, rect.bottomRight, rect.bottomLeft)) {
                    drawCircle(colors.accent, radius = 6.dp.toPx(), center = point)
                }
            }
        }
    }
}

private fun CropRect.toRect(width: Float, height: Float) =
    Rect((x * width).toFloat(), (y * height).toFloat(), ((x + this.width) * width).toFloat(), ((y + this.height) * height).toFloat())
