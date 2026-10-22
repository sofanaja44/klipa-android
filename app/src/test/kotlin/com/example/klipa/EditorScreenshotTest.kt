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
import org.junit.Rule
import org.junit.Test
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

    private fun capture(state: EditorState, name: String) {
        val viewModel = EditorViewModel(initial = state)
        compose.setContent {
            KlipaTheme {
                // Robolectric has no GL or codecs, so the monitor uses still frames.
                EditorScreen(viewModel, frames = SyntheticFrames, importing = false, onPickMedia = {}, onPickAudio = {}, realTimePreview = false)
            }
        }
        // Thumbnails are requested while drawing, and Robolectric only draws on capture:
        // draw once, let them decode, then take the real picture.
        compose.onRoot().captureRoboImage("build/tmp/roborazzi-warmup/$name.png")
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
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
