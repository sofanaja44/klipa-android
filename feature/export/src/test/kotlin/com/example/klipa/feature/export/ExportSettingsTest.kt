package com.example.klipa.feature.export

import com.example.klipa.core.model.Project
import com.example.klipa.core.model.createClip
import com.example.klipa.core.model.createProject
import com.example.klipa.core.model.testAsset
import kotlin.test.Test
import kotlin.test.assertEquals

class ExportSettingsTest {
    private val hd = createProject()
    private val portrait = hd.copy(width = 1080, height = 1920)

    private fun Project.lasting(seconds: Double) =
        copy(assets = listOf(testAsset), clips = listOf(createClip(testAsset, tracks[1].id, 0.0, fps).copy(duration = seconds)))

    @Test
    fun `resolutions stop at the project's own and default to 1080p`() {
        assertEquals(listOf(480, 720, 1080), exportResolutions(hd))
        assertEquals(1080, defaultExportSettings(hd).shortSide)
        assertEquals(listOf(480, 720, 1080), exportResolutions(portrait))

        val uhd = hd.copy(width = 3840, height = 2160)
        assertEquals(listOf(480, 720, 1080, 1440, 2160), exportResolutions(uhd))
        assertEquals(1080, defaultExportSettings(uhd).shortSide)

        val small = hd.copy(width = 640, height = 360)
        assertEquals(listOf(360), exportResolutions(small))
        assertEquals(360, defaultExportSettings(small).shortSide)
    }

    @Test
    fun `output frames keep the project's shape in even sizes`() {
        assertEquals(1280 to 720, outputSize(hd, ExportSettings(720)))
        assertEquals(1920 to 1080, outputSize(hd, ExportSettings(1080)))
        assertEquals(720 to 1280, outputSize(portrait, ExportSettings(720)))
        assertEquals(864 to 1080, outputSize(hd.copy(width = 1080, height = 1350), ExportSettings(864)))
        // Encoders need even sizes, so odd projects round to the nearest even pixel.
        assertEquals(1002 to 564, outputSize(hd.copy(width = 1001, height = 563), ExportSettings(563)))
    }

    @Test
    fun `the size estimate follows the bitrate Media3 would pick`() {
        val tenSeconds = hd.lasting(10.0)
        // 1920 × 1080 × 30 fps × 0.14 bits per pixel = 8.7 Mbit/s, plus 128 kbit/s of audio.
        assertEquals(8_709_120, videoBitrate(tenSeconds, ExportSettings(1080)))
        assertEquals((8_709_120L + 128_000) * 10 / 8, estimatedSizeBytes(tenSeconds, ExportSettings(1080)))
        assertEquals(3_870_720, videoBitrate(tenSeconds, ExportSettings(720)))
    }

    @Test
    fun `clips with missing media are counted`() {
        val p = hd.lasting(4.0)
        assertEquals(0, missingMediaClips(p))
        assertEquals(1, missingMediaClips(p.copy(assets = p.assets.map { it.copy(missing = true) })))
    }

    @Test
    fun `durations read as minutes and seconds`() {
        assertEquals("0:00", formatClock(0.2))
        assertEquals("1:05", formatClock(65.4))
        assertEquals("1:00:00", formatClock(3600.0))
    }
}
