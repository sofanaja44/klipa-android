package com.example.klipa.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

// Ported from the "untrusted project validation" and "optional source-space visual
// settings validation" suites of ReaCut tests/model.test.ts. Malformed values that a
// Kotlin data class cannot hold are written straight into the JSON tree.

class ProjectValidationTest {
    /** ReaCut's `[undefined, null, "1", NaN, Infinity]`; a null entry removes the key. */
    private val missingOrCoerced: List<JsonElement?> = listOf(
        null,
        JsonNull,
        JsonPrimitive("1"),
        JsonPrimitive(Double.NaN),
        JsonPrimitive(Double.POSITIVE_INFINITY),
    )

    private fun assertInvalid(tree: JsonElement) {
        assertFailsWith<InvalidProjectException> { validateProject(tree) }
    }

    private fun assertInvalid(project: Project) {
        assertFailsWith<InvalidProjectException> { validateProject(project) }
    }

    @Test
    fun `accepts own projects and image duration zero`() {
        val project = fixture()
        assertEquals(project, validateProject(project))
        assertEquals(project, validateProject(project.toJsonTree()))
        validateProject(createProject())
        validateProject(project.copy(assets = listOf(testAsset.copy(kind = MediaKind.Image, duration = 0.0))))
    }

    @Test
    fun `rejects missing and coerced clip numbers`() {
        val fields = listOf("start", "duration", "sourceIn", "speed", "volume", "fadeIn", "fadeOut", "audioFadeIn", "audioFadeOut")
        for (field in fields) {
            for (invalid in missingOrCoerced) assertInvalid(fixture().toJsonTree().with("clips", 0, field, value = invalid))
        }
    }

    @Test
    fun `rejects missing transform and adjustment fields`() {
        for (field in listOf("x", "y", "scale", "rotation", "opacity", "flipX", "flipY")) {
            assertInvalid(fixture().toJsonTree().with("clips", 0, "transform", field, value = null))
        }
        for (field in listOf("brightness", "contrast", "saturation", "temperature", "blur")) {
            assertInvalid(fixture().toJsonTree().with("clips", 0, "adjustments", field, value = null))
        }
    }

    @Test
    fun `rejects malformed objects, bad references, duplicate ids and bad keyframe windows`() {
        val tree = fixture().toJsonTree()
        assertInvalid(tree.with("clips", 0, "transform", value = JsonNull))
        assertInvalid(tree.with("assets", 0, value = JsonNull))
        assertInvalid(tree.with("tracks", 1, "muted", value = null))

        val p = fixture()
        val tracks = p.tracks
        assertInvalid(p.copy(tracks = listOf(tracks[0].copy(id = tracks[1].id)) + tracks.drop(1)))
        assertInvalid(p.withFirstClip { it.copy(trackId = "missing") })
        assertInvalid(p.withFirstClip { c -> c.copy(keyframes = listOf(c.keyframes[0], c.keyframes[1].copy(easingWindow = EasingWindow(0.8, 0.2)))) })
        assertInvalid(p.withFirstClip { c -> c.copy(keyframes = listOf(c.keyframes[0].copy(easingWindow = EasingWindow.Full), c.keyframes[1])) })
        assertInvalid(p.withFirstClip { c -> c.copy(keyframes = listOf(c.keyframes[0], c.keyframes[1].copy(time = 9.0))) })
        assertInvalid(p.withFirstClip { it.copy(speed = 8.0) })
        assertInvalid(p.copy(background = "url(https://invalid)"))
    }

    @Test
    fun `rejects ids shared between assets, tracks and clips`() {
        val p = fixture()
        assertInvalid(p.withFirstClip { it.copy(id = p.tracks[0].id) })
        assertInvalid(p.copy(assets = listOf(testAsset.copy(id = p.tracks[2].id))).withFirstClip { it.copy(assetId = p.tracks[2].id) })
    }

    @Test
    fun `rejects broken link ids and clips on the wrong track kind`() {
        val p = fixture()
        assertInvalid(p.toJsonTree().with("clips", 0, "linkId", value = JsonPrimitive(42)))
        assertInvalid(p.withFirstClip { it.copy(linkId = "") })
        assertInvalid(p.withFirstClip { it.copy(trackId = p.tracks[2].id) })
        assertInvalid(p.withFirstClip { it.copy(trackId = p.tracks[0].id) })
        assertInvalid(p.withFirstClip { it.copy(assetId = "nowhere") })
    }

    @Test
    fun `validates every required text field rather than trusting the shape`() {
        val base = createProject()
        val p = base.copy(clips = listOf(createClip(null, base.tracks[0].id, 0.0, base.fps)))
        validateProject(p)
        for (field in listOf("text", "fontSize", "fontFamily", "color", "background", "align", "bold", "stroke", "strokeWidth")) {
            assertInvalid(p.toJsonTree().with("clips", 0, "text", field, value = null))
        }
        assertInvalid(p.withFirstClip { it.copy(trackId = base.tracks[1].id) })
    }

    @Test
    fun `strictly validates every optional fade-window field`() {
        val windowed = fixture().withFirstClip { it.copy(videoFadeWindow = FadeWindow(0.0, 8.0, 1.0, 1.0)) }
        validateProject(windowed)
        for (field in listOf("offset", "duration", "fadeIn", "fadeOut")) {
            for (invalid in listOf(null, JsonPrimitive(Double.NaN), JsonPrimitive(Double.POSITIVE_INFINITY), JsonPrimitive("1"))) {
                assertInvalid(windowed.toJsonTree().with("clips", 0, "videoFadeWindow", field, value = invalid))
            }
        }
        assertInvalid(fixture().withFirstClip { it.copy(audioFadeWindow = FadeWindow(0.0, 8.0, 9.0, 0.0)) })
    }

    @Test
    fun `accepts crop, chroma key and mask and rejects malformed crop coordinates`() {
        val p = fixture().withFirstClip { it.copy(crop = CropRect(0.0, 0.0, 1.0, 1.0)) }
        validateProject(p)
        for (field in listOf("x", "y", "width", "height")) {
            for (invalid in listOf(null, JsonPrimitive("0.5"), JsonPrimitive(Double.NaN), JsonPrimitive(Double.POSITIVE_INFINITY), JsonNull)) {
                assertInvalid(p.toJsonTree().with("clips", 0, "crop", field, value = invalid))
            }
        }
        for (crop in listOf(
            CropRect(-0.1, 0.0, 1.0, 1.0),
            CropRect(0.1, 0.0, 1.0, 1.0),
            CropRect(0.0, 0.5, 1.0, 0.6),
            CropRect(0.0, 0.0, 0.0, 1.0),
            CropRect(0.0, 0.0, 0.009, 1.0),
        )) {
            assertInvalid(fixture().withFirstClip { it.copy(crop = crop) })
        }
    }

    @Test
    fun `rejects chroma and mask malformed fields while accepting exact interval boundaries`() {
        val base = fixture().withFirstClip {
            it.copy(
                chromaKey = ChromaKey(enabled = false, color = "#aAbB00", similarity = 0.01, blend = 0.0),
                mask = ShapeMask(shape = MaskShape.Rectangle, width = 0.01, height = 1.0, feather = 0.5, invert = true),
            )
        }
        validateProject(base)
        for (field in listOf("enabled", "color", "similarity", "blend")) {
            assertInvalid(base.toJsonTree().with("clips", 0, "chromaKey", field, value = null))
        }
        for (field in listOf("shape", "width", "height", "feather", "invert")) {
            assertInvalid(base.toJsonTree().with("clips", 0, "mask", field, value = null))
        }
        for (invalid in listOf("red", "#fff", "#ff000080", "url(x)")) {
            assertInvalid(base.withFirstClip { it.copy(chromaKey = it.chromaKey!!.copy(color = invalid)) })
        }
        for (invalid in listOf(-0.1, 0.501, Double.NaN)) {
            assertInvalid(base.withFirstClip { it.copy(mask = it.mask!!.copy(feather = invalid)) })
        }
        assertInvalid(base.toJsonTree().with("clips", 0, "mask", "feather", value = JsonPrimitive("0")))
    }

    @Test
    fun `rejects projects outside the supported canvas, frame rate and duration`() {
        val p = fixture()
        for (fps in listOf(0, 29, 120)) assertInvalid(p.copy(fps = fps))
        for (size in listOf(15, 7681)) {
            assertInvalid(p.copy(width = size))
            assertInvalid(p.copy(height = size))
        }
        assertInvalid(p.toJsonTree().with("width", value = JsonPrimitive(1920.5)))
        assertInvalid(p.toJsonTree().with("version", value = JsonPrimitive(2)))
        assertInvalid(p.copy(createdAt = "yesterday"))
        assertInvalid(p.withFirstClip { it.copy(start = 86395.0) })
        assertInvalid(p.withFirstClip { it.copy(name = "a\u0000b") })
    }
}
