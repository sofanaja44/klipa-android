// Transformer's errors are marked unstable; Klipa pins the Media3 version.
@file:OptIn(UnstableApi::class)

package com.example.klipa.feature.export

import android.net.Uri
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import com.example.klipa.core.model.Project
import com.example.klipa.core.model.createClip
import com.example.klipa.core.model.createProject
import com.example.klipa.core.model.testAsset
import java.io.File
import java.io.FileNotFoundException
import java.time.Duration
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class ExportControllerTest {
    private class FakeEngine : ExportEngine {
        lateinit var listener: ExportEngine.Listener
        lateinit var output: File
        var bitrate = 0
        var progress: Float? = null
        var cancelled = false

        override fun start(composition: Composition, output: File, videoBitrate: Int, listener: ExportEngine.Listener) {
            this.output = output
            this.listener = listener
            bitrate = videoBitrate
            output.writeText("mp4")
        }

        override fun progress(): Float? = progress

        override fun cancel() {
            cancelled = true
        }
    }

    private class FakeLibrary(private val gallery: Uri?) : VideoLibrary {
        var saved: File? = null

        override fun save(file: File, name: String): Uri? {
            saved = file
            return gallery
        }

        override fun shareable(file: File): Uri = Uri.fromFile(file)
    }

    private val project: Project = createProject().let { base ->
        base.copy(assets = listOf(testAsset), clips = listOf(createClip(testAsset, base.tracks[1].id, 0.0, base.fps).copy(duration = 4.0)))
    }
    private val engines = mutableListOf<FakeEngine>()

    private var started = 0

    private fun exporter(library: VideoLibrary = FakeLibrary(Uri.parse("content://media/external/video/media/7"))) = ExportController(
        RuntimeEnvironment.getApplication(),
        engineFactory = { FakeEngine().also { engines += it } },
        library = library,
        io = Dispatchers.Unconfined,
        clock = { Date(0) },
        onStarted = { started++ },
    )

    private fun runMainLooper(millis: Long = 0) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))

    @Test
    fun `an export reports progress and lands in the gallery`() {
        val exporter = exporter()
        exporter.start(project, ExportSettings(720))
        assertEquals(ExportState.Running(null), exporter.state.value)
        val engine = engines.single()
        assertEquals(3_870_720, engine.bitrate)
        assertTrue(engine.output.name.startsWith("Klipa-") && engine.output.name.endsWith(".mp4"))

        engine.progress = 0.4f
        runMainLooper(250)
        assertEquals(ExportState.Running(0.4f), exporter.state.value)

        engine.listener.onCompleted(sizeBytes = 1234, durationMs = 4000)
        runMainLooper()
        val done = assertIs<ExportState.Done>(exporter.state.value)
        assertEquals(ExportedVideo(Uri.parse("content://media/external/video/media/7"), engine.output.name, 1234, 4000, inGallery = true), done.video)
        // Once in the gallery, the cache copy is gone.
        assertFalse(engine.output.exists())

        exporter.dismiss()
        assertEquals(ExportState.Idle, exporter.state.value)
    }

    @Test
    fun `without the gallery an export stays in the app, ready to share`() {
        val exporter = exporter(FakeLibrary(gallery = null))
        exporter.start(project, ExportSettings(1080))
        val engine = engines.single()
        engine.listener.onCompleted(sizeBytes = 99, durationMs = 4000)
        runMainLooper()
        val done = assertIs<ExportState.Done>(exporter.state.value).video
        assertFalse(done.inGallery)
        assertEquals(Uri.fromFile(engine.output), done.uri)
        assertTrue(engine.output.exists())
    }

    @Test
    fun `failures say what to do and keep the details`() {
        val exporter = exporter()
        exporter.start(project, ExportSettings(1080))
        engines.single().listener.onError(ExportException.createForAssetLoader(FileNotFoundException("gone"), ExportException.ERROR_CODE_IO_FILE_NOT_FOUND))
        val failed = assertIs<ExportState.Failed>(exporter.state.value)
        assertEquals(ExportFailure.MissingMedia, failed.failure)
        assertTrue("ERROR_CODE_IO_FILE_NOT_FOUND" in failed.detail)
        // Trying again starts from the settings.
        exporter.dismiss()
        assertEquals(ExportState.Idle, exporter.state.value)
    }

    @Test
    fun `cancelling stops the engine and ignores its late callbacks`() {
        val exporter = exporter()
        exporter.start(project, ExportSettings(1080))
        val engine = engines.single()
        exporter.cancel()
        assertTrue(engine.cancelled)
        assertEquals(ExportState.Idle, exporter.state.value)
        assertFalse(engine.output.exists())
        engine.listener.onCompleted(sizeBytes = 1, durationMs = 1)
        runMainLooper(1000)
        assertEquals(ExportState.Idle, exporter.state.value)
    }

    @Test
    fun `one export runs at a time`() {
        val exporter = exporter()
        exporter.start(project, ExportSettings(1080))
        exporter.start(project, ExportSettings(720))
        assertEquals(1, engines.size)
        // The foreground service is asked for once per export.
        assertEquals(1, started)
    }

    @Test
    fun `a project without a picture fails before rendering`() {
        val exporter = exporter()
        exporter.start(createProject(), ExportSettings(1080))
        assertEquals(ExportFailure.NothingToExport, assertIs<ExportState.Failed>(exporter.state.value).failure)
        assertTrue(engines.isEmpty())
    }

    @Test
    fun `media3 error codes map to what the user can do`() {
        fun failure(code: Int) = classify(ExportException.createForAssetLoader(RuntimeException(), code))
        assertEquals(ExportFailure.UnsupportedMedia, failure(ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED))
        assertEquals(ExportFailure.Encoder, failure(ExportException.ERROR_CODE_ENCODER_INIT_FAILED))
        assertEquals(ExportFailure.Storage, failure(ExportException.ERROR_CODE_MUXING_FAILED))
        assertEquals(ExportFailure.Unknown, failure(ExportException.ERROR_CODE_UNSPECIFIED))
    }
}
