// Transformer's composition export is marked unstable; Klipa pins the Media3 version and
// builds on it deliberately.
@file:OptIn(UnstableApi::class)

package com.example.klipa.feature.export

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
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

/** [ExportEngine] on Media3 Transformer: H.264 video and AAC audio in MP4. */
class TransformerEngine(private val context: Context) : ExportEngine {
    private var transformer: Transformer? = null
    private val holder = ProgressHolder()

    override fun start(composition: Composition, output: File, videoBitrate: Int, listener: ExportEngine.Listener) {
        val encoders = DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(videoBitrate).build())
            // A device that cannot encode the requested size or bitrate gets the closest it can.
            .setEnableFallback(true)
            .build()
        val transformer = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setEncoderFactory(encoders)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    this@TransformerEngine.transformer = null
                    listener.onCompleted(exportResult.fileSizeBytes, exportResult.durationMs)
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    this@TransformerEngine.transformer = null
                    listener.onError(exportException)
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
        transformer?.cancel()
        transformer = null
    }
}
