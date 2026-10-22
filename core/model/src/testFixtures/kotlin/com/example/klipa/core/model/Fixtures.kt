package com.example.klipa.core.model

import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

val testAsset = MediaAsset(
    id = "asset",
    name = "Video",
    path = "/tmp/test.mp4",
    url = "file:///tmp/test.mp4",
    kind = MediaKind.Video,
    duration = 20.0,
    width = 1920,
    height = 1080,
    fps = 30.0,
    hasAudio = true,
)

/** ReaCut's model.test.ts fixture: one 2× speed clip with fades and an eased x animation. */
fun fixture(): Project {
    val project = createProject("Test")
    val clip = createClip(testAsset, project.tracks[1].id, 2.0, project.fps).copy(
        duration = 8.0,
        sourceIn = 2.0,
        speed = 2.0,
        fadeIn = 2.0,
        fadeOut = 3.0,
        audioFadeIn = 1.0,
        audioFadeOut = 2.0,
        keyframes = listOf(
            Keyframe(id = "key0", property = KeyframeProperty.X, time = 0.0, value = 0.0, easing = Easing.Linear),
            Keyframe(id = "key1", property = KeyframeProperty.X, time = 8.0, value = 800.0, easing = Easing.EaseInOut),
        ),
    )
    return project.copy(assets = listOf(testAsset), clips = listOf(clip))
}

fun Project.withFirstClip(change: (Clip) -> Clip): Project = copy(clips = listOf(change(clips[0])) + clips.drop(1))

fun Project.toJsonTree(): JsonElement = ProjectJson.json.encodeToJsonElement(Project.serializer(), this)

/**
 * Replaces the value at [path] (object keys and array indices); a null [value] removes
 * an object key, the way `delete` does in the ReaCut tests.
 */
fun JsonElement.with(vararg path: Any, value: JsonElement?): JsonElement = edit(path.toList(), value)

private fun JsonElement.edit(path: List<Any>, value: JsonElement?): JsonElement {
    val head = path.first()
    val rest = path.drop(1)
    return when (this) {
        is JsonObject -> {
            val key = head as String
            val next = if (rest.isEmpty()) value else this.getValue(key).edit(rest, value)
            JsonObject(toMutableMap().apply { if (next == null) remove(key) else put(key, next) })
        }
        is JsonArray -> {
            val index = head as Int
            val next = if (rest.isEmpty()) requireNotNull(value) else this[index].edit(rest, value)
            JsonArray(toMutableList().apply { set(index, next) })
        }
        else -> error("Cannot descend into $this")
    }
}

/** Vitest's `toBeCloseTo`: equal to within half a unit in the [digits]th decimal place. */
fun assertClose(expected: Double, actual: Double, digits: Int = 2) {
    assertTrue(abs(expected - actual) < 10.0.pow(-digits) / 2, "expected $expected but was $actual")
}

/** `from`, `from + step`, … up to and including [to], accumulated like a JavaScript for-loop. */
fun steps(from: Double, to: Double, step: Double): List<Double> =
    generateSequence(from) { it + step }.takeWhile { it <= to }.toList()
