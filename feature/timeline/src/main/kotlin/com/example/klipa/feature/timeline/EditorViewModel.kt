package com.example.klipa.feature.timeline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.klipa.core.edit.EditRejectedException
import com.example.klipa.core.edit.EditorState
import com.example.klipa.core.edit.PlacementMode
import com.example.klipa.core.edit.SourceRange
import com.example.klipa.core.edit.TrackDirection
import com.example.klipa.core.edit.TrimEdge
import com.example.klipa.core.edit.addAssets
import com.example.klipa.core.edit.addText
import com.example.klipa.core.edit.beginTransaction
import com.example.klipa.core.edit.commit
import com.example.klipa.core.edit.deleteSelected
import com.example.klipa.core.edit.deleteTrack
import com.example.klipa.core.edit.detachAudio
import com.example.klipa.core.edit.duplicate
import com.example.klipa.core.edit.endTransaction
import com.example.klipa.core.edit.linkedIds
import com.example.klipa.core.edit.moveLinked
import com.example.klipa.core.edit.placeAsset
import com.example.klipa.core.edit.redo
import com.example.klipa.core.edit.reorderTrack
import com.example.klipa.core.edit.seek
import com.example.klipa.core.edit.select
import com.example.klipa.core.edit.setPlaying
import com.example.klipa.core.edit.setProject
import com.example.klipa.core.edit.setZoom
import com.example.klipa.core.edit.split
import com.example.klipa.core.edit.trimLinked
import com.example.klipa.core.edit.undo
import com.example.klipa.core.edit.unlinkSelected
import com.example.klipa.core.edit.updateAssetMetadata
import com.example.klipa.core.edit.updateClip
import com.example.klipa.core.edit.updateTrack
import com.example.klipa.core.model.Clip
import com.example.klipa.core.model.InvalidProjectException
import com.example.klipa.core.model.MediaAsset
import com.example.klipa.core.model.MediaKind
import com.example.klipa.core.model.Project
import com.example.klipa.core.model.Track
import com.example.klipa.core.model.end
import com.example.klipa.core.model.projectDuration
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** Something the editor tells the user once, e.g. in a snackbar. */
sealed interface EditorMessage {
    data class Rejected(val reason: EditRejectedException.Reason) : EditorMessage

    data object InvalidProject : EditorMessage

    /** [count] picked files could not be read as media. */
    data class ImportFailed(val count: Int) : EditorMessage

    /** Real-time preview failed on this device; the editor shows still frames instead. */
    data object PreviewFallback : EditorMessage
}

/** Where the editor keeps its working project between launches. */
interface ProjectRepository {
    /** The last saved project, or null if there is none or it cannot be read. */
    suspend fun load(): Project?

    suspend fun save(project: Project)
}

/** How long stills last when imported. */
const val STILL_DURATION_SECONDS = 5.0

/**
 * Holds the editor state and turns UI intents into [EditorState] actions. Rejected edits
 * leave the state untouched and surface as [messages].
 *
 * Drags (trim, move) are gestures: every update is computed from the state at the start
 * of the drag, so snapping never accumulates error, and the whole drag is one undo step.
 */
@OptIn(FlowPreview::class)
class EditorViewModel(
    initial: EditorState = EditorState(),
    private val repository: ProjectRepository? = null,
) : ViewModel() {
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<EditorState> = _state.asStateFlow()

    private val _ready = MutableStateFlow(repository == null)

    /** False until the saved project has been restored. */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val _messages = Channel<EditorMessage>(Channel.BUFFERED)
    val messages: Flow<EditorMessage> = _messages.receiveAsFlow()

    private var gestureStart: EditorState? = null

    init {
        if (repository != null) {
            viewModelScope.launch {
                repository.load()?.let { saved -> edit { it.setProject(saved) } }
                _ready.value = true
                // Saves once edits settle; the restored project itself is not re-saved.
                state.map { it.project }
                    .distinctUntilChanged()
                    .drop(1)
                    .debounce(AUTOSAVE_DELAY_MS)
                    .collect { repository.save(it) }
            }
        }
    }

    private fun edit(action: (EditorState) -> EditorState) {
        try {
            _state.value = action(_state.value)
        } catch (e: EditRejectedException) {
            _messages.trySend(EditorMessage.Rejected(e.reason))
        } catch (e: InvalidProjectException) {
            _messages.trySend(EditorMessage.InvalidProject)
        }
    }

    fun openProject(project: Project) = edit { it.setProject(project) }

    // Transport

    fun seek(time: Double) = edit { it.seek(time) }

    fun togglePlay() = edit { s ->
        val duration = projectDuration(s.project)
        when {
            s.playing -> s.setPlaying(false)
            duration <= 0 -> s
            s.playhead >= duration - 1e-6 -> s.seek(0.0).setPlaying(true)
            else -> s.setPlaying(true)
        }
    }

    fun pause() = edit { it.setPlaying(false) }

    /** Follows the real-time player: moves the playhead without stopping playback. */
    fun syncPlayhead(seconds: Double) = edit { s ->
        if (s.playing) s.copy(playhead = seconds.coerceIn(0.0, projectDuration(s.project))) else s
    }

    /** The player reached the end of the timeline. */
    fun finishPlayback() = edit { it.copy(playhead = projectDuration(it.project), playing = false) }

    /** Attaches a computed waveform to every asset reading [path]. Not an undo step. */
    fun attachWaveform(path: String, waveform: List<Double>) = edit { s ->
        s.updateAssetMetadata(path) { it.copy(waveform = waveform) }
    }

    /** Saves right away, e.g. when the app goes to the background. */
    fun saveNow() {
        val repository = repository ?: return
        if (!_ready.value) return
        val project = _state.value.project
        viewModelScope.launch { repository.save(project) }
    }

    /**
     * Opens an edit session (a sheet with sliders or a text field): everything until
     * [endEditSession] is one undo step.
     */
    fun beginEditSession() = edit { it.setPlaying(false).beginTransaction() }

    fun endEditSession() = edit { it.endTransaction() }

    /** Applies [change] to the first selected clip; inside an edit session it joins that step. */
    fun updateSelectedClip(change: (Clip) -> Clip) = edit { s ->
        s.selectedIds.firstOrNull()?.let { id -> s.updateClip(id, change) } ?: s
    }

    fun reportPreviewFallback() {
        _messages.trySend(EditorMessage.PreviewFallback)
    }

    /** Advances the playhead during frame-by-frame playback; stops at the end of the timeline. */
    fun advance(seconds: Double) = edit { s ->
        if (!s.playing) return@edit s
        val duration = projectDuration(s.project)
        val next = s.playhead + seconds
        if (next >= duration) s.copy(playhead = duration, playing = false) else s.copy(playhead = next)
    }

    fun setZoom(pxPerSecond: Double) = edit { it.setZoom(pxPerSecond) }

    // Selection

    fun select(clipId: String) = edit { it.select(listOf(clipId)) }

    fun clearSelection() = edit { it.select(emptyList()) }

    // Edits

    fun undo() = edit { it.undo() }

    fun redo() = edit { it.redo() }

    fun split() = edit { it.split() }

    fun deleteSelected() = edit { it.deleteSelected() }

    fun duplicate() = edit { it.duplicate() }

    fun addText() = edit { it.addText() }

    fun detachAudio() = edit { s -> s.selectedIds.firstOrNull()?.let { s.detachAudio(it) } ?: s }

    fun unlinkSelected() = edit { it.unlinkSelected() }

    /** Sets the speed of the first selected clip; its linked partners follow. */
    fun setSpeed(speed: Double) = edit { s -> s.selectedIds.firstOrNull()?.let { id -> s.updateClip(id) { it.copy(speed = speed) } } ?: s }

    fun updateTrack(trackId: String, change: (Track) -> Track) = edit { it.updateTrack(trackId, change) }

    fun reorderTrack(trackId: String, direction: TrackDirection) = edit { it.reorderTrack(trackId, direction) }

    fun deleteTrack(trackId: String) = edit { it.deleteTrack(trackId) }

    /**
     * Adds media and inserts it at the playhead in order, rippling later clips. Files
     * already in the project are reused rather than added twice. One undo step.
     */
    fun importMedia(assets: List<MediaAsset>, failed: Int = 0) {
        if (failed > 0) _messages.trySend(EditorMessage.ImportFailed(failed))
        if (assets.isEmpty()) return
        edit { start ->
            var s = start.beginTransaction().setPlaying(false).addAssets(assets)
            val insertAt = s.playhead
            for (incoming in assets) {
                val asset = s.project.assets.first { it.path == incoming.path }
                val duration = if (asset.kind == MediaKind.Image) STILL_DURATION_SECONDS else asset.duration
                s = s.placeAsset(asset.id, null, SourceRange(0.0, duration), PlacementMode.Insert)
                val placed = s.project.clips.first { it.id == s.selectedIds.first() }
                s = s.seek(placed.end)
            }
            s.endTransaction().seek(insertAt).select(emptyList())
        }
    }

    // Gestures

    /** Starts a drag; pauses playback and opens an undo transaction. */
    fun beginGesture() {
        val s = _state.value.setPlaying(false)
        gestureStart = s
        _state.value = s.beginTransaction()
    }

    /**
     * Trims [edge] of a clip (and its partners) by [delta] seconds from where the drag
     * started, snapping to clip edges and the playhead within [snapSeconds].
     */
    fun dragTrim(clipId: String, edge: TrimEdge, delta: Double, snapSeconds: Double) {
        val base = gestureStart ?: return
        val clip = base.project.clips.find { it.id == clipId } ?: return
        val from = if (edge == TrimEdge.Start) clip.start else clip.end
        var time = from + delta
        if (base.snapping) {
            time = snap(time, snapTargets(base.project, base.playhead, linkedIds(base.project, listOf(clipId)).toSet()), snapSeconds)
        }
        _state.value = _state.value.commit(trimLinked(base.project, clipId, edge, time))
    }

    /**
     * Moves a clip (and its partners) by [delta] seconds from where the drag started.
     * With [trackId], the clip itself moves to that track if it accepts it.
     */
    fun dragMove(clipId: String, delta: Double, trackId: String?, snapSeconds: Double) {
        val base = gestureStart ?: return
        val members = linkedIds(base.project, listOf(clipId)).toSet()
        var adjusted = delta
        if (base.snapping) {
            val moving = base.project.clips.filter { it.id in members }
            adjusted = snapMove(moving, delta, snapTargets(base.project, base.playhead, members), snapSeconds)
        }
        _state.value = _state.value.commit(moveLinked(base.project, listOf(clipId), adjusted, trackId))
    }

    /** Ends a drag. A drag that ends where it began leaves no undo entry. */
    fun endGesture() {
        val start = gestureStart ?: return
        gestureStart = null
        val end = _state.value.endTransaction()
        // Commits stamp updatedAt, so compare the edit itself.
        val unchanged = end.project.copy(updatedAt = start.project.updatedAt) == start.project
        _state.value = if (unchanged) start.copy(selectedIds = end.selectedIds) else end
    }

    private companion object {
        const val AUTOSAVE_DELAY_MS = 800L
    }
}
