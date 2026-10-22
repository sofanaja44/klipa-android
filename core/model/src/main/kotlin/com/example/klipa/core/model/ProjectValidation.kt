package com.example.klipa.core.model

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Thrown when project data fails validation; [field] names the first offending value. */
class InvalidProjectException(val field: String) : IllegalArgumentException("Invalid project $field")

/**
 * Validates untrusted project JSON and decodes it. Ported from ReaCut's
 * `validateProject`: nothing is coerced and missing fields are not defaulted. Quoted
 * numbers, non-finite numbers, nulls in place of values and out-of-range values are all
 * rejected rather than repaired.
 */
fun validateProject(input: JsonElement): Project {
    ProjectValidator.check(input)
    return try {
        ProjectJson.json.decodeFromJsonElement(Project.serializer(), input)
    } catch (e: SerializationException) {
        throw InvalidProjectException("content")
    } catch (e: IllegalArgumentException) {
        throw InvalidProjectException("content")
    }
}

/** Checks an in-memory project against the same invariants as a loaded file. */
fun validateProject(project: Project): Project {
    ProjectValidator.check(ProjectJson.permissiveJson.encodeToJsonElement(Project.serializer(), project))
    return project
}

private object ProjectValidator {
    private val jsonNumber = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")
    private val hexColor = Regex("#[0-9a-f]{6}([0-9a-f]{2})?", RegexOption.IGNORE_CASE)
    private val namedColor = Regex("black|white|red|green|blue|transparent", RegexOption.IGNORE_CASE)
    private val keyColor = Regex("#[0-9a-f]{6}", RegexOption.IGNORE_CASE)
    private val keyframeProperties = listOf("x", "y", "scale", "rotation", "opacity", "volume")

    private fun fail(name: String): Nothing = throw InvalidProjectException(name)

    private fun obj(value: JsonElement?, name: String): JsonObject = value as? JsonObject ?: fail(name)

    private fun str(value: JsonElement?, name: String, max: Int = 4096, empty: Boolean = false): String {
        val primitive = value as? JsonPrimitive
        if (primitive == null || primitive is JsonNull || !primitive.isString) fail(name)
        val text = primitive.content
        if (text.length > max || (!empty && text.isEmpty()) || '\u0000' in text) fail(name)
        return text
    }

    private fun num(value: JsonElement?, name: String, low: Double = -1e9, high: Double = 1e9): Double {
        val primitive = value as? JsonPrimitive
        if (primitive == null || primitive is JsonNull || primitive.isString) fail(name)
        if (!jsonNumber.matches(primitive.content)) fail(name)
        val number = primitive.content.toDouble()
        if (!number.isFinite() || number < low || number > high) fail(name)
        return number
    }

    private fun int(value: JsonElement?, name: String, low: Double, high: Double): Double {
        val number = num(value, name, low, high)
        if (number % 1.0 != 0.0) fail(name)
        return number
    }

    private fun bool(value: JsonElement?, name: String) {
        val primitive = value as? JsonPrimitive
        if (primitive == null || primitive is JsonNull || primitive.isString ||
            (primitive.content != "true" && primitive.content != "false")
        ) fail(name)
    }

    private fun list(value: JsonElement?, name: String, max: Int): JsonArray {
        val array = value as? JsonArray ?: fail(name)
        if (array.size > max) fail(name)
        return array
    }

    private fun choice(value: JsonElement?, choices: List<String>, name: String): String {
        val primitive = value as? JsonPrimitive
        if (primitive == null || primitive is JsonNull || !primitive.isString || primitive.content !in choices) fail(name)
        return primitive.content
    }

    private fun color(value: JsonElement?, name: String) {
        val text = str(value, name, 32)
        if (!hexColor.matches(text) && !namedColor.matches(text)) fail(name)
    }

    // ReaCut accepts anything `Date.parse` understands; ISO-8601 covers what it writes.
    private fun date(value: JsonElement?, name: String) {
        val text = str(value, name, 64)
        val parsers = listOf<(String) -> Any>(
            Instant::parse, OffsetDateTime::parse, LocalDateTime::parse, LocalDate::parse,
        )
        val parsed = parsers.any { parse ->
            try {
                parse(text)
                true
            } catch (e: DateTimeParseException) {
                false
            }
        }
        if (!parsed) fail(name)
    }

    fun check(input: JsonElement) {
        val p = obj(input, "content")
        if (num(p["version"], "version") != 1.0) fail("version (only version 1 is supported)")
        str(p["id"], "id", 256)
        str(p["name"], "name", 4096, empty = true)
        int(p["width"], "width", 16.0, 7680.0)
        int(p["height"], "height", 16.0, 7680.0)
        if (num(p["fps"], "fps") !in ProjectFrameRates.map(Int::toDouble)) fail("fps")
        color(p["background"], "background")
        date(p["createdAt"], "createdAt")
        date(p["updatedAt"], "updatedAt")
        val rawAssets = list(p["assets"], "assets", 10000)
        val rawTracks = list(p["tracks"], "tracks", 1000)
        val rawClips = list(p["clips"], "clips", 10000)

        val ids = HashSet<String>()
        fun recordId(item: JsonObject, label: String): String {
            val id = str(item["id"], "$label id", 256)
            if (!ids.add(id)) fail("duplicate id")
            return id
        }

        val assets = HashMap<String, JsonObject>()
        val tracks = HashMap<String, JsonObject>()
        for (raw in rawAssets) {
            val a = obj(raw, "asset")
            assets[recordId(a, "asset")] = a
            str(a["name"], "asset name", 4096, empty = true)
            str(a["path"], "asset path", 32768)
            str(a["url"], "asset url", 32768)
            choice(a["kind"], listOf("video", "audio", "image"), "asset kind")
            num(a["duration"], "asset duration", 0.0, 86400.0 * 8)
            // Klipa stores asset dimensions as integers, so fractional sizes are rejected.
            int(a["width"], "asset width", 0.0, 100000.0)
            int(a["height"], "asset height", 0.0, 100000.0)
            num(a["fps"], "asset fps", 0.0, 1000.0)
            bool(a["hasAudio"], "asset hasAudio")
            a["missing"]?.let { bool(it, "asset missing") }
            a["thumbnail"]?.let { str(it, "asset thumbnail", 32768) }
            a["previewUrl"]?.let { str(it, "asset previewUrl", 32768) }
            a["previewError"]?.let { str(it, "asset previewError", 4096, empty = true) }
            a["waveform"]?.let { waveform ->
                for (v in list(waveform, "asset waveform", 100000)) num(v, "asset waveform value", 0.0, 1.0)
            }
        }
        for (raw in rawTracks) {
            val t = obj(raw, "track")
            tracks[recordId(t, "track")] = t
            str(t["name"], "track name", 4096, empty = true)
            choice(t["kind"], listOf("video", "audio", "image", "text"), "track kind")
            for (field in listOf("muted", "hidden", "locked")) bool(t[field], "track $field")
        }
        for (raw in rawClips) checkClip(obj(raw, "clip"), assets, tracks, ::recordId)
    }

    private fun checkClip(
        c: JsonObject,
        assets: Map<String, JsonObject>,
        tracks: Map<String, JsonObject>,
        recordId: (JsonObject, String) -> String,
    ) {
        recordId(c, "clip")
        str(c["name"], "clip name", 4096, empty = true)
        c["linkId"]?.let { str(it, "clip linkId", 256) }
        val track = tracks[str(c["trackId"], "clip trackId", 256)] ?: fail("clip track reference")
        val asset = c["assetId"]?.let { assets[str(it, "clip assetId", 256)] ?: fail("clip asset reference") }
        val start = num(c["start"], "clip start", 0.0, 86400.0)
        val duration = num(c["duration"], "clip duration", Double.MIN_VALUE, 86400.0)
        val sourceIn = num(c["sourceIn"], "clip sourceIn", 0.0, 86400.0 * 8)
        val speed = num(c["speed"], "clip speed", 0.1, 8.0)
        if (start + duration > 86400) fail("timeline duration (24 hours at most)")
        num(c["volume"], "clip volume", 0.0, 4.0)
        bool(c["muted"], "clip muted")
        for (field in listOf("fadeIn", "fadeOut", "audioFadeIn", "audioFadeOut")) num(c[field], field, 0.0, duration)
        for (field in listOf("videoFadeWindow", "audioFadeWindow")) {
            val w = obj(c[field] ?: continue, field)
            num(w["offset"], "$field offset", -86400.0 * 80, 86400.0 * 80)
            val full = num(w["duration"], "$field duration", Double.MIN_VALUE, 86400.0 * 80)
            num(w["fadeIn"], "$field fadeIn", 0.0, full)
            num(w["fadeOut"], "$field fadeOut", 0.0, full)
        }
        val transform = obj(c["transform"], "transform")
        for (field in listOf("x", "y", "rotation")) num(transform[field], "transform $field")
        num(transform["scale"], "transform scale", 0.000001, 10.0)
        num(transform["opacity"], "transform opacity", 0.0, 1.0)
        bool(transform["flipX"], "transform flipX")
        bool(transform["flipY"], "transform flipY")
        c["crop"]?.let {
            val crop = obj(it, "crop")
            val x = num(crop["x"], "crop x", 0.0, 1.0)
            val y = num(crop["y"], "crop y", 0.0, 1.0)
            val width = num(crop["width"], "crop width", 0.01, 1.0)
            val height = num(crop["height"], "crop height", 0.01, 1.0)
            if (x + width > 1 + 1e-9 || y + height > 1 + 1e-9) fail("crop outside the source")
        }
        c["chromaKey"]?.let {
            val chroma = obj(it, "chromaKey")
            bool(chroma["enabled"], "chromaKey enabled")
            if (!keyColor.matches(str(chroma["color"], "chromaKey color", 9))) fail("chromaKey color")
            num(chroma["similarity"], "chromaKey similarity", 0.01, 1.0)
            num(chroma["blend"], "chromaKey blend", 0.0, 1.0)
        }
        c["mask"]?.let {
            val mask = obj(it, "mask")
            choice(mask["shape"], listOf("rectangle", "ellipse"), "mask shape")
            num(mask["width"], "mask width", 0.01, 1.0)
            num(mask["height"], "mask height", 0.01, 1.0)
            num(mask["feather"], "mask feather", 0.0, 0.5)
            bool(mask["invert"], "mask invert")
        }
        val adjustments = obj(c["adjustments"], "adjustments")
        num(adjustments["brightness"], "brightness", -1.0, 1.0)
        num(adjustments["contrast"], "contrast", 0.0, 3.0)
        num(adjustments["saturation"], "saturation", 0.0, 3.0)
        num(adjustments["temperature"], "temperature", -1.0, 1.0)
        num(adjustments["blur"], "blur", 0.0, 100.0)
        val trackKind = (track["kind"] as JsonPrimitive).content
        val textValue = c["text"]
        if (textValue != null) {
            if (asset != null || trackKind != "text") fail("text clip reference")
            val text = obj(textValue, "text style")
            str(text["text"], "text", 20000, empty = true)
            num(text["fontSize"], "text fontSize", 1.0, 1000.0)
            str(text["fontFamily"], "text fontFamily", 256)
            color(text["color"], "text color")
            color(text["background"], "text background")
            color(text["stroke"], "text stroke")
            num(text["strokeWidth"], "text strokeWidth", 0.0, 100.0)
            choice(text["align"], listOf("left", "center", "right"), "text align")
            bool(text["bold"], "text bold")
        } else {
            val assetKind = (asset?.get("kind") as? JsonPrimitive)?.content
            if (asset == null || trackKind == "text" ||
                !(trackKind == assetKind || (trackKind == "video" && assetKind == "image"))
            ) fail("clip track and asset kind")
            val assetDuration = (asset["duration"] as JsonPrimitive).content.toDouble()
            if (assetKind != "image" && sourceIn + duration * speed > assetDuration + 0.08) {
                fail("clip exceeds the source duration")
            }
        }
        val keyIds = HashSet<String>()
        for (rawKey in list(c["keyframes"], "keyframes", 400)) {
            val k = obj(rawKey, "keyframe")
            if (!keyIds.add(str(k["id"], "keyframe id", 256))) fail("duplicate keyframe id")
            k["easingWindow"]?.let {
                val w = obj(it, "keyframe easingWindow")
                val windowStart = num(w["start"], "easingWindow start", 0.0, 1.0)
                val windowEnd = num(w["end"], "easingWindow end", 0.0, 1.0)
                val easing = (k["easing"] as? JsonPrimitive)?.takeIf { e -> e.isString }?.content
                if (windowStart >= windowEnd || easing != "easeInOut") fail("keyframe easingWindow")
            }
            num(k["time"], "keyframe time", 0.0, duration)
            val property = choice(k["property"], keyframeProperties, "keyframe property")
            choice(k["easing"], listOf("linear", "easeInOut"), "keyframe easing")
            val (low, high) = when (property) {
                "scale" -> 0.000001 to 10.0
                "opacity" -> 0.0 to 1.0
                "volume" -> 0.0 to 4.0
                else -> -1e9 to 1e9
            }
            num(k["value"], "keyframe value", low, high)
        }
    }
}
