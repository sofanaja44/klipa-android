package com.example.klipa.core.media

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Process
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.example.klipa.core.model.MediaAsset
import com.example.klipa.core.model.MediaKind
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext

/** Still frames of media, for the program monitor and thumbnails. */
interface FrameSource {
    /**
     * The frame shown at [sourceTime] seconds into [asset], at most [maxSize] px on its
     * long side. With [exact] false the nearest key frame is returned instead, which is
     * far quicker to decode and fine while playing.
     */
    suspend fun frame(asset: MediaAsset, sourceTime: Double, maxSize: Int, exact: Boolean = true): ImageBitmap?

    /** A quick thumbnail near [sourceTime] for the timeline. */
    suspend fun thumbnail(asset: MediaAsset, sourceTime: Double, maxSize: Int): ImageBitmap? = frame(asset, sourceTime, maxSize, exact = false)
}

/**
 * Decodes frames with [MediaMetadataRetriever] and stills with the platform decoders.
 * Monitor frames and timeline thumbnails decode on separate threads, so a long filmstrip
 * never holds up the frame under the playhead.
 */
class MediaFrameSource(context: Context) : FrameSource {
    private val resolver: ContentResolver = context.applicationContext.contentResolver
    private val appContext = context.applicationContext

    private val stills = object : LruCache<String, ImageBitmap>(MAX_STILL_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    private val monitor = DecoderLane("klipa-frames", Process.THREAD_PRIORITY_DISPLAY)
    private val thumbnails = DecoderLane("klipa-thumbnails", Process.THREAD_PRIORITY_BACKGROUND)

    override suspend fun frame(asset: MediaAsset, sourceTime: Double, maxSize: Int, exact: Boolean): ImageBitmap? =
        monitor.decode(asset, sourceTime, maxSize, if (exact) MediaMetadataRetriever.OPTION_CLOSEST else MediaMetadataRetriever.OPTION_CLOSEST_SYNC)

    override suspend fun thumbnail(asset: MediaAsset, sourceTime: Double, maxSize: Int): ImageBitmap? =
        thumbnails.decode(asset, sourceTime, maxSize, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)

    /** Frees the decoders and stops the workers. Call once, when the owner is destroyed. */
    fun release() {
        monitor.release()
        thumbnails.release()
        stills.evictAll()
    }

    /**
     * One decoding thread with its own retrievers: MediaMetadataRetriever is not
     * thread-safe, so each instance stays on the thread that created it.
     */
    private inner class DecoderLane(name: String, priority: Int) {
        private val worker: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
            Thread({ Process.setThreadPriority(priority); runnable.run() }, name)
        }
        private val dispatcher: CoroutineDispatcher = worker.asCoroutineDispatcher()

        private val retrievers = object : LinkedHashMap<String, MediaMetadataRetriever>(4, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MediaMetadataRetriever>): Boolean {
                if (size <= MAX_RETRIEVERS) return false
                eldest.value.release()
                return true
            }
        }

        suspend fun decode(asset: MediaAsset, sourceTime: Double, maxSize: Int, option: Int): ImageBitmap? = withContext(dispatcher) {
            try {
                when (asset.kind) {
                    MediaKind.Video -> videoFrame(asset, sourceTime, maxSize, option)
                    MediaKind.Image -> still(asset, maxSize)
                    MediaKind.Audio -> null
                }
            } catch (e: Exception) {
                // Missing permission, deleted file or an undecodable frame: show nothing.
                null
            }
        }

        private fun videoFrame(asset: MediaAsset, sourceTime: Double, maxSize: Int, option: Int): ImageBitmap? {
            val retriever = retrievers.getOrPut(asset.url) {
                MediaMetadataRetriever().apply { setDataSource(appContext, Uri.parse(asset.url)) }
            }
            val micros = (sourceTime.coerceAtLeast(0.0) * 1_000_000).toLong()
            val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(micros, option, maxSize, maxSize)
            } else {
                retriever.getFrameAtTime(micros, option)?.let { scaleDown(it, maxSize) }
            }
            return bitmap?.asImageBitmap()
        }

        fun release() {
            worker.execute {
                retrievers.values.forEach { it.release() }
                retrievers.clear()
            }
            worker.shutdown()
        }
    }

    private fun still(asset: MediaAsset, maxSize: Int): ImageBitmap? {
        val key = "${asset.url}@$maxSize"
        stills.get(key)?.let { return it }
        val uri = Uri.parse(asset.url)
        val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // ImageDecoder applies EXIF orientation.
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                val longSide = maxOf(info.size.width, info.size.height)
                if (longSide > maxSize) {
                    val ratio = maxSize.toFloat() / longSide
                    decoder.setTargetSize((info.size.width * ratio).toInt().coerceAtLeast(1), (info.size.height * ratio).toInt().coerceAtLeast(1))
                }
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSize) sample *= 2
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
        } ?: return null
        return bitmap.asImageBitmap().also { stills.put(key, it) }
    }

    private fun scaleDown(bitmap: Bitmap, maxSize: Int): Bitmap {
        val longSide = maxOf(bitmap.width, bitmap.height)
        if (longSide <= maxSize) return bitmap
        val ratio = maxSize.toFloat() / longSide
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt().coerceAtLeast(1), (bitmap.height * ratio).toInt().coerceAtLeast(1), true)
    }

    private companion object {
        const val MAX_RETRIEVERS = 4
        const val MAX_STILL_BYTES = 32 * 1024 * 1024
    }
}
