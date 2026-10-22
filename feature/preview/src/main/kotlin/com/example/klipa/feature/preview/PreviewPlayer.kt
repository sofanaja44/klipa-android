// CompositionPlayer is Media3's only multi-track preview and is marked experimental.
// Klipa pins the Media3 version and falls back to still frames if the player fails.
@file:OptIn(UnstableApi::class, ExperimentalApi::class)

package com.example.klipa.feature.preview

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.SurfaceView
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.MultipleInputVideoGraph
import androidx.media3.transformer.CompositionPlayer
import com.example.klipa.core.media.ProjectComposition
import com.example.klipa.core.media.VideoLayering
import com.example.klipa.core.media.buildComposition
import com.example.klipa.core.model.Project
import kotlin.math.abs
import kotlin.math.roundToLong

/** What the monitor is showing. */
enum class PreviewMode {
    /** Nothing to play: no media in the project, or not loaded yet. */
    Idle,

    /** Real-time playback of every video layer. */
    Layered,

    /** Real-time playback of the bottom video layer, with the sound of all layers. */
    BaseLayer,

    /** Real-time playback failed on this device; still frames are shown instead. */
    StillFrames,
}

/**
 * Real-time playback of a project, with sound, through Media3's CompositionPlayer, set up
 * the way Media3's composition demo does: a fresh player for every composition, a
 * single-input video graph unless several video layers need compositing.
 *
 * If playback fails it degrades instead of breaking: layered playback falls back to the
 * bottom layer only, and if that fails too, [mode] becomes [PreviewMode.StillFrames] and
 * the editor shows decoded still frames. Must be used on the main thread.
 */
class PreviewPlayer(
    private val context: Context,
    private val onFailure: (Exception) -> Unit,
    private val onSystemPause: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var player: CompositionPlayer? = null
    private var loaded: ProjectComposition? = null
    private var project: Project? = null
    private var surfaceView: SurfaceView? = null
    private var wantPlaying = false
    private var layeringFailed = false

    var mode by mutableStateOf(PreviewMode.Idle)
        private set

    /** True while a composition plays through the player (layered or base layer). */
    val active: Boolean get() = mode == PreviewMode.Layered || mode == PreviewMode.BaseLayer

    val failed: Boolean get() = mode == PreviewMode.StillFrames

    /** True once the player has a picture up, so the monitor can stop drawing stills. */
    var showingVideo by mutableStateOf(false)
        private set

    /** Bumped whenever something worth showing in diagnostics changes. */
    var revision by mutableIntStateOf(0)
        private set

    private var loads = 0
    private val errors = ArrayDeque<String>()

    /** Current playback position in seconds. */
    val position: Double get() = (player?.currentPosition ?: 0L) / 1000.0

    val ended: Boolean get() = player?.playbackState == Player.STATE_ENDED

    private val listener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            // Do not release the player from inside its own callback.
            main.post { handleFailure(error) }
        }

        override fun onRenderedFirstFrame() {
            showingVideo = true
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY && loaded?.plan?.videoSequences?.isNotEmpty() == true) showingVideo = true
            revision++
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            // Audio focus loss or headphones unplugged: tell the editor to stop too.
            if (!playWhenReady && wantPlaying && reason != Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) {
                wantPlaying = false
                onSystemPause()
            }
            revision++
        }
    }

    /** Loads [project], positioned at [seconds]. Projects without media unload the player. */
    fun setProject(project: Project, seconds: Double) {
        this.project = project
        if (mode == PreviewMode.StillFrames) return
        load(project, seconds)
    }

    private fun load(project: Project, seconds: Double) {
        try {
            val built = buildComposition(project, allowLayering = !layeringFailed)
            releasePlayer()
            if (built == null) {
                mode = PreviewMode.Idle
                return
            }
            // Set first, so a failure below knows which configuration failed.
            mode = if (built.layering == VideoLayering.Layered) PreviewMode.Layered else PreviewMode.BaseLayer
            // A fresh player per composition, as in Media3's composition demo.
            val player = createPlayer(built.layering == VideoLayering.Layered)
            this.player = player
            loaded = built
            loads++
            player.setComposition(built.composition, (seconds * 1000).roundToLong())
            player.prepare()
            if (wantPlaying) player.play()
        } catch (e: Exception) {
            handleFailure(e)
        } finally {
            revision++
        }
    }

    fun play() {
        wantPlaying = true
        player?.play()
    }

    fun pause() {
        wantPlaying = false
        player?.pause()
    }

    fun seekTo(seconds: Double) {
        val player = player ?: return
        val target = (seconds * 1000).roundToLong()
        // Ignore sub-frame differences: they would only interrupt the decoder.
        if (abs(target - player.currentPosition) >= 10) player.seekTo(target)
    }

    fun attach(view: SurfaceView) {
        surfaceView = view
        player?.setVideoSurfaceView(view)
    }

    fun detach(view: SurfaceView) {
        if (surfaceView !== view) return
        player?.clearVideoSurfaceView(view)
        surfaceView = null
        showingVideo = false
    }

    fun release() {
        main.removeCallbacksAndMessages(null)
        releasePlayer()
    }

    /** A plain-text report of the player's state, for bug reports. */
    fun diagnostics(): String = buildString {
        val player = player
        appendLine("mode: $mode")
        appendLine("loads: $loads, layering fell back: $layeringFailed")
        if (player != null) {
            appendLine("state: ${stateName(player.playbackState)}, playWhenReady: ${player.playWhenReady}, playing: ${player.isPlaying}")
            appendLine("position: ${player.currentPosition} ms of ${player.duration.takeIf { it != C.TIME_UNSET } ?: "?"} ms")
        }
        appendLine("showing video: $showingVideo")
        loaded?.let { built ->
            appendLine("layering: ${built.layering}")
            built.plan.sequences.forEachIndexed { index, sequence ->
                val kinds = listOfNotNull("video".takeIf { sequence.hasVideo }, "audio".takeIf { sequence.hasAudio }).joinToString("+")
                appendLine("sequence $index ($kinds): " + sequence.segments.joinToString(", ") { segment ->
                    val seconds = "%.2f".format(java.util.Locale.ROOT, segment.durationUs / 1e6)
                    if (segment is com.example.klipa.core.media.Segment.Item) "${segment.asset.kind.name.lowercase()} ${seconds}s" else "gap ${seconds}s"
                })
            }
        }
        if (errors.isNotEmpty()) {
            appendLine("errors:")
            errors.forEach { appendLine("  $it") }
        }
    }

    private fun createPlayer(layered: Boolean): CompositionPlayer =
        newCompositionPlayer(context, layered).also { player ->
            player.addListener(listener)
            surfaceView?.let(player::setVideoSurfaceView)
        }

    private fun releasePlayer() {
        player?.let {
            it.removeListener(listener)
            it.release()
        }
        player = null
        loaded = null
        showingVideo = false
    }

    private fun handleFailure(error: Exception) {
        val description = (error as? PlaybackException)?.let { "${it.errorCodeName}: ${it.message} (${it.cause})" } ?: error.toString()
        Log.w(TAG, "Preview playback failed in $mode", error)
        errors.addLast(description)
        while (errors.size > MAX_ERRORS) errors.removeFirst()
        val position = position
        if (mode == PreviewMode.Layered && !layeringFailed) {
            // Try again with only the bottom layer, the configuration Media3 supports best.
            layeringFailed = true
            project?.let { load(it, position) }
            return
        }
        releasePlayer()
        if (mode != PreviewMode.StillFrames) {
            mode = PreviewMode.StillFrames
            onFailure(error)
        }
        revision++
    }

    private fun stateName(state: Int) = when (state) {
        Player.STATE_IDLE -> "idle"
        Player.STATE_BUFFERING -> "buffering"
        Player.STATE_READY -> "ready"
        Player.STATE_ENDED -> "ended"
        else -> state.toString()
    }

    private companion object {
        const val TAG = "KlipaPreview"
        const val MAX_ERRORS = 5
    }
}

/**
 * A CompositionPlayer configured for Klipa's compositions: a compositor only when several
 * video layers need one ([layered]), audio focus handling, and per-stream progression.
 */
fun newCompositionPlayer(context: Context, layered: Boolean): CompositionPlayer {
    val builder = CompositionPlayer.Builder(context)
        .setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus= */ true)
        // Avoids freezes at clip boundaries (androidx/media#3453); Media3 is making it the default.
        .setPerStreamMediaProgressionEnabled(true)
    if (layered) builder.setVideoGraphFactory(MultipleInputVideoGraph.Factory())
    return builder.build()
}

/** A [PreviewPlayer] tied to the composition; released when it leaves. */
@Composable
fun rememberPreviewPlayer(onFailure: (Exception) -> Unit, onSystemPause: () -> Unit): PreviewPlayer {
    val context = LocalContext.current
    val failure by rememberUpdatedState(onFailure)
    val systemPause by rememberUpdatedState(onSystemPause)
    val player = remember { PreviewPlayer(context.applicationContext, { failure(it) }, { systemPause() }) }
    DisposableEffect(player) { onDispose { player.release() } }
    return player
}
