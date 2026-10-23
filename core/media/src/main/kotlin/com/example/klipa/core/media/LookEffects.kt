// Media3's effect APIs are marked unstable; Klipa pins the Media3 version and builds on
// them deliberately.
@file:OptIn(UnstableApi::class)

package com.example.klipa.core.media

import android.content.Context
import android.opengl.GLES20
import androidx.annotation.OptIn
import androidx.annotation.RawRes
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.ConvolutionFunction1D
import androidx.media3.effect.GaussianFunction
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.effect.SeparableConvolution
import com.example.klipa.core.model.MaskShape
import java.io.IOException

/** How the look shader writes alpha. */
enum class LookOutput(internal val code: Int) {
    /** For the compositor, which blends straight alpha. */
    Straight(0),

    /** For a blur that follows, so transparent pixels do not bleed their color. */
    Premultiplied(1),

    /** For a single layer: cut-out parts show black, as over an empty timeline. */
    OverBlack(2),
}

/**
 * Applies a clip's [Look] to each frame. [look] is read for every frame on the GL thread,
 * so the preview can follow edits without rebuilding the composition.
 */
class LookEffect(val look: () -> Look, val output: LookOutput) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        LookShaderProgram(context, useHdr, look, output)
}

private class LookShaderProgram(
    context: Context,
    useHdr: Boolean,
    private val look: () -> Look,
    private val output: LookOutput,
) : FullFrameShaderProgram(context, useHdr, R.raw.klipa_look_fragment_es2) {
    override fun setUniforms(program: GlProgram, size: Size) {
        val look = look()
        program.setFloatsUniform("uSize", floatArrayOf(size.width.toFloat(), size.height.toFloat()))
        program.setFloatUniform("uBrightness", look.brightness.toFloat())
        program.setFloatUniform("uContrast", look.contrast.toFloat())
        program.setFloatUniform("uSaturation", look.saturation.toFloat())
        program.setFloatUniform("uTint", (look.temperature * TEMPERATURE_LEVELS / 255).toFloat())
        val key = look.key
        val keyColor = key?.let { parseArgb(it.color) } ?: 0
        program.setIntUniform("uKeyEnabled", if (key != null) 1 else 0)
        program.setFloatsUniform(
            "uKeyColor",
            floatArrayOf((keyColor shr 16 and 0xFF) / 255f, (keyColor shr 8 and 0xFF) / 255f, (keyColor and 0xFF) / 255f),
        )
        program.setFloatUniform("uKeySimilarity", key?.similarity?.toFloat() ?: 0f)
        program.setFloatUniform("uKeyBlend", key?.blend?.toFloat() ?: 0f)
        val mask = look.mask
        program.setIntUniform("uMaskShape", if (mask == null) 0 else if (mask.shape == MaskShape.Ellipse) 2 else 1)
        program.setFloatsUniform(
            "uMaskRadius",
            floatArrayOf(
                maxOf(1e-6f, (size.width * (mask?.width ?: 1.0) / 2).toFloat()),
                maxOf(1e-6f, (size.height * (mask?.height ?: 1.0) / 2).toFloat()),
            ),
        )
        program.setFloatUniform("uMaskFeather", ((mask?.feather ?: 0.0) * minOf(size.width, size.height)).toFloat())
        program.setIntUniform("uMaskInvert", if (mask?.invert == true) 1 else 0)
        program.setIntUniform("uOutputMode", output.code)
    }
}

/** Turns premultiplied alpha back into straight alpha after a blur. */
object UnpremultiplyEffect : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        object : FullFrameShaderProgram(context, useHdr, R.raw.klipa_unpremultiply_fragment_es2) {
            override fun setUniforms(program: GlProgram, size: Size) = Unit
        }
}

/**
 * A Gaussian blur whose radius, [sigma] input pixels, is read for every frame. The output
 * is [scale] times the input size: a wide blur loses no detail at a lower resolution and
 * costs a fraction of the work.
 */
class LiveBlur(private val sigma: () -> Float, val scale: Float) : SeparableConvolution(scale, scale) {
    override fun getConvolution(presentationTimeUs: Long): ConvolutionFunction1D =
        GaussianFunction(sigma().coerceAtLeast(MIN_SIGMA), STANDARD_DEVIATIONS)

    companion object {
        /** A blur this narrow samples one pixel: it leaves the frame as it is. */
        private const val MIN_SIGMA = 0.01f

        /** FFmpeg's gblur, which ReaCut uses, reaches about three standard deviations. */
        private const val STANDARD_DEVIATIONS = 3f

        /** Blur radius, in output pixels, kept at full detail. */
        private const val SHARP_SIGMA = 4f

        /** The output scale that keeps a blur of up to [maxSigma] input pixels efficient. */
        fun scaleFor(maxSigma: Float): Float = (SHARP_SIGMA / maxSigma).coerceIn(1f / 16, 1f)
    }
}

/** Draws one full-frame quad with a fragment shader from this module's raw resources. */
private abstract class FullFrameShaderProgram(
    context: Context,
    useHdr: Boolean,
    @RawRes fragmentShader: Int,
) : BaseGlShaderProgram(useHdr, /* texturePoolCapacity= */ 1) {
    private val program: GlProgram = try {
        GlProgram(context, R.raw.klipa_vertex_es2, fragmentShader)
    } catch (e: IOException) {
        throw VideoFrameProcessingException(e)
    } catch (e: GlUtil.GlException) {
        throw VideoFrameProcessingException(e)
    }
    private var size = Size(1, 1)

    init {
        program.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
    }

    abstract fun setUniforms(program: GlProgram, size: Size)

    override fun configure(inputWidth: Int, inputHeight: Int): Size = Size(inputWidth, inputHeight).also { size = it }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            program.use()
            program.setSamplerTexIdUniform("uTexSampler", inputTexId, /* texUnitIndex= */ 0)
            setUniforms(program, size)
            program.bindAttributesAndUniforms()
            // Alpha is written, not blended: the output keeps exactly what the shader computes.
            GLES20.glDisable(GLES20.GL_BLEND)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, /* first= */ 0, /* count= */ 4)
            GlUtil.checkGlError()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    override fun release() {
        super.release()
        try {
            program.delete()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }
}
