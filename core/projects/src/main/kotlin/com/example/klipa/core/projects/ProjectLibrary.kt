package com.example.klipa.core.projects

import com.example.klipa.core.model.InvalidProjectException
import com.example.klipa.core.model.MediaAsset
import com.example.klipa.core.model.MediaKind
import com.example.klipa.core.model.Project
import com.example.klipa.core.model.ProjectJson
import com.example.klipa.core.model.TrackKind
import com.example.klipa.core.model.createProject
import com.example.klipa.core.model.newId
import com.example.klipa.core.model.projectDuration
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** A project as the project list shows it, without loading the whole project. */
@Serializable
data class ProjectSummary(
    val id: String,
    val name: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    /** Seconds. */
    val duration: Double,
    val updatedAt: String,
    val clipCount: Int,
    /** Where to take a cover picture from, if the project has any picture. */
    val cover: Cover? = null,
)

/** The first frame of a project's first picture: [time] seconds into [asset]. */
@Serializable
data class Cover(val asset: MediaAsset, val time: Double)

/** The project list's view of [project]. */
fun summarize(project: Project): ProjectSummary {
    val visual = project.tracks.filter { it.kind != TrackKind.Audio && !it.hidden }.mapTo(HashSet()) { it.id }
    val assets = project.assets.associateBy { it.id }
    val cover = project.clips
        .filter { it.trackId in visual }
        .sortedBy { it.start }
        .firstNotNullOfOrNull { clip ->
            assets[clip.assetId]?.takeIf { it.kind != MediaKind.Audio && it.missing != true }?.let { asset ->
                // Waveforms are of no use to a cover and can be large.
                Cover(asset.copy(waveform = null), if (asset.kind == MediaKind.Image) 0.0 else clip.sourceIn)
            }
        }
    return ProjectSummary(
        id = project.id,
        name = project.name,
        width = project.width,
        height = project.height,
        fps = project.fps,
        duration = projectDuration(project),
        updatedAt = project.updatedAt,
        clipCount = project.clips.size,
        cover = cover,
    )
}

/**
 * Every project on the device, one ReaCut-compatible JSON file each, in [directory].
 *
 * A small index caches each file's summary, keyed by its modification time, so listing
 * projects never parses them all. Writes go to a temporary file first, so a crash mid-save
 * never leaves half a project; a file that fails validation is set aside, never
 * overwritten. Safe to use from any thread.
 */
class ProjectLibrary(
    private val directory: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Instant = Instant::now,
) {
    private val mutex = Mutex()
    private val entries = HashMap<String, Entry>()
    private val _projects = MutableStateFlow<List<ProjectSummary>?>(null)

    /** Every project, most recently edited first; null until [refresh] has run. */
    val projects: StateFlow<List<ProjectSummary>?> = _projects.asStateFlow()

    /** Reads the project list from disk, taking in projects saved by older versions. */
    suspend fun refresh() = locked {
        directory.mkdirs()
        migrateAutosave()
        val index = readIndex()
        entries.clear()
        val files = directory.listFiles { file -> file.name.endsWith(SUFFIX) }.orEmpty()
        for (file in files) {
            val cached = index[file.name]?.takeIf { it.modified == file.lastModified() }
            val entry = cached ?: readProject(file)?.let { Entry(file.name, file.lastModified(), summarize(it)) } ?: continue
            entries[entry.summary.id] = entry
        }
        writeIndex()
        publish()
    }

    /** The project [id], or null if it is gone or cannot be read. */
    suspend fun load(id: String): Project? = locked { readProject(fileFor(id)) }

    suspend fun save(project: Project) = locked { write(project) }

    /** Creates and saves an empty project of [width] × [height] pixels. */
    suspend fun create(name: String, width: Int, height: Int): Project = locked {
        createProject(name, clock()).copy(width = width, height = height).also(::write)
    }

    /** Renames a project; blank names are ignored. Its place in the list is kept. */
    suspend fun rename(id: String, name: String) = locked {
        val trimmed = name.trim().take(MAX_NAME_LENGTH)
        if (trimmed.isEmpty()) return@locked
        val project = readProject(fileFor(id)) ?: return@locked
        if (project.name != trimmed) write(project.copy(name = trimmed))
    }

    /** Saves a copy of project [id] named [name], as if just created; null if [id] is gone. */
    suspend fun duplicate(id: String, name: String): Project? = locked {
        val project = readProject(fileFor(id)) ?: return@locked null
        val now = clock().truncatedTo(ChronoUnit.MILLIS).toString()
        project.copy(id = newId(), name = name.trim().take(MAX_NAME_LENGTH).ifEmpty { project.name }, createdAt = now, updatedAt = now)
            .also(::write)
    }

    /** Deletes project [id]. The media it uses stays on the device. */
    suspend fun delete(id: String) = locked {
        fileFor(id).delete()
        entries.remove(id)
        writeIndex()
        publish()
    }

    private suspend fun <T> locked(block: () -> T): T = mutex.withLock { withContext(io) { block() } }

    private fun fileFor(id: String) = File(directory, id.replace(UnsafeFileCharacters, "_") + SUFFIX)

    private fun write(project: Project) {
        directory.mkdirs()
        val file = fileFor(project.id)
        writeAtomically(file, ProjectJson.encode(project))
        entries[project.id] = Entry(file.name, file.lastModified(), summarize(project))
        writeIndex()
        publish()
    }

    private fun readProject(file: File): Project? {
        if (!file.exists()) return null
        return try {
            ProjectJson.decode(file.readText())
        } catch (e: InvalidProjectException) {
            setAside(file)
            null
        } catch (e: IOException) {
            null
        }
    }

    /** Keeps an unreadable file for recovery, out of the list. */
    private fun setAside(file: File) {
        file.renameTo(File(directory, "${file.name.removeSuffix(SUFFIX)}.damaged-${clock().toEpochMilli()}.json"))
    }

    /** Klipa 0.7 and older kept a single project in autosave.klipa.json. */
    private fun migrateAutosave() {
        val old = File(directory, LEGACY_AUTOSAVE)
        if (!old.exists()) return
        val project = readProject(old) ?: return
        val target = fileFor(project.id)
        if (!target.exists()) writeAtomically(target, ProjectJson.encode(project))
        old.delete()
    }

    private fun readIndex(): Map<String, Entry> {
        val file = File(directory, INDEX)
        if (!file.exists()) return emptyMap()
        return try {
            IndexJson.decodeFromString(Index.serializer(), file.readText()).entries.associateBy { it.file }
        } catch (e: SerializationException) {
            emptyMap()
        } catch (e: IllegalArgumentException) {
            emptyMap()
        } catch (e: IOException) {
            emptyMap()
        }
    }

    private fun writeIndex() {
        writeAtomically(File(directory, INDEX), IndexJson.encodeToString(Index.serializer(), Index(entries.values.sortedBy { it.file })))
    }

    private fun publish() {
        _projects.value = entries.values.map { it.summary }.sortedByDescending { instantOf(it.updatedAt) }
    }

    private fun writeAtomically(file: File, text: String) {
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(text)
        if (!temp.renameTo(file)) {
            file.delete()
            temp.renameTo(file)
        }
    }

    @Serializable
    private data class Index(val entries: List<Entry>)

    /** A project file's summary as of its [modified] time. */
    @Serializable
    private data class Entry(val file: String, val modified: Long, val summary: ProjectSummary)

    private companion object {
        const val SUFFIX = ".klipa.json"
        const val INDEX = "index.json"
        const val LEGACY_AUTOSAVE = "autosave.klipa.json"
        const val MAX_NAME_LENGTH = 120
        val UnsafeFileCharacters = Regex("[^A-Za-z0-9_-]")
        val IndexJson = Json { ignoreUnknownKeys = true }

        /** Project timestamps are ISO-8601; anything else sorts as oldest. */
        fun instantOf(text: String): Instant = try {
            Instant.parse(text)
        } catch (e: java.time.format.DateTimeParseException) {
            Instant.EPOCH
        }
    }
}
