package com.example.klipa.feature.preview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.unit.dp
import com.example.klipa.core.designsystem.Klipa
import com.example.klipa.core.model.Project
import com.example.klipa.core.model.TextStyle
import com.example.klipa.core.media.textLayerSize
import kotlin.math.PI
import kotlin.math.abs

/** What touching the monitor does to clips. Coordinates are project pixels. */
interface MonitorActions {
    /** Selects the clip tapped, or clears the selection when [clipId] is null. */
    fun select(clipId: String?)

    /** A tap on the clip that is already selected, e.g. to edit a title's words. */
    fun open(clipId: String)

    /** A drag or pinch on the selected clip begins: one undo step until [endTransform]. */
    fun beginTransform()

    /**
     * The gesture so far, from where it began: moved by [dx], [dy], scaled by [scale] and
     * turned clockwise by [rotation] degrees. Positions within [snap] of a guide snap to it.
     */
    fun transform(clipId: String, dx: Double, dy: Double, scale: Double, rotation: Double, snap: Double)

    fun endTransform()
}

/** Distance, on screen, within which a dragged clip snaps to the frame's center lines. */
private val SnapDistance = 8.dp

/** Extra reach, on screen, around a clip for starting a drag on it. */
private val TouchReach = 12.dp

/**
 * Lets the user arrange clips on the monitor: tap to select (tap a selected title to edit
 * it), drag the selected clip to move it, pinch with two fingers to scale and turn it.
 * Draws the selection's outline and, while it moves, the center lines it snaps to.
 * [projectPerPx] converts screen pixels in this box to project pixels.
 */
@Composable
internal fun MonitorEditing(project: Project, time: Double, selectedId: String?, actions: MonitorActions, projectPerPx: Float) {
    val colors = Klipa.colors
    // Titles are measured once per style, not on every frame of playback.
    val textSizes = remember { HashMap<TextStyle, Pair<Int, Int>?>() }
    val boxes = remember(project, time) { layerBoxes(project, time) { style -> textSizes.getOrPut(style) { textLayerSize(style) } } }
    val currentBoxes by rememberUpdatedState(boxes)
    val currentSelected by rememberUpdatedState(selectedId)
    var transforming by remember { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(actions, projectPerPx) {
                val snap = SnapDistance.toPx() * projectPerPx
                val reach = TouchReach.toPx() * projectPerPx
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val startX = down.position.x * projectPerPx.toDouble()
                    val startY = down.position.y * projectPerPx.toDouble()
                    val selected = currentBoxes.firstOrNull { it.clipId == currentSelected }
                    var pan = Offset.Zero
                    var zoom = 1f
                    var rotation = 0f
                    var moved = false
                    var target: String? = null
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.changes.none { it.pressed }) break
                        pan += event.calculatePan()
                        zoom *= event.calculateZoom()
                        rotation += event.calculateRotation()
                        if (!moved) {
                            // The same slop test as Compose's transform gestures.
                            val centroidSize = event.calculateCentroidSize(useCurrent = false)
                            val zoomMotion = abs(1 - zoom) * centroidSize
                            val rotationMotion = abs(rotation * PI.toFloat() * centroidSize / 180f)
                            if (pan.getDistance() > viewConfiguration.touchSlop || zoomMotion > viewConfiguration.touchSlop ||
                                rotationMotion > viewConfiguration.touchSlop
                            ) {
                                moved = true
                                // Only the selected clip moves: a drag that starts on it, or a
                                // pinch anywhere. Tapping first keeps the main video from
                                // moving by accident.
                                val pinching = event.changes.count { it.pressed } > 1
                                if (selected != null && (pinching || selected.contains(startX, startY, reach.toDouble()))) {
                                    target = selected.clipId
                                    transforming = true
                                    actions.beginTransform()
                                }
                            }
                        }
                        val clipId = target
                        if (clipId != null) {
                            actions.transform(clipId, pan.x * projectPerPx.toDouble(), pan.y * projectPerPx.toDouble(), zoom.toDouble(), rotation.toDouble(), snap.toDouble())
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    }
                    if (target != null) {
                        transforming = false
                        actions.endTransform()
                    } else if (!moved) {
                        val hit = hitTest(currentBoxes, startX, startY)
                        if (hit != null && hit == currentSelected) actions.open(hit) else actions.select(hit)
                    }
                }
            },
    )
    Canvas(Modifier.fillMaxSize()) {
        val box = boxes.firstOrNull { it.clipId == selectedId } ?: return@Canvas
        val pxPerProject = 1 / projectPerPx
        if (transforming) {
            // Center lines show where the clip snapped.
            val dash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))
            if (box.centerX == project.width / 2.0) {
                drawLine(colors.accent, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), 1.dp.toPx(), pathEffect = dash)
            }
            if (box.centerY == project.height / 2.0) {
                drawLine(colors.accent, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1.dp.toPx(), pathEffect = dash)
            }
        }
        val corners = box.corners().map { (x, y) -> Offset((x * pxPerProject).toFloat(), (y * pxPerProject).toFloat()) }
        val outline = Path().apply {
            moveTo(corners[0].x, corners[0].y)
            corners.drop(1).forEach { lineTo(it.x, it.y) }
            close()
        }
        drawPath(outline, colors.accent, style = Stroke(width = 1.5.dp.toPx()))
        for (corner in corners) {
            drawCircle(colors.canvas, radius = 5.dp.toPx(), center = corner)
            drawCircle(colors.accent, radius = 3.5.dp.toPx(), center = corner)
        }
    }
}
