package com.example.klipa

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.example.klipa.core.designsystem.KlipaTheme
import com.example.klipa.core.edit.EditorState
import com.example.klipa.core.model.MediaAsset
import com.example.klipa.core.media.FrameSource
import com.example.klipa.feature.timeline.EditorViewModel
import com.github.takahirom.roborazzi.captureRoboImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.media3.transformer.Composition
import com.example.klipa.feature.export.ExportEngine
import com.example.klipa.feature.export.ExportSettings
import java.io.File
import com.example.klipa.feature.export.ExportViewModel
import org.junit.Rule
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the editor on the JVM so its look can be reviewed without a device.
 * `./gradlew :app:recordRoborazziDebug` writes the images to `app/build/outputs/roborazzi`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class EditorScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun editingAProject() = capture(EditorState().copy(project = sampleProject(), playhead = 4.2, zoom = 38.0).let { s ->
        s.copy(selectedIds = listOf(s.project.clips.first { it.name == "Harbor at dusk.mp4" }.id))
    }, "editor_editing")

    @Test
    fun emptyProject() = capture(EditorState(), "editor_empty")

    @Test
    @Config(qualifiers = "w891dp-h411dp-land-xxhdpi")
    fun landscape() = capture(EditorState().copy(project = sampleProject(), playhead = 4.2, zoom = 38.0), "editor_landscape")

    @Test
    fun exportSettings() {
        show(EditorState().copy(project = sampleProject(), playhead = 4.2, zoom = 38.0))
        compose.onNodeWithText("Export").performClick()
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        compose.onNodeWithText("Resolution").assertExists()
        captureSheet("export_settings")
    }

    @Test
    fun exportInProgress() {
        // An engine that never finishes, 42% of the way through.
        val engine = object : ExportEngine {
            override fun start(composition: Composition, output: File, videoBitrate: Int, listener: ExportEngine.Listener) = Unit

            override fun progress(): Float = 0.42f

            override fun cancel() = Unit
        }
        val exporter = ExportViewModel(RuntimeEnvironment.getApplication(), engineFactory = { engine })
        val state = EditorState().copy(project = sampleProject(), playhead = 4.2, zoom = 38.0)
        show(state, exporter)
        exporter.start(state.project, ExportSettings(1080))
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        compose.onNodeWithText("42%").assertExists()
        captureSheet("export_progress")
    }

    private fun show(state: EditorState, exporter: ExportViewModel = ExportViewModel(RuntimeEnvironment.getApplication())) {
        val viewModel = EditorViewModel(initial = state)
        compose.setContent {
            KlipaTheme {
                // Robolectric has no GL or codecs, so the monitor uses still frames.
                EditorScreen(viewModel, exporter, frames = SyntheticFrames, importing = false, onPickMedia = {}, onPickAudio = {}, realTimePreview = false)
            }
        }
        // Thumbnails are requested while drawing, and Robolectric only draws on capture:
        // draw once, let them decode.
        compose.onRoot().captureRoboImage("build/tmp/roborazzi-warmup/${System.nanoTime()}.png")
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
    }

    /** Sheets open in a window of their own: the second compose root. */
    private fun captureSheet(name: String) {
        compose.onAllNodes(isRoot())[1].captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    private fun capture(state: EditorState, name: String) {
        show(state)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }
}

/** Stand-in frames: a dusk gradient with a dark waterline, so the monitor has content. */
private object SyntheticFrames : FrameSource {
    override suspend fun frame(asset: MediaAsset, sourceTime: Double, maxSize: Int, exact: Boolean): ImageBitmap {
        val width = 480
        val height = 270
        val image = ImageBitmap(width, height)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(image), Size(width.toFloat(), height.toFloat())) {
            drawRect(Brush.verticalGradient(listOf(Color(0xFF2B3A67), Color(0xFFE07A5F), Color(0xFFF2CC8F))), size = Size(width.toFloat(), height * 0.62f))
            drawRect(Color(0xFF1B2433), Offset(0f, height * 0.62f), Size(width.toFloat(), height * 0.38f))
            drawCircle(Color(0xFFFFE3A3), radius = 26f, center = Offset(width * 0.68f, height * 0.5f))
        }
        return image
    }
}
