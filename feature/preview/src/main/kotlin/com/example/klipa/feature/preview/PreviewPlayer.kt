// CompositionPlayer is Media3's only multi-track preview and is marked experimental.
// Klipa pins the Media3 version and falls back to still frames if the player fails.
@file:OptIn(UnstableApi::class, ExperimentalApi::class)

package com.example.klipa.feature.preview

import androidx.annotation.OptIn
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import android.content.Context
import android.util.Log
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.effect.MultipleInputVideoGraph
import androidx.media3.transformer.CompositionPlayer
import com.example.klipa.core.media.buildComposition
import com.example.klipa.core.model.Project
import kotlin.math.roundToLong

/**
 * Real-time playback of a project, with sound, through Media3's CompositionPlayer.
 *
 * The player is created lazily and only for projects that have media. If anything goes
 * wrong (an unsupported codec, a GL failure on this device), it stops for good and
 * [failed] turns true; the editor then falls back to frame-by-frame preview.
 * Must be used on the main thread.
 */
class PreviewPlayer(private val context: Context, private val onFailure: (Exception) -> Unit) {
    private var player: CompositionPlayer? = null
    private var surfaceView: SurfaceView? = null

    /** True once playback has failed; the player is then released and stays unused. */
    var failed by mutableStateOf(false)
        private set

    /** True while a composition is loaded and playable. */
    var active by mutableStateOf(false)
        private set

    /** True once the current composition has drawn a frame, so the monitor can show it. */
    var showingVideo by mutableStateOf(false)
        private set

    /** Current playback position in seconds. */
    val position: Double get() = (player?.currentPosition ?: 0L) / 1000.0

    val ended: Boolean get() = player?.playbackState == Player.STATE_ENDED

    private val listener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) = fail(error)

        override fun onRenderedFirstFrame() {
            showingVideo = true
        }
    }

    /** Loads [project], positioned at [seconds]. Projects without media unload the player. */
    fun setProject(project: Project, seconds: Double) {
        if (failed) return
        try {
            val built = buildComposition(project)
            if (built == null) {
                releasePlayer()
                return
            }
            val player = player ?: createPlayer().also { player = it }
            player.setComposition(built.composition, (seconds * 1000).roundToLong())
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            active = true
            if (!built.plan.videoSequences.any()) showingVideo = false
        } catch (e: Exception) {
            fail(e)
        }
    }

    fun play() {
        player?.play()
    }

    fun pause() {
        player?.pause()
    }

    fun seekTo(seconds: Double) {
        val player = player ?: return
        val target = (seconds * 1000).roundToLong()
        if (target != player.currentPosition) player.seekTo(target)
    }

    /** Scrubbing mode favors fast, frequent seeks over smooth playback. */
    fun setScrubbing(enabled: Boolean) {
        val player = player ?: return
        if (player.isScrubbingModeEnabled != enabled) player.setScrubbingModeEnabled(enabled)
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

    fun release() = releasePlayer()

    private fun createPlayer(): CompositionPlayer =
        CompositionPlayer.Builder(context)
            // Every track is its own input to the compositor, even when there is only one.
            .setVideoGraphFactory(MultipleInputVideoGraph.Factory())
            .build()
            .also { player ->
                player.addListener(listener)
                surfaceView?.let(player::setVideoSurfaceView)
            }

    private fun releasePlayer() {
        player?.let {
            it.removeListener(listener)
            it.release()
        }
        player = null
        active = false
        showingVideo = false
    }

    private fun fail(error: Exception) {
        Log.w(TAG, "Real-time preview failed; falling back to frames", error)
        releasePlayer()
        if (!failed) {
            failed = true
            onFailure(error)
        }
    }

    private companion object {
        const val TAG = "KlipaPreview"
    }
}

/** A [PreviewPlayer] tied to the composition; released when it leaves. */
@Composable
fun rememberPreviewPlayer(onFailure: (Exception) -> Unit): PreviewPlayer {
    val context = LocalContext.current
    val failure by rememberUpdatedState(onFailure)
    val player = remember { PreviewPlayer(context.applicationContext) { failure(it) } }
    DisposableEffect(player) { onDispose { player.release() } }
    return player
}
