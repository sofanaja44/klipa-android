package com.example.klipa.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

class ProjectJsonTest {
    @Test
    fun `round-trips a project`() {
        val project = fixture().withFirstClip {
            it.copy(
                linkId = "link",
                videoFadeWindow = FadeWindow(-1.0, 8.0, 4.0, 0.0),
                crop = CropRect(0.1, 0.2, 0.8, 0.7),
                keyframes = it.keyframes + Keyframe("k", 2.0, KeyframeProperty.Volume, 0.5, Easing.EaseInOut, EasingWindow(0.25, 0.75)),
            )
        }
        assertEquals(project, ProjectJson.decode(ProjectJson.encode(project)))
    }

    @Test
    fun `writes required fields and omits absent optional ones`() {
        val clip = ProjectJson.json.parseToJsonElement(ProjectJson.encode(fixture()))
            .jsonObject.getValue("clips").jsonArray[0] as JsonObject
        for (field in listOf("start", "sourceIn", "speed", "volume", "muted", "transform", "adjustments", "keyframes")) {
            assertTrue(field in clip, field)
        }
        for (field in listOf("linkId", "text", "videoFadeWindow", "crop", "chromaKey", "mask")) {
            assertFalse(field in clip, field)
        }
    }

    @Test
    fun `reads a ReaCut project file`() {
        // Integer-valued numbers, an unknown key and no optional fields, as ReaCut writes them.
        val text = """
            {
              "version": 1, "id": "p", "name": "未命名项目", "width": 1920, "height": 1080, "fps": 30,
              "background": "#101015", "createdAt": "2026-07-01T08:00:00.000Z", "updatedAt": "2026-07-01T08:00:00.000Z",
              "assets": [{ "id": "a", "name": "clip.mp4", "path": "/clip.mp4", "url": "file:///clip.mp4", "kind": "video",
                "duration": 12.5, "width": 1280, "height": 720, "fps": 29.97, "hasAudio": true, "waveform": [0, 0.5, 1] }],
              "tracks": [
                { "id": "t1", "name": "文字", "kind": "text", "muted": false, "hidden": false, "locked": false },
                { "id": "t2", "name": "视频 1", "kind": "video", "muted": false, "hidden": false, "locked": true }
              ],
              "clips": [
                { "id": "c1", "trackId": "t2", "assetId": "a", "name": "clip.mp4", "start": 0, "duration": 4, "sourceIn": 1,
                  "speed": 1, "volume": 1, "muted": false,
                  "transform": { "x": 0, "y": 0, "scale": 1, "rotation": 0, "opacity": 1, "flipX": false, "flipY": false },
                  "adjustments": { "brightness": 0, "contrast": 1, "saturation": 1, "temperature": 0, "blur": 0 },
                  "fadeIn": 0, "fadeOut": 0.5, "audioFadeIn": 0, "audioFadeOut": 0, "keyframes": [], "futureField": true },
                { "id": "c2", "trackId": "t1", "name": "Title", "start": 1, "duration": 2, "sourceIn": 0, "speed": 1,
                  "volume": 1, "muted": false,
                  "transform": { "x": 0, "y": 0, "scale": 1, "rotation": 0, "opacity": 1, "flipX": false, "flipY": false },
                  "adjustments": { "brightness": 0, "contrast": 1, "saturation": 1, "temperature": 0, "blur": 0 },
                  "text": { "text": "你好", "fontSize": 72, "fontFamily": "Noto Sans SC", "color": "#ffffff",
                    "background": "transparent", "align": "center", "bold": true, "stroke": "#000000", "strokeWidth": 2 },
                  "fadeIn": 0, "fadeOut": 0, "audioFadeIn": 0, "audioFadeOut": 0,
                  "keyframes": [{ "id": "k", "time": 0, "property": "opacity", "value": 0, "easing": "linear" }] }
              ]
            }
        """.trimIndent()
        val project = ProjectJson.decode(text)
        assertEquals(30, project.fps)
        assertEquals(29.97, project.assets[0].fps)
        assertEquals(listOf(0.0, 0.5, 1.0), project.assets[0].waveform)
        assertNull(project.assets[0].missing)
        assertTrue(project.tracks[1].locked)
        assertEquals(0.5, project.clips[0].fadeOut)
        assertNull(project.clips[0].linkId)
        assertEquals("你好", project.clips[1].text?.text)
        assertEquals(KeyframeProperty.Opacity, project.clips[1].keyframes[0].property)
    }

    @Test
    fun `rejects text that is not a project`() {
        for (text in listOf("", "[]", "null", "{", """{"version": 1}""")) {
            assertFailsWith<InvalidProjectException> { ProjectJson.decode(text) }
        }
        val valid = ProjectJson.encode(fixture())
        assertFailsWith<InvalidProjectException> { ProjectJson.decode(valid.replace("\"fps\":30", "\"fps\":\"30\"")) }
        assertFailsWith<InvalidProjectException> { ProjectJson.decode(valid.replace("\"fps\":30", "\"fps\":30f")) }
        assertFailsWith<InvalidProjectException> { ProjectJson.decode(valid.replace("\"fps\":30", "\"fps\":0x1E")) }
    }
}
