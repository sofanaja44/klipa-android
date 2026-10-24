// Transformer's composition export is marked unstable; Klipa pins the Media3 version.
@file:OptIn(UnstableApi::class)

package com.example.klipa.feature.export

import android.app.Application
import android.app.NotificationManager
import android.net.Uri
import android.os.Looper
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import com.example.klipa.core.model.createClip
import com.example.klipa.core.model.createProject
import com.example.klipa.core.model.testAsset
import java.io.File
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** An app with an exporter whose engine the test finishes by hand. */
class TestExportApp : Application(), ExportHost {
    var listener: ExportEngine.Listener? = null
    var progress: Float? = null

    override val exporter: ExportController by lazy {
        ExportController(
            this,
            engineFactory = {
                object : ExportEngine {
                    override fun start(composition: Composition, output: File, videoBitrate: Int, listener: ExportEngine.Listener) {
                        output.writeText("mp4")
                        this@TestExportApp.listener = listener
                    }

                    override fun progress(): Float? = progress

                    override fun cancel() = Unit
                }
            },
            library = object : VideoLibrary {
                override fun save(file: File, name: String): Uri = Uri.parse("content://media/external/video/media/9")

                override fun shareable(file: File): Uri = Uri.fromFile(file)
            },
            io = Dispatchers.Unconfined,
            onStarted = {},
        )
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(application = TestExportApp::class)
class ExportServiceTest {
    private val app get() = RuntimeEnvironment.getApplication() as TestExportApp

    private fun idle(millis: Long = 0) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))

    @Test
    fun `shows progress while exporting, then that the video was saved`() {
        val project = createProject().let { it.copy(assets = listOf(testAsset), clips = listOf(createClip(testAsset, it.tracks[1].id, 0.0, it.fps).copy(duration = 3.0))) }
        app.exporter.start(project, ExportSettings(720))
        val controller = Robolectric.buildService(ExportService::class.java).create().startCommand(0, 1)
        val service = shadowOf(controller.get())
        val running = assertNotNull(service.lastForegroundNotification)
        assertEquals("Exporting video", running.extras.getString(NotificationCompat.EXTRA_TITLE))

        app.progress = 0.42f
        idle(300)
        val manager = shadowOf(app.getSystemService(NotificationManager::class.java))
        assertEquals("42%", manager.allNotifications.single().extras.getCharSequence(NotificationCompat.EXTRA_TEXT).toString())

        app.listener!!.onCompleted(sizeBytes = 2_000_000, durationMs = 3_000)
        idle()
        assertTrue(service.isStoppedBySelf)
        val done = manager.allNotifications.single()
        assertEquals("Video saved", done.extras.getString(NotificationCompat.EXTRA_TITLE))
        assertTrue(done.extras.getCharSequence(NotificationCompat.EXTRA_TEXT).toString().startsWith("Movies/Klipa"))
    }

    @Test
    fun `a cancelled export leaves no notification`() {
        val project = createProject().let { it.copy(assets = listOf(testAsset), clips = listOf(createClip(testAsset, it.tracks[1].id, 0.0, it.fps).copy(duration = 3.0))) }
        app.exporter.start(project, ExportSettings(720))
        val controller = Robolectric.buildService(ExportService::class.java).create().startCommand(0, 1)
        app.exporter.cancel()
        idle()
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
        assertTrue(shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications.isEmpty())
    }
}
