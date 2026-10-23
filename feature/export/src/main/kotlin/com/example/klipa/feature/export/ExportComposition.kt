// Media3's composition and effect APIs are marked unstable; Klipa pins the Media3 version
// and builds on them deliberately.
@file:OptIn(UnstableApi::class)

package com.example.klipa.feature.export

import android.graphics.Bitmap
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import com.example.klipa.core.media.ProjectComposition
import com.example.klipa.core.media.buildComposition
import com.example.klipa.core.media.renderText
import com.example.klipa.core.media.textOverlayEffect
import com.example.klipa.core.model.MediaKind
import com.example.klipa.core.model.Project
import com.example.klipa.core.model.TextStyle

/**
 * The composition Transformer renders for [project]: the same layers the preview plays,
 * with every video layer composited, then text drawn over the frame and the frame scaled
 * to the chosen resolution. Null when the project has no picture to export.
 */
fun buildExportComposition(
    project: Project,
    settings: ExportSettings,
    render: (TextStyle) -> Bitmap? = { renderText(it) },
): ProjectComposition? {
    val effects = buildList<Effect> {
        // Media shot faster than the project is thinned to its frame rate first, so the
        // effects after this one skip the dropped frames.
        if (project.assets.any { it.kind == MediaKind.Video && it.fps > project.fps + 0.5 }) {
            add(FrameDropEffect.createDefaultFrameDropEffect(project.fps.toFloat()))
        }
        textOverlayEffect(project, render)?.let(::add)
        val (width, height) = outputSize(project, settings)
        if (width != project.width || height != project.height) {
            add(Presentation.createForWidthAndHeight(width, height, Presentation.LAYOUT_SCALE_TO_FIT))
        }
    }
    // Phones record HDR by default. Layers, text and the H.264 output need one color
    // space, so HDR media is tone-mapped to SDR (OpenGL tone mapping needs API 29).
    val hdrMode = if (Build.VERSION.SDK_INT >= 29) Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL else Composition.HDR_MODE_KEEP_HDR
    val built = buildComposition(project, allowLayering = true, videoEffects = effects, hdrMode = hdrMode) ?: return null
    return built.takeIf { it.plan.videoSequences.isNotEmpty() }
}
