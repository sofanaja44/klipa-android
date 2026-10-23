// Media3's composition, effect and audio-processing APIs are marked unstable; Klipa
// pins the Media3 version and builds on them deliberately.
@file:OptIn(UnstableApi::class)

package com.example.klipa.core.media

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Multiplies PCM audio by a gain. [gainAt] receives the position in microseconds of the
 * audio it is given since the start of the item (after any speed change); it is sampled
 * every [BLOCK_FRAMES] frames, which is smooth enough for fades and volume keyframes.
 */
class GainAudioProcessor(private val gainAt: (positionUs: Long) -> Float) : BaseAudioProcessor() {
    private var positionUs = 0L

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val bytes = inputBuffer.remaining()
        if (bytes == 0) return
        val format = inputAudioFormat
        val input = inputBuffer.order(ByteOrder.nativeOrder())
        val output = replaceOutputBuffer(bytes)
        val frames = bytes / format.bytesPerFrame
        var frame = 0
        while (frame < frames) {
            val block = minOf(BLOCK_FRAMES, frames - frame)
            val gain = gainAt(positionUs + frame * 1_000_000L / format.sampleRate)
            repeat(block * format.channelCount) {
                if (format.encoding == C.ENCODING_PCM_FLOAT) {
                    output.putFloat(input.float * gain)
                } else {
                    output.putShort((input.short * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
                }
            }
            frame += block
        }
        positionUs += frames * 1_000_000L / format.sampleRate
        output.flip()
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        positionUs = streamMetadata.positionOffsetUs.coerceAtLeast(0L)
    }

    override fun onReset() {
        positionUs = 0L
    }

    private companion object {
        const val BLOCK_FRAMES = 256
    }
}
