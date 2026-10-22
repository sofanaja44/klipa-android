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
}
