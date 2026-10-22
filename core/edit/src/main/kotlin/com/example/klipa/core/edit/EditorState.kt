package com.example.klipa.core.edit

import com.example.klipa.core.model.Clip
import com.example.klipa.core.model.Project
import com.example.klipa.core.model.createProject
import com.example.klipa.core.model.validateProject
import java.time.Instant
import java.time.temporal.ChronoUnit

// Editor state and undo history, ported from ReaCut src/editor/store.ts. The zustand store
// becomes an immutable value: every action is a function from one EditorState to the next,
// so a ViewModel can hold it in a StateFlow and tests can drive it directly.

/** Undo entries kept before the oldest is dropped. */
const val HISTORY_LIMIT = 150

/** Timeline zoom range, in pixels per second. */
val ZoomRange: ClosedFloatingPointRange<Double> = 5.0..400.0

data class EditorState(
    val project: Project = createProject(),
    /** Where the project was last saved or opened from; null for a new project. */
    val path: String? = null,
    val selectedIds: List<String> = emptyList(),
    val playhead: Double = 0.0,
    val playing: Boolean = false,
    /** Pixels per second of timeline. */
    val zoom: Double = 60.0,
    val snapping: Boolean = true,
    /** Deleting closes the gap on the deleted clips' tracks. */
    val magnetic: Boolean = false,
    /** Unsaved changes since the last [markSaved] or [setProject]. */
    val dirty: Boolean = false,
    val past: List<Project> = emptyList(),
    val future: List<Project> = emptyList(),
    val clipboard: List<Clip> = emptyList(),
    val transactionOpen: Boolean = false,
    /** Whether the open transaction already pushed its one undo entry. */
    val transactionRecorded: Boolean = false,
) {
    val canUndo: Boolean get() = past.isNotEmpty()
    val canRedo: Boolean get() = future.isNotEmpty()
}

/** Opens [project] (validated) with fresh history, selection and clipboard. */
fun EditorState.setProject(project: Project, path: String? = null): EditorState = copy(
    project = validateProject(project),
    path = path,
    selectedIds = emptyList(),
    playhead = 0.0,
    playing = false,
    dirty = false,
    past = emptyList(),
    future = emptyList(),
    clipboard = emptyList(),
    transactionOpen = false,
    transactionRecorded = false,
)

/**
 * Makes [project] current and records the previous one for undo. Unchanged projects are
 * ignored, so a refused edit never adds an undo entry. Inside a transaction only the
 * first change is recorded, so one drag or slider gesture undoes as a single step.
 */
fun EditorState.commit(project: Project, now: Instant = Instant.now()): EditorState {
    if (project === this.project || project == this.project) return this
    return copy(
        project = project.copy(updatedAt = now.truncatedTo(ChronoUnit.MILLIS).toString()),
        dirty = true,
        past = if (transactionOpen && transactionRecorded) past else (past + this.project).takeLast(HISTORY_LIMIT),
        future = emptyList(),
        transactionRecorded = transactionOpen,
    )
}

fun EditorState.beginTransaction(): EditorState =
    if (transactionOpen) this else copy(transactionOpen = true, transactionRecorded = false)

fun EditorState.endTransaction(): EditorState = copy(transactionOpen = false, transactionRecorded = false)

fun EditorState.undo(): EditorState {
    if (past.isEmpty()) return this
    return copy(
        project = past.last(),
        past = past.dropLast(1),
        future = listOf(project) + future,
        dirty = true,
        selectedIds = emptyList(),
        playing = false,
        transactionOpen = false,
        transactionRecorded = false,
    )
}

fun EditorState.redo(): EditorState {
    if (future.isEmpty()) return this
    return copy(
        project = future.first(),
        past = (past + project).takeLast(HISTORY_LIMIT),
        future = future.drop(1),
        dirty = true,
        selectedIds = emptyList(),
        playing = false,
        transactionOpen = false,
        transactionRecorded = false,
    )
}

fun EditorState.markSaved(path: String): EditorState = copy(path = path, dirty = false)

/** Selects clips together with their linked partners. */
fun EditorState.select(ids: List<String>): EditorState = copy(selectedIds = linkedIds(project, ids))

/** Moves the playhead (never before 0) and pauses playback. */
fun EditorState.seek(time: Double): EditorState =
    if (time.isFinite()) copy(playhead = maxOf(0.0, time), playing = false) else this

fun EditorState.setPlaying(playing: Boolean): EditorState = copy(playing = playing)

fun EditorState.setZoom(zoom: Double): EditorState =
    if (zoom.isFinite()) copy(zoom = zoom.coerceIn(ZoomRange)) else this

fun EditorState.setSnapping(snapping: Boolean): EditorState = copy(snapping = snapping)

fun EditorState.setMagnetic(magnetic: Boolean): EditorState = copy(magnetic = magnetic)
