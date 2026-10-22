package com.example.klipa.core.model

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Project file load/save. Files have the same JSON shape as ReaCut projects. */
object ProjectJson {
    /** Writes every required field and omits absent optional ones, like ReaCut's `JSON.stringify`. */
    val json: Json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    // Lets an in-memory project holding NaN or Infinity be encoded so validation can reject it.
    internal val permissiveJson: Json = Json(json) { allowSpecialFloatingPointValues = true }

    fun encode(project: Project): String = json.encodeToString(Project.serializer(), project)

    /** Parses and validates a project file; throws [InvalidProjectException] on any defect. */
    fun decode(text: String): Project {
        val tree = try {
            json.parseToJsonElement(text)
        } catch (e: SerializationException) {
            throw InvalidProjectException("content")
        }
        return validateProject(tree)
    }
}
