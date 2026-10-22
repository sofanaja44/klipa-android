package com.example.klipa

import com.example.klipa.core.model.InvalidProjectException
import com.example.klipa.core.model.Project
import com.example.klipa.core.model.ProjectJson
import com.example.klipa.feature.timeline.ProjectRepository
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Keeps the working project as a ReaCut-compatible JSON file. Writes go to a temporary
 * file first so a crash mid-save never leaves a half-written project. A file that fails
 * validation is set aside rather than overwritten.
 */
class ProjectFileRepository(private val directory: File) : ProjectRepository {
    private val file = File(directory, "autosave.klipa.json")

    override suspend fun load(): Project? = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext null
        try {
            ProjectJson.decode(file.readText())
        } catch (e: InvalidProjectException) {
            file.renameTo(File(directory, "autosave.damaged-${System.currentTimeMillis()}.klipa.json"))
            null
        } catch (e: IOException) {
            null
        }
    }

    override suspend fun save(project: Project) = withContext(Dispatchers.IO) {
        directory.mkdirs()
        val temp = File(directory, "${file.name}.tmp")
        temp.writeText(ProjectJson.encode(project))
        if (!temp.renameTo(file)) {
            file.delete()
            temp.renameTo(file)
        }
        Unit
    }
}
