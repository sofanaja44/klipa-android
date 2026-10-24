package com.example.klipa

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.example.klipa.core.designsystem.KlipaTheme
import com.example.klipa.core.projects.Cover
import com.example.klipa.core.projects.ProjectSummary
import com.github.takahirom.roborazzi.captureRoboImage
import java.time.Instant
import java.time.temporal.ChronoUnit
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Renders the home screen on the JVM, like [EditorScreenshotTest]. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class HomeScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val noActions = object : HomeActions {
        override fun create(format: ProjectFormat) = Unit
        override fun open(id: String) = Unit
        override fun rename(id: String, name: String) = Unit
        override fun duplicate(project: ProjectSummary) = Unit
        override fun delete(id: String) = Unit
    }

    private fun summary(id: String, name: String, width: Int, height: Int, duration: Double, hoursAgo: Long, cover: Boolean = true) = ProjectSummary(
        id = id,
        name = name,
        width = width,
        height = height,
        fps = 30,
        duration = duration,
        updatedAt = Instant.now().minus(hoursAgo, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MILLIS).toString(),
        clipCount = 4,
        cover = if (cover) Cover(asset("harbor", "Harbor at dusk.mp4", com.example.klipa.core.model.MediaKind.Video, 14.0), 2.0) else null,
    )

    private val projects = listOf(
        summary("a", "Weekend in Lisbon", 1080, 1920, 34.0, 1),
        summary("b", "Harbor timelapse", 1920, 1080, 15.0, 26),
        summary("c", "Cafe menu", 1080, 1080, 8.0, 72),
        summary("d", "Untitled draft", 1080, 1350, 0.0, 200, cover = false),
    )

    private fun show(list: List<ProjectSummary>?) {
        compose.setContent { KlipaTheme { HomeScreen(list, SyntheticFrames, noActions) } }
        compose.onRoot().captureRoboImage("build/tmp/roborazzi-warmup/home.png")
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
    }

    @Test
    fun projects() {
        show(projects)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/home_projects.png")
    }

    @Test
    fun firstLaunch() {
        show(emptyList())
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/home_empty.png")
    }

    @Test
    fun newProjectFormats() {
        show(projects)
        compose.onNodeWithText("New project").performClick()
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        compose.onAllNodes(isRoot())[1].captureRoboImage("build/outputs/roborazzi/home_new_project.png")
    }
}
