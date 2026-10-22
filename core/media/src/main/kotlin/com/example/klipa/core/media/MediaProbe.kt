package com.example.klipa.core.media

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import com.example.klipa.core.model.MediaAsset
import com.example.klipa.core.model.MediaKind
import com.example.klipa.core.model.newId

/** Reads what the editor needs to know about picked files. Call off the main thread. */
class MediaProbe(context: Context) {
    private val context = context.applicationContext
    private val resolver = this.context.contentResolver

    data class Result(val assets: List<MediaAsset>, val failed: Int)

    fun probe(uris: List<Uri>): Result {
        val assets = uris.mapNotNull(::probe)
        return Result(assets, uris.size - assets.size)
    }

    /** A media asset for [uri], or null if it is not readable video, audio or an image. */
    fun probe(uri: Uri): MediaAsset? {
        // Keep access across restarts where the provider allows it.
        try {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            // Not persistable; access lasts while the app runs.
        }
        val name = displayName(uri)
        val type = resolver.getType(uri).orEmpty()
        return try {
            if (type.startsWith("image/")) image(uri, name) else timed(uri, name)
        } catch (e: Exception) {
            null
        }
    }

    private fun displayName(uri: Uri): String =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: uri.lastPathSegment ?: "Media"

    private fun timed(uri: Uri, name: String): MediaAsset? {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            fun meta(key: Int) = retriever.extractMetadata(key)
            val duration = (meta(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return null) / 1000.0
            if (duration <= 0) return null
            val hasVideo = meta(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
            val hasAudio = meta(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
            if (!hasVideo && !hasAudio) return null
            var width = 0
            var height = 0
            var fps = 0.0
            if (hasVideo) {
                width = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                height = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                // Frames come out upright, so report the displayed size.
                val rotation = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                if (rotation % 180 != 0) width = height.also { height = width }
                fps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toDoubleOrNull()?.let { it / duration } ?: 0.0
                } else {
                    0.0
                }
                if (fps <= 0 || fps > 1000) fps = 30.0
            }
            return MediaAsset(
                id = newId(),
                name = name,
                path = uri.toString(),
                url = uri.toString(),
                kind = if (hasVideo) MediaKind.Video else MediaKind.Audio,
                duration = duration,
                width = width,
                height = height,
                fps = fps,
                hasAudio = hasAudio,
            )
        } finally {
            retriever.release()
        }
    }

    private fun image(uri: Uri, name: String): MediaAsset? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        return MediaAsset(
            id = newId(),
            name = name,
            path = uri.toString(),
            url = uri.toString(),
            kind = MediaKind.Image,
            duration = 0.0,
            width = bounds.outWidth,
            height = bounds.outHeight,
            fps = 0.0,
            hasAudio = false,
        )
    }
}
