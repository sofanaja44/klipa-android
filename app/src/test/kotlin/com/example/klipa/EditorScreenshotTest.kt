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
import com.example.klipa.core.edit.detachLinkedAudio
import com.example.klipa.core.model.Easing
import com.example.klipa.core.model.Keyframe
import com.example.klipa.core.model.KeyframeProperty
import com.example.klipa.core.model.MediaAsset
import com.example.klipa.core.model.MediaKind
import com.example.klipa.core.model.Project
import com.example.klipa.core.model.TextStyle
import com.example.klipa.core.model.createClip
import com.example.klipa.core.model.createProject
import com.example.klipa.feature.preview.FrameSource
import com.example.klipa.feature.timeline.EditorViewModel
import com.github.takahirom.roborazzi.captureRoboImage
import kotlin.math.sin
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
                EditorScreen(viewModel, frames = SyntheticFrames, importing = false, onPickMedia = {}, onPickAudio = {})
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }
}

/** A short edit: two shots with extracted sound, a title, a music bed. */
private fun sampleProject(): Project {
    var project = createProject("Weekend in Lisbon")
    val video = project.tracks[1].id
    val harbor = asset("harbor", "Harbor at dusk.mp4", MediaKind.Video, 14.0)
    val tram = asset("tram", "Tram 28.mp4", MediaKind.Video, 9.0)
    val music = asset("music", "Saudade (instrumental).m4a", MediaKind.Audio, 60.0)
        .copy(waveform = List(400) { i -> (0.35 + 0.3 * sin(i / 3.0) * sin(i / 17.0)).coerceIn(0.05, 1.0) })
    project = project.copy(assets = listOf(harbor, tram, music))
    val first = createClip(harbor, video, 0.0, 30).copy(sourceIn = 2.0, duration = 9.0, fadeIn = 1.0)
    val second = createClip(tram, video, 9.0, 30).copy(duration = 6.0, speed = 1.5)
    project = project.copy(clips = listOf(first, second))
    project = detachLinkedAudio(project, first.id).project
    val bed = createClip(music, project.tracks[2].id, 0.0, 30).copy(duration = 15.0, audioFadeOut = 3.0, muted = false)
    val musicTrack = com.example.klipa.core.model.createTrack(com.example.klipa.core.model.TrackKind.Audio, "Music")
    val title = createClip(null, project.tracks[0].id, 1.0, 30).copy(
        name = "Title",
        duration = 6.0,
        text = TextStyle(text = "Lisbon", fontSize = 160.0, strokeWidth = 0.0),
        transform = com.example.klipa.core.model.Transform(y = 280.0),
        keyframes = listOf(
            Keyframe("k1", 0.0, KeyframeProperty.Opacity, 0.0, Easing.Linear),
            Keyframe("k2", 1.0, KeyframeProperty.Opacity, 1.0, Easing.EaseInOut),
        ),
    )
    return project.copy(
        tracks = project.tracks + musicTrack,
        clips = project.clips + bed.copy(trackId = musicTrack.id) + title,
    )
}

private fun asset(id: String, name: String, kind: MediaKind, duration: Double) = MediaAsset(
    id = id,
    name = name,
    path = "content://media/$id",
    url = "content://media/$id",
    kind = kind,
    duration = duration,
    width = if (kind == MediaKind.Video) 1920 else 0,
    height = if (kind == MediaKind.Video) 1080 else 0,
    fps = if (kind == MediaKind.Video) 30.0 else 0.0,
    hasAudio = true,
)

/** Stand-in frames: a dusk gradient with a dark waterline, so the monitor has content. */
private object SyntheticFrames : FrameSource {
    override suspend fun frame(asset: MediaAsset, sourceTime: Double, maxSize: Int): ImageBitmap {
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
