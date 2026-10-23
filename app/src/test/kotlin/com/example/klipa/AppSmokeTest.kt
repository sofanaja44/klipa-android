package com.example.klipa

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.click
import androidx.lifecycle.ViewModelProvider
import com.example.klipa.feature.timeline.EditorViewModel
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import androidx.compose.ui.geometry.Offset
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Starts the real app and drives the editor the way a person would. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class AppSmokeTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun `add text, scrub, zoom, delete and undo`() {
        compose.onNodeWithText("Your timeline is empty").assertExists()
        compose.onNodeWithText("Text").performClick()
        // A new text clip is selected, so the clip tools replace the add tools.
        compose.onNodeWithText("Done").assertExists()
        compose.onNodeWithText("00:00:00:00").assertExists()

        val timeline = compose.onNodeWithContentDescription("Timeline")
        timeline.performTouchInput { swipeLeft(startX = centerX + 200f, endX = centerX - 200f) }
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithText("00:00:00:00").assertDoesNotExist()
        timeline.performTouchInput {
            pinch(Offset(centerX - 40f, centerY), Offset(centerX - 200f, centerY), Offset(centerX + 40f, centerY), Offset(centerX + 200f, centerY))
        }

        // The new clip's text can be edited in place.
        compose.onNodeWithText("Edit").performClick()
        compose.onNodeWithText("Enter text").performTextReplacement("Lisbon")
        compose.onNodeWithText("Lisbon").assertExists()
        compose.onNodeWithText("Bold").performClick()

        compose.onNodeWithText("Delete").performClick()
        compose.onNodeWithText("Your timeline is empty").assertExists()
        compose.onNodeWithContentDescription("Undo").assertIsEnabled().performClick()
        compose.onNodeWithText("Your timeline is empty").assertDoesNotExist()
    }

    @Test
    fun `arrange a title on the monitor`() {
        val editor = ViewModelProvider(compose.activity)[EditorViewModel::class.java]
        compose.onNodeWithText("Text").performClick()
        val monitor = compose.onNodeWithContentDescription(compose.activity.getString(R.string.monitor_description))
        fun title() = editor.state.value.project.clips.single { it.text != null }

        // The new title is selected and sits in the center: drag it right.
        monitor.performTouchInput { swipe(center, center + Offset(width * 0.25f, 0f), durationMillis = 300) }
        compose.waitForIdle()
        assertTrue(title().transform.x > 300, "moved to ${title().transform.x}")
        assertEquals(0.0, title().transform.y, "snapped to the center line")
        compose.onNodeWithContentDescription("Undo").performClick()
        assertEquals(0.0, title().transform.x)

        // Tapping empty frame clears the selection; tapping the title selects it again,
        // and tapping it once more opens its words for editing.
        monitor.performTouchInput { click(Offset(width * 0.05f, height * 0.1f)) }
        compose.onNodeWithText("Done").assertDoesNotExist()
        monitor.performTouchInput { click(center) }
        compose.onNodeWithText("Done").assertExists()
        monitor.performTouchInput { click(center) }
        compose.onNodeWithText("Enter text").assertExists()
    }
}
