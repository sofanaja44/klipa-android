package com.example.klipa.feature.timeline

import android.util.LruCache
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import com.example.klipa.core.media.FrameSource
import com.example.klipa.core.model.MediaAsset
import com.example.klipa.core.model.MediaKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.floor

/** Thumbnail spacings in source seconds; the finest one wider than a tile is used. */
private val ThumbnailSteps = listOf(0.5, 1.0, 2.0, 5.0, 10.0, 30.0, 60.0, 300.0, 900.0, 3600.0)

/**
 * Timeline thumbnails, decoded in the background and kept in memory. [get] can be called
 * while drawing: a missing thumbnail is requested and [version] changes when it arrives,
 * which redraws whoever read it.
 */
@Stable
class ThumbnailCache(private val frames: FrameSource, private val scope: CoroutineScope, private val heightPx: Int) {
    private val bitmaps = LruCache<String, ImageBitmap>(MAX_THUMBNAILS)
    private val pending = HashSet<String>()
    private val unreadable = HashSet<String>()

    var version by mutableIntStateOf(0)
        private set

    /** The step between thumbnails for tiles covering [tileSeconds] of source each. */
    fun step(tileSeconds: Double): Double = ThumbnailSteps.firstOrNull { it >= tileSeconds } ?: ThumbnailSteps.last()

    /** The thumbnail for [sourceTime] snapped to [step], or null until it is decoded. */
    fun get(asset: MediaAsset, sourceTime: Double, step: Double): ImageBitmap? {
        val time = if (asset.kind == MediaKind.Image) 0.0 else floor(sourceTime / step) * step
        val key = "${asset.id}@$time"
        bitmaps.get(key)?.let { return it }
        if (key !in unreadable && pending.add(key)) {
            scope.launch {
                try {
                    val bitmap = frames.thumbnail(asset, time, heightPx * 2)
                    // Undecodable frames are not retried on every redraw.
                    if (bitmap != null) bitmaps.put(key, bitmap) else unreadable += key
                } finally {
                    pending.remove(key)
                    version++
                }
            }
        }
        return null
    }

    private companion object {
        const val MAX_THUMBNAILS = 240
    }
}
