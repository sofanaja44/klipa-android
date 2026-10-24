// Transformer's errors are marked unstable; Klipa pins the Media3 version.
@file:OptIn(UnstableApi::class)

package com.example.klipa.feature.export

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.ExportException
import com.example.klipa.core.model.Project
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where an export stands. */
sealed interface ExportState {
    data object Idle : ExportState

    /** Rendering; [progress] is in 0..1, or null until Media3 can tell. */
    data class Running(val progress: Float?) : ExportState

    /** Rendered; copying the file into the phone's videos. */
    data object Saving : ExportState

    data class Done(val video: ExportedVideo) : ExportState

    /** [detail] is the technical cause, for bug reports. */
    data class Failed(val failure: ExportFailure, val detail: String) : ExportState
}

/** A finished export. [inGallery] is false when it could only be kept in app storage. */
data class ExportedVideo(val uri: Uri, val fileName: String, val sizeBytes: Long, val durationMs: Long, val inGallery: Boolean)

/** Why an export failed, in terms the user can act on. */
enum class ExportFailure {
    /** The project has no video or photo to render. */
    NothingToExport,

    /** A media file was moved or deleted, or access to it was lost. */
    MissingMedia,

    /** A file could not be decoded on this device. */
    UnsupportedMedia,

    /** The device could not encode at this resolution. */
    Encoder,

    /** The output could not be written, usually because storage is full. */
    Storage,

    Unknown,
}

/** Maps Media3's error codes to what the user can do about them. */
fun classify(error: ExportException): ExportFailure = when (error.errorCode) {
    ExportException.ERROR_CODE_IO_FILE_NOT_FOUND,
    ExportException.ERROR_CODE_IO_NO_PERMISSION,
    -> ExportFailure.MissingMedia
    ExportException.ERROR_CODE_DECODER_INIT_FAILED,
    ExportException.ERROR_CODE_DECODING_FAILED,
    ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
    -> ExportFailure.UnsupportedMedia
    ExportException.ERROR_CODE_ENCODER_INIT_FAILED,
    ExportException.ERROR_CODE_ENCODING_FAILED,
    ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED,
    -> ExportFailure.Encoder
    ExportException.ERROR_CODE_MUXING_FAILED -> ExportFailure.Storage
    else -> ExportFailure.Unknown
}

/** Gives the export service the app's one [ExportController]; the Application implements it. */
interface ExportHost {
    val exporter: ExportController
}

/**
 * Runs exports, one at a time: it renders into app cache, then copies the file into
 * Movies/Klipa. It belongs to the application, not a screen, so an export outlives the
 * activity; [onStarted] is told when one begins, to keep the process in the foreground
 * (see [ExportService]). Used from the main thread.
 */
class ExportController(
    context: Context,
    private val engineFactory: () -> ExportEngine = { TransformerEngine(context) },
    private val library: VideoLibrary = MediaStoreLibrary(context),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Date = ::Date,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val onStarted: () -> Unit = { ExportService.start(context) },
) {
    private val _state = MutableStateFlow<ExportState>(ExportState.Idle)
    val state: StateFlow<ExportState> = _state.asStateFlow()

    private var engine: ExportEngine? = null
    private var progressJob: Job? = null
    private val folder = File(context.cacheDir, "exports")

    val running: Boolean get() = _state.value is ExportState.Running || _state.value is ExportState.Saving

    /** Starts exporting [project] with [settings]; ignored while an export runs. */
    fun start(project: Project, settings: ExportSettings) {
        if (running) return
        val built = try {
            buildExportComposition(project, settings)
        } catch (e: RuntimeException) {
            // Media3 checks its arguments with exceptions; report them rather than crash.
            fail(ExportFailure.Unknown, e.toString())
            return
        }
        if (built == null) {
            fail(ExportFailure.NothingToExport, "")
            return
        }
        // Only the latest export is kept in the cache.
        folder.deleteRecursively()
        folder.mkdirs()
        val name = "Klipa-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(clock())}.mp4"
        val output = File(folder, name)
        val engine = engineFactory()
        this.engine = engine
        _state.value = ExportState.Running(null)
        try {
            engine.start(built.composition, output, videoBitrate(project, settings), object : ExportEngine.Listener {
                override fun onCompleted(sizeBytes: Long, durationMs: Long) {
                    if (this@ExportController.engine !== engine) return
                    finish(output, name, sizeBytes, durationMs)
                }

                override fun onError(error: ExportException) {
                    if (this@ExportController.engine !== engine) return
                    Log.w(TAG, "Export failed", error)
                    output.delete()
                    fail(classify(error), "${error.errorCodeName}: ${error.message} (${error.cause})")
                }
            })
        } catch (e: RuntimeException) {
            // Media3 rejected the composition up front.
            fail(ExportFailure.Unknown, e.toString())
            return
        }
        onStarted()
        progressJob = scope.launch {
            while (isActive) {
                if (_state.value is ExportState.Running) _state.value = ExportState.Running(engine.progress())
                delay(PROGRESS_INTERVAL_MS)
            }
        }
    }

    /** Stops a running export and discards its file. A finished one being saved is kept. */
    fun cancel() {
        if (_state.value !is ExportState.Running) return
        val engine = engine ?: return
        this.engine = null
        progressJob?.cancel()
        engine.cancel()
        folder.deleteRecursively()
        _state.value = ExportState.Idle
    }

    /** Clears a finished or failed export so the next one starts from the settings. */
    fun dismiss() {
        if (!running) _state.value = ExportState.Idle
    }

    private fun finish(output: File, name: String, sizeBytes: Long, durationMs: Long) {
        progressJob?.cancel()
        engine = null
        _state.value = ExportState.Saving
        val size = if (sizeBytes > 0) sizeBytes else output.length()
        scope.launch {
            // Once copied into the gallery, the cache copy is not needed.
            val saved = withContext(io) { library.save(output, name)?.also { output.delete() } }
            _state.value = ExportState.Done(ExportedVideo(saved ?: library.shareable(output), name, size, durationMs, inGallery = saved != null))
        }
    }

    private fun fail(failure: ExportFailure, detail: String) {
        progressJob?.cancel()
        engine = null
        _state.value = ExportState.Failed(failure, detail)
    }

    private companion object {
        const val TAG = "KlipaExport"
        const val PROGRESS_INTERVAL_MS = 200L
    }
}
