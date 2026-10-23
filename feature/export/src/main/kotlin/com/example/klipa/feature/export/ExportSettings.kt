package com.example.klipa.feature.export

import com.example.klipa.core.model.Project
import com.example.klipa.core.model.projectDuration
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** What the user picks before exporting: the output's short side in pixels (720 = 720p). */
data class ExportSettings(val shortSide: Int)

/** Standard short sides offered when they are smaller than the project's own. */
private val StandardShortSides = listOf(480, 720, 1080, 1440, 2160)

/** AAC at Media3's default bitrate. */
private const val AUDIO_BITRATE = 128_000

/**
 * The resolutions offered for [project], smallest first: the standard ones below the
 * project's size, then the project's own. Exports never upscale.
 */
fun exportResolutions(project: Project): List<Int> {
    val native = minOf(project.width, project.height)
    return StandardShortSides.filter { it < native } + native
}

/** The resolution selected by default: 1080p, or the project's own if it is smaller. */
fun defaultExportSettings(project: Project): ExportSettings {
    val options = exportResolutions(project)
    return ExportSettings(options.lastOrNull { it <= 1080 } ?: options.first())
}

/**
 * The encoded frame size for [settings]: the project frame scaled to the chosen short
 * side, rounded to even dimensions as video encoders require.
 */
fun outputSize(project: Project, settings: ExportSettings): Pair<Int, Int> {
    val scale = settings.shortSide.toDouble() / minOf(project.width, project.height)
    return even(project.width * scale) to even(project.height * scale)
}

private fun even(value: Double): Int = maxOf(2, (value / 2).roundToInt() * 2)

/**
 * The video bitrate requested from the encoder, in bits per second. It is Media3's own
 * default (0.14 bits per pixel per frame), fixed here so the size estimate holds.
 */
fun videoBitrate(project: Project, settings: ExportSettings): Int {
    val (width, height) = outputSize(project, settings)
    return (width.toLong() * height * project.fps * 0.07 * 2).roundToLong().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

/** Roughly how large the exported file will be, in bytes. */
fun estimatedSizeBytes(project: Project, settings: ExportSettings): Long =
    ((videoBitrate(project, settings) + AUDIO_BITRATE) * projectDuration(project) / 8).roundToLong()

/** Clips whose media file is missing; they export as gaps. */
fun missingMediaClips(project: Project): Int {
    val missing = project.assets.filter { it.missing == true }.map { it.id }.toSet()
    return project.clips.count { it.assetId in missing }
}
