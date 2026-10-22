package com.example.klipa.feature.preview

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.example.klipa.core.model.MediaAsset
import com.example.klipa.core.model.MediaKind
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext

/** Still frames of media, for the program monitor and thumbnails. */
interface FrameSource {
    /** The frame shown at [sourceTime] seconds into [asset], at most [maxSize] px on its long side. */
    suspend fun frame(asset: MediaAsset, sourceTime: Double, maxSize: Int): ImageBitmap?
}

/**
 * Decodes frames with [MediaMetadataRetriever] and stills with the platform decoders.
 * Frame-accurate but not real-time: fine for scrubbing, replaced by the ExoPlayer
 * pipeline for playback.
 */
class MediaFrameSource(context: Context) : FrameSource {
    private val resolver: ContentResolver = context.applicationContext.contentResolver
    private val appContext = context.applicationContext

    // MediaMetadataRetriever is not thread-safe; one worker thread serializes all decoding.
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "klipa-frames") }
    private val decoder = worker.asCoroutineDispatcher()

    private val retrievers = object : LinkedHashMap<String, MediaMetadataRetriever>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MediaMetadataRetriever>): Boolean {
            if (size <= MAX_RETRIEVERS) return false
            eldest.value.release()
            return true
        }
    }

    private val stills = object : LruCache<String, ImageBitmap>(MAX_STILL_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    override suspend fun frame(asset: MediaAsset, sourceTime: Double, maxSize: Int): ImageBitmap? = withContext(decoder) {
        try {
            when (asset.kind) {
                MediaKind.Video -> videoFrame(asset, sourceTime, maxSize)
                MediaKind.Image -> still(asset, maxSize)
                MediaKind.Audio -> null
            }
        } catch (e: Exception) {
            // Missing permission, deleted file or an undecodable frame: show nothing.
            null
        }
    }

    private fun videoFrame(asset: MediaAsset, sourceTime: Double, maxSize: Int): ImageBitmap? {
        val retriever = retrievers.getOrPut(asset.url) {
            MediaMetadataRetriever().apply { setDataSource(appContext, Uri.parse(asset.url)) }
        }
        val micros = (sourceTime.coerceAtLeast(0.0) * 1_000_000).toLong()
        val option = MediaMetadataRetriever.OPTION_CLOSEST
        val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            retriever.getScaledFrameAtTime(micros, option, maxSize, maxSize)
        } else {
            retriever.getFrameAtTime(micros, option)?.let { scaleDown(it, maxSize) }
        }
        return bitmap?.asImageBitmap()
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

    /** Frees the decoders and stops the worker. Call once, when the owner is destroyed. */
    fun release() {
        worker.execute {
            retrievers.values.forEach { it.release() }
            retrievers.clear()
            stills.evictAll()
        }
        worker.shutdown()
    }

    private companion object {
        const val MAX_RETRIEVERS = 4
        const val MAX_STILL_BYTES = 32 * 1024 * 1024
    }
}
