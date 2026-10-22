package com.example.klipa.core.media

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.example.klipa.core.model.MediaAsset
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Computes an asset's waveform: the peak level of its first audio track in 0..1, at up to
 * [BUCKETS_PER_SECOND] values per second and never more than the project format allows.
 */
class WaveformExtractor(context: Context) {
    private val context = context.applicationContext

    /** The waveform, or null if the file has no decodable audio. Cancellable. */
    suspend fun extract(asset: MediaAsset): List<Double>? = withContext(Dispatchers.Default) {
        if (!asset.hasAudio || asset.duration <= 0) return@withContext null
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, Uri.parse(asset.url), null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return@withContext null
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()

            val rate = minOf(BUCKETS_PER_SECOND.toDouble(), MAX_VALUES / asset.duration)
            val peaks = DoubleArray(ceil(asset.duration * rate).toInt().coerceAtLeast(1))
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var float = false
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var idle = 0
            while (true) {
                ensureActive()
                if (!inputDone) {
                    val index = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        val buffer = decoder.getInputBuffer(index)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val index = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                // A decoder that stops producing output after the last input is done.
                if (index == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone && ++idle > MAX_IDLE_POLLS) break
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val output = decoder.outputFormat
                    sampleRate = output.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = output.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    float = output.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        output.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                } else if (index >= 0) {
                    idle = 0
                    val buffer = decoder.getOutputBuffer(index)!!.order(ByteOrder.nativeOrder())
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    var frame = 0L
                    while (buffer.remaining() >= channels * if (float) 4 else 2) {
                        var peak = 0.0
                        repeat(channels) {
                            val sample = if (float) abs(buffer.float.toDouble()) else abs(buffer.short / 32768.0)
                            if (sample > peak) peak = sample
                        }
                        val seconds = info.presentationTimeUs / 1_000_000.0 + frame.toDouble() / sampleRate
                        val bucket = (seconds * rate).toInt()
                        if (bucket in peaks.indices && peak > peaks[bucket]) peaks[bucket] = peak
                        frame++
                    }
                    decoder.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            // Three decimals keep project files small and are finer than any lane can draw.
            peaks.map { (it.coerceIn(0.0, 1.0) * 1000).roundToInt() / 1000.0 }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            null
        } finally {
            try {
                codec?.stop()
            } catch (e: IllegalStateException) {
                // Never started.
            }
            codec?.release()
            extractor.release()
        }
    }

    private companion object {
        const val BUCKETS_PER_SECOND = 20
        // ReaCut's validator accepts at most 100000 waveform values per asset.
        const val MAX_VALUES = 100_000.0
        const val TIMEOUT_US = 10_000L
        const val MAX_IDLE_POLLS = 100
    }
}
