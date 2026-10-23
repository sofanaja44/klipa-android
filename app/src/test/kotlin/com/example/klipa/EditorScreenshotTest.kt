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
import androidx.compose.ui.test.performScrollTo
import com.example.klipa.core.model.Adjustments
import com.example.klipa.core.model.ChromaKey
import com.example.klipa.core.model.Clip
import com.example.klipa.core.model.MaskShape
import com.example.klipa.core.model.ShapeMask
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
    fun arrangingATitle() = capture(EditorState().copy(project = sampleProject(), playhead = 4.2, zoom = 38.0).let { s ->
        // The title, turned a little and moved off center, is outlined on the monitor.
        val title = s.project.clips.first { it.name == "Title" }
        val project = s.project.copy(clips = s.project.clips.map { if (it.id == title.id) it.copy(transform = it.transform.copy(x = -420.0, rotation = -6.0, scale = 0.8)) else it })
        s.copy(project = project, selectedIds = listOf(title.id))
    }, "editor_arrange")

    @Test
    fun emptyProject() = capture(EditorState(), "editor_empty")

    @Test
    @Config(qualifiers = "w891dp-h411dp-land-xxhdpi")
    fun landscape() = capture(EditorState().copy(project = sampleProject(), playhead = 4.2, zoom = 38.0), "editor_landscape")

    @Test
    fun adjustSheet() = captureClipSheet("Adjust", "adjust_sheet") { it.copy(adjustments = Adjustments(brightness = -0.04, contrast = 1.22, saturation = 0.78, temperature = -0.08)) }

    @Test
    fun chromaKeySheet() = captureClipSheet("Chroma key", "chroma_key_sheet") { it.copy(chromaKey = ChromaKey(true, "#00ff00", 0.18, 0.08)) }

    @Test
    fun maskSheet() = captureClipSheet("Mask", "mask_sheet") { it.copy(mask = ShapeMask(MaskShape.Ellipse, 0.7, 0.85, 0.12, invert = false)) }

    /** Selects the first shot, styled by [style], and opens its [tool] sheet. */
    private fun captureClipSheet(tool: String, name: String, style: (Clip) -> Clip) {
        val project = sampleProject().let { p ->
            val shot = p.clips.first { it.name == "Harbor at dusk.mp4" }
            p.copy(clips = p.clips.map { if (it.id == shot.id) style(it) else it })
        }
        val shot = project.clips.first { it.name == "Harbor at dusk.mp4" }
        show(EditorState().copy(project = project, playhead = 4.2, zoom = 38.0, selectedIds = listOf(shot.id)))
        compose.onNodeWithText(tool).performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        captureSheet(name)
    }

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
