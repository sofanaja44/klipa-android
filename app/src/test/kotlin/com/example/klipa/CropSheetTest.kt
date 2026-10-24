package com.example.klipa

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import com.example.klipa.core.designsystem.KlipaTheme
import com.example.klipa.core.model.Clip
import com.example.klipa.core.model.CropRect
import com.example.klipa.core.model.MediaAsset
import com.example.klipa.core.model.MediaKind
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class CropSheetTest {
    @get:Rule
    val compose = createComposeRule()

    private val asset = MediaAsset("a", "Shot.mp4", "", "", MediaKind.Video, 10.0, 1920, 1080, 30.0, hasAudio = false)
    private var clip = Clip(id = "c", trackId = "t", assetId = "a", name = "Shot.mp4", start = 0.0, duration = 4.0, crop = CropRect(0.25, 0.25, 0.5, 0.5))

    private fun show() {
        compose.setContent {
            KlipaTheme {
                var current by remember { mutableStateOf(clip) }
                CropSheet(current, asset, 0.0, SyntheticFrames, onChange = { edit ->
                    current = edit(current)
                    clip = current
                }, onDismiss = {})
            }
        }
    }

    @Test
    fun `dragging inside moves the crop and dragging a corner resizes it`() {
        show()
        val editor = compose.onNodeWithContentDescription("Crop")
        editor.performTouchInput { swipe(center, center + Offset(width * 0.2f, 0f)) }
        var crop = assertNotNull(clip.crop)
        assertEquals(0.45, crop.x, 0.01)
        assertEquals(0.25, crop.y, 1e-9)
        assertEquals(0.5, crop.width, 1e-9)

        editor.performTouchInput { swipe(Offset(width * (crop.x + crop.width).toFloat(), height * 0.75f), Offset(width * 0.98f, height * 0.95f)) }
        crop = assertNotNull(clip.crop)
        // The top left corner stays; the crop grows but stays inside the frame.
        assertEquals(0.25, crop.y, 1e-9)
        assertEquals(0.98 - crop.x, crop.width, 0.01)
        assertEquals(0.7, crop.height, 0.01)
    }

    @Test
    fun `a shape crops the center and reset removes the crop`() {
        show()
        compose.onNodeWithText("1:1").performClick()
        assertEquals(CropRect(0.21875, 0.0, 0.5625, 1.0), clip.crop)
        compose.onNodeWithText("Reset").performClick()
        assertNull(clip.crop)
    }
}
