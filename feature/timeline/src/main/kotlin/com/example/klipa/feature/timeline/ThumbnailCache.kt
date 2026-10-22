package com.example.klipa.feature.timeline

import android.os.SystemClock
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.floor

/** Thumbnail spacings in source seconds; the finest one wider than a tile is used. */
private val ThumbnailSteps = listOf(0.5, 1.0, 2.0, 5.0, 10.0, 30.0, 60.0, 300.0, 900.0, 3600.0)

/**
 * Timeline thumbnails, decoded in the background and kept in memory. [get] can be called
 * while drawing: a missing thumbnail is queued and [version] changes when it arrives,
 * which redraws whoever read it.
 *
 * The newest requests are decoded first and requests not drawn for a moment are dropped,
 * so after scrubbing or zooming the visible thumbnails arrive first. All bookkeeping
 * happens on the main thread.
 */
@Stable
class ThumbnailCache(private val frames: FrameSource, private val scope: CoroutineScope, private val heightPx: Int) {
    private class Request(val key: String, val asset: MediaAsset, val time: Double)

    private val bitmaps = LruCache<String, ImageBitmap>(MAX_THUMBNAILS)
    private val queue = ArrayDeque<Request>()
    private val queued = HashSet<String>()
    private val lastWanted = HashMap<String, Long>()
    private val unreadable = HashSet<String>()
    private var worker: Job? = null

    var version by mutableIntStateOf(0)
        private set

    /** The step between thumbnails for tiles covering [tileSeconds] of source each. */
    fun step(tileSeconds: Double): Double = ThumbnailSteps.firstOrNull { it >= tileSeconds } ?: ThumbnailSteps.last()

    /** The thumbnail for [sourceTime] snapped to [step], or null until it is decoded. */
    fun get(asset: MediaAsset, sourceTime: Double, step: Double): ImageBitmap? {
        val time = if (asset.kind == MediaKind.Image) 0.0 else floor(sourceTime / step) * step
        val key = "${asset.id}@$time"
        bitmaps.get(key)?.let { return it }
        // Undecodable frames are not retried on every redraw.
        if (key in unreadable) return null
        lastWanted[key] = SystemClock.uptimeMillis()
        if (queued.add(key)) {
            queue.addLast(Request(key, asset, time))
            if (worker?.isActive != true) worker = scope.launch { drain() }
        }
        return null
    }

    private suspend fun drain() {
        while (queue.isNotEmpty()) {
            val request = queue.removeLast()
            val wanted = lastWanted.remove(request.key) ?: 0L
            if (SystemClock.uptimeMillis() - wanted > STALE_AFTER_MS) {
                // Scrolled away: it is queued again if it comes back into view.
                queued.remove(request.key)
                continue
            }
            val bitmap = frames.thumbnail(request.asset, request.time, heightPx * 2)
            if (bitmap != null) bitmaps.put(request.key, bitmap) else unreadable += request.key
            // Still marked queued while decoding, so a redraw meanwhile does not queue it twice.
            queued.remove(request.key)
            version++
        }
    }

    private companion object {
        const val MAX_THUMBNAILS = 240
        const val STALE_AFTER_MS = 1_000L
    }
}
