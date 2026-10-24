// Transformer's composition export is marked unstable; Klipa pins the Media3 version and
// builds on it deliberately.
@file:OptIn(UnstableApi::class)

package com.example.klipa.feature.export

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.DefaultMuxer
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.FrameworkMuxer
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import java.io.File

/** Renders one composition to an MP4 file. Used from the main thread. */
interface ExportEngine {
    fun start(composition: Composition, output: File, videoBitrate: Int, listener: Listener)

    /** How far the export is, in 0..1, or null while that is unknown. */
    fun progress(): Float?

    fun cancel()

    interface Listener {
        fun onCompleted(sizeBytes: Long, durationMs: Long)

        fun onError(error: ExportException)
    }
}

/** Muxers to write the MP4 with, in the order they are tried. */
enum class ExportMuxer {
    /**
     * Android's MediaMuxer. It takes whatever the device's own encoder produces; Media3's
     * muxer rejects some encoders' H.264 output ("Invalid Nal units").
     */
    Platform,

    /** Media3's own MP4 muxer, Transformer's default. */
    Media3,
}

/**
 * Whether an export that failed with [error] on one muxer is worth one more try on the
 * other: the muxer, or a runtime check in it, gave up, not the media or the encoder.
 */
fun retriesWithOtherMuxer(error: ExportException): Boolean = error.errorCode in setOf(
    ExportException.ERROR_CODE_FAILED_RUNTIME_CHECK,
    ExportException.ERROR_CODE_MUXING_FAILED,
    ExportException.ERROR_CODE_MUXING_APPEND,
)

/**
 * [ExportEngine] on Media3 Transformer: H.264 video and AAC audio in MP4, written by
 * Android's muxer first and Media3's if that one fails.
 */
class TransformerEngine(private val context: Context) : ExportEngine {
    private var transformer: Transformer? = null
    private val holder = ProgressHolder()

    override fun start(composition: Composition, output: File, videoBitrate: Int, listener: ExportEngine.Listener) {
        start(composition, output, videoBitrate, listener, ExportMuxer.entries)
    }

    private fun start(composition: Composition, output: File, videoBitrate: Int, listener: ExportEngine.Listener, muxers: List<ExportMuxer>) {
        val encoders = DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(videoBitrate).build())
            // A device that cannot encode the requested size or bitrate gets the closest it can.
            .setEnableFallback(true)
            .build()
        val transformer = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setEncoderFactory(encoders)
            .setMuxerFactory(
                when (muxers.first()) {
                    ExportMuxer.Platform -> FrameworkMuxer.Factory()
                    ExportMuxer.Media3 -> DefaultMuxer.Factory()
                },
            )
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    this@TransformerEngine.transformer = null
                    listener.onCompleted(exportResult.fileSizeBytes, exportResult.durationMs)
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    // A cancelled export reports nothing.
                    if (this@TransformerEngine.transformer == null) return
                    this@TransformerEngine.transformer = null
                    val rest = muxers.drop(1)
                    if (rest.isNotEmpty() && retriesWithOtherMuxer(exportException)) {
                        Log.w(TAG, "Export failed with the ${muxers.first()} muxer; trying ${rest.first()}", exportException)
                        output.delete()
                        start(composition, output, videoBitrate, listener, rest)
                    } else {
                        listener.onError(exportException)
                    }
                }
            })
            .build()
        this.transformer = transformer
        transformer.start(composition, output.absolutePath)
    }

    override fun progress(): Float? {
        val transformer = transformer ?: return null
        return if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) holder.progress / 100f else null
    }

    override fun cancel() {
        val transformer = transformer ?: return
        this.transformer = null
        transformer.cancel()
    }

    private companion object {
        const val TAG = "KlipaExport"
    }
}
