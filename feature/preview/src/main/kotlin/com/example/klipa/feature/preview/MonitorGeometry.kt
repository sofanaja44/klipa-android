package com.example.klipa.feature.preview

import com.example.klipa.core.media.fitSize
import com.example.klipa.core.media.textLayerSize
import com.example.klipa.core.model.Project
import com.example.klipa.core.model.TextStyle
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Where a visible layer sits in the project frame, in project pixels (origin top left, y
 * down): its center, its size before scaling, its scale and its clockwise rotation in
 * degrees. The monitor draws, hit-tests and outlines clips with it.
 */
data class LayerBox(
    val clipId: String,
    val centerX: Double,
    val centerY: Double,
    val width: Double,
    val height: Double,
    val scale: Double,
    val rotation: Double,
) {
    /** True when the project point [x], [y] is inside the box, or within [slop] of it. */
    fun contains(x: Double, y: Double, slop: Double = 0.0): Boolean {
        val radians = Math.toRadians(rotation)
        val dx = x - centerX
        val dy = y - centerY
        // Undo the rotation to measure along the box's own axes.
        val along = dx * cos(radians) + dy * sin(radians)
        val across = -dx * sin(radians) + dy * cos(radians)
        val size = abs(scale)
        return abs(along) <= width * size / 2 + slop && abs(across) <= height * size / 2 + slop
    }

    /** The corners, clockwise from the top left, in project pixels. */
    fun corners(): List<Pair<Double, Double>> {
        val radians = Math.toRadians(rotation)
        val halfWidth = width * abs(scale) / 2
        val halfHeight = height * abs(scale) / 2
        return listOf(-1.0 to -1.0, 1.0 to -1.0, 1.0 to 1.0, -1.0 to 1.0).map { (sx, sy) ->
            val x = sx * halfWidth
            val y = sy * halfHeight
            (centerX + x * cos(radians) - y * sin(radians)) to (centerY + x * sin(radians) + y * cos(radians))
        }
    }
}

/**
 * The boxes of the layers visible at [time], topmost first, as the monitor stacks them:
 * text above media, upper tracks above lower ones. Media fill the frame as far as their
 * shape allows before their own scale; [textSize] measures a title.
 */
fun layerBoxes(
    project: Project,
    time: Double,
    textSize: (TextStyle) -> Pair<Int, Int>? = { textLayerSize(it) },
): List<LayerBox> {
    val text = mutableListOf<LayerBox>()
    val media = mutableListOf<LayerBox>()
    for (layer in monitorLayers(project, time)) {
        val clip = layer.clip
        val style = clip.text
        val size = when {
            style != null -> textSize(style)?.let { (w, h) -> w.toDouble() to h.toDouble() } ?: continue
            layer.asset != null -> fitSize(project, clip, layer.asset)?.let { (w, h) -> w.toDouble() to h.toDouble() }
                ?: (project.width.toDouble() to project.height.toDouble())
            else -> continue
        }
        val t = clip.transform
        val box = LayerBox(clip.id, project.width / 2.0 + t.x, project.height / 2.0 + t.y, size.first, size.second, t.scale, t.rotation)
        if (style != null) text += box else media += box
    }
    return text.asReversed() + media.asReversed()
}

/** The topmost layer at project point [x], [y], within [slop] project pixels. */
fun hitTest(boxes: List<LayerBox>, x: Double, y: Double, slop: Double = 0.0): String? =
    boxes.firstOrNull { it.contains(x, y) }?.clipId ?: boxes.firstOrNull { it.contains(x, y, slop) }?.clipId
