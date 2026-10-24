package com.example.klipa.feature.timeline

import com.example.klipa.core.edit.EditRejectedException
import com.example.klipa.core.edit.EditorState
import com.example.klipa.core.edit.TrimEdge
import com.example.klipa.core.edit.setProject
import com.example.klipa.core.edit.setSnapping
import com.example.klipa.core.model.MediaKind
import com.example.klipa.core.model.Transform
import com.example.klipa.core.model.Project
import com.example.klipa.core.model.createClip
import com.example.klipa.core.model.createProject
import com.example.klipa.core.model.fixture
import com.example.klipa.core.model.testAsset
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class EditorViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    /** The model fixture without its animation. */
    private fun still(): Project = fixture().let { p -> p.copy(clips = p.clips.map { it.copy(keyframes = emptyList()) }) }

    /** The model fixture (one 8 s clip at 2 s on the video track) opened in the editor. */
    private fun viewModel(project: Project = fixture()) = EditorViewModel(EditorState().setProject(project))

    @Test
    fun `a trim drag is one undo step and snaps to the playhead`() {
        val vm = viewModel()
        val clip = vm.state.value.project.clips[0]
        vm.seek(6.0)
        vm.select(clip.id)
        vm.beginGesture()
        vm.dragTrim(clip.id, TrimEdge.End, -3.0, snapSeconds = 0.1)
        vm.dragTrim(clip.id, TrimEdge.End, -3.97, snapSeconds = 0.1)
        vm.endGesture()
        val state = vm.state.value
        assertEquals(6.0, state.project.clips[0].end())
        assertEquals(1, state.past.size)
        assertFalse(state.transactionOpen)
        vm.undo()
        assertEquals(10.0, vm.state.value.project.clips[0].end())
    }

    @Test
    fun `a move is computed from the drag start and a drag back to the start leaves no undo entry`() {
        val vm = viewModel()
        val clip = vm.state.value.project.clips[0]
        vm.beginGesture()
        vm.dragMove(clip.id, 1.0, null, snapSeconds = 0.0)
        vm.dragMove(clip.id, 1.5, null, snapSeconds = 0.0)
        assertEquals(3.5, vm.state.value.project.clips[0].start)
        vm.dragMove(clip.id, 0.0, null, snapSeconds = 0.0)
        vm.endGesture()
        assertEquals(2.0, vm.state.value.project.clips[0].start)
        assertEquals(0, vm.state.value.past.size)
        assertFalse(vm.state.value.dirty)
    }

    @Test
    fun `arranging a clip on the monitor is one undo step and snaps to the center`() {
        val vm = viewModel(still())
        val clip = vm.state.value.project.clips[0]
        vm.select(clip.id)
        vm.beginGesture()
        vm.dragTransform(clip.id, dx = 300.0, dy = 5.0, scale = 0.6, rotation = 30.0, snap = 8.0)
        vm.dragTransform(clip.id, dx = 6.0, dy = 250.0, scale = 1.02, rotation = 88.0, snap = 8.0)
        vm.endGesture()
        val t = vm.state.value.project.clips[0].transform
        // Within 8 px of the center line, 3% of full size and 3° of a right angle: snapped.
        assertEquals(listOf(0.0, 250.0, 1.0, 90.0), listOf(t.x, t.y, t.scale, t.rotation))
        assertEquals(1, vm.state.value.past.size)
        vm.undo()
        assertEquals(Transform(), vm.state.value.project.clips[0].transform)
    }

    @Test
    fun `without snapping a clip goes exactly where it is dragged`() {
        val vm = EditorViewModel(EditorState().setProject(still()).setSnapping(false))
        val clip = vm.state.value.project.clips[0]
        vm.beginGesture()
        vm.dragTransform(clip.id, dx = 6.0, dy = -3.0, scale = 1.02, rotation = 89.0, snap = 8.0)
        vm.endGesture()
        val t = vm.state.value.project.clips[0].transform
        assertEquals(listOf(6.0, -3.0, 1.02, 89.0), listOf(t.x, t.y, t.scale, t.rotation))
    }

    @Test
    fun `an animated clip snaps where the playhead shows it`() {
        // The fixture animates x from 0 to 800 over its 8 s, starting at 2 s.
        val vm = viewModel()
        val clip = vm.state.value.project.clips[0]
        vm.seek(2.0)
        vm.beginGesture()
        vm.dragTransform(clip.id, dx = 5.0, dy = 0.0, scale = 1.0, rotation = 0.0, snap = 8.0)
        vm.endGesture()
        // Shown at x = 0, dragged 5 px: snapped back, so nothing changed and no undo entry.
        assertEquals(0, vm.state.value.past.size)
        vm.beginGesture()
        vm.dragTransform(clip.id, dx = 40.0, dy = 0.0, scale = 1.0, rotation = 0.0, snap = 8.0)
        vm.endGesture()
        assertEquals(listOf(40.0, 840.0), vm.state.value.project.clips[0].keyframes.map { it.value })
    }

    @Test
    fun `a move onto another video track carries the clip there`() {
        val base = fixture()
        val project = base.copy(tracks = base.tracks + base.tracks[1].copy(id = "video2", name = "Video 2"))
        val vm = viewModel(project)
        val clip = project.clips[0]
        vm.beginGesture()
        vm.dragMove(clip.id, 0.0, "video2", snapSeconds = 0.0)
        vm.endGesture()
        assertEquals("video2", vm.state.value.project.clips[0].trackId)
        assertEquals(1, vm.state.value.past.size)
    }

    @Test
    fun `rejected edits leave the state unchanged and report why`() = runTest(dispatcher) {
        val base = createProject()
        val text = createClip(null, base.tracks[0].id, 0.0, base.fps)
        val vm = viewModel(base.copy(clips = listOf(text)))
        vm.select(text.id)
        val before = vm.state.value
        vm.detachAudio()
        assertEquals(before, vm.state.value)
        assertEquals(EditorMessage.Rejected(EditRejectedException.Reason.NoAudioToDetach), vm.messages.first())
    }

    @Test
    fun `imports media in order at the playhead as one undo step`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.seek(2.0)
        val still = testAsset.copy(id = "still", path = "/tmp/still.jpg", url = "file:///tmp/still.jpg", kind = MediaKind.Image, duration = 0.0)
        val again = testAsset.copy(id = "duplicate-id")
        vm.importMedia(listOf(still, again), failed = 1)
        val state = vm.state.value
        val ranges = state.project.clips.sortedBy { it.start }.map { listOf(it.start, it.duration) }
        // The still (5 s) and the existing video (20 s) are inserted at 2 s; the original clip ripples after them.
        assertEquals(listOf(listOf(2.0, 5.0), listOf(7.0, 20.0), listOf(27.0, 8.0)), ranges)
        assertEquals(2, state.project.assets.size)
        assertEquals(1, state.past.size)
        assertEquals(2.0, state.playhead)
        assertEquals(EditorMessage.ImportFailed(1), vm.messages.first())
    }

    @Test
    fun `playback advances to the end of the timeline and stops`() {
        val vm = viewModel()
        vm.togglePlay()
        assertTrue(vm.state.value.playing)
        vm.advance(4.0)
        assertEquals(4.0, vm.state.value.playhead)
        vm.advance(10.0)
        assertEquals(10.0, vm.state.value.playhead)
        assertFalse(vm.state.value.playing)
        vm.togglePlay()
        assertEquals(0.0, vm.state.value.playhead)
        assertTrue(vm.state.value.playing)
    }

    private class MemoryRepository(vararg projects: Project) : ProjectRepository {
        val stored = projects.associateBy { it.id }.toMutableMap()
        val writes = mutableListOf<Project>()

        override suspend fun load(id: String) = stored[id]

        override suspend fun save(project: Project) {
            writes += project
            stored[project.id] = project
        }
    }

    @Test
    fun `opens a project and autosaves it after edits settle`() = runTest(dispatcher) {
        val saved = fixture()
        val repository = MemoryRepository(saved)
        val vm = EditorViewModel(repository = repository)
        assertNull(vm.openProjectId.value)
        vm.open(saved.id)
        assertFalse(vm.ready.value)
        runCurrent()
        assertTrue(vm.ready.value)
        assertEquals(saved, vm.state.value.project)
        advanceTimeBy(2000)
        // Opening is not an edit: nothing is written.
        assertEquals(0, repository.writes.size)
        vm.addText()
        vm.addText()
        advanceTimeBy(500)
        assertEquals(0, repository.writes.size)
        advanceTimeBy(1000)
        assertEquals(listOf(vm.state.value.project), repository.writes)
    }

    @Test
    fun `switching projects saves the open one and starts a fresh history`() = runTest(dispatcher) {
        val first = fixture()
        val second = createProject("Second")
        val repository = MemoryRepository(first, second)
        val vm = EditorViewModel(repository = repository)
        vm.open(first.id)
        runCurrent()
        vm.addText()
        val edited = vm.state.value.project

        // Closing saves at once, without waiting for the autosave.
        vm.close()
        runCurrent()
        assertNull(vm.openProjectId.value)
        assertFalse(vm.ready.value)
        assertEquals(listOf(edited), repository.writes)

        vm.open(second.id)
        runCurrent()
        assertEquals(second, vm.state.value.project)
        assertFalse(vm.state.value.canUndo)
        advanceTimeBy(2000)
        // Nothing of the closed project leaks into the next one's file.
        assertEquals(listOf(edited), repository.writes)
    }

    @Test
    fun `a project that cannot be read is not opened`() = runTest(dispatcher) {
        val vm = EditorViewModel(repository = MemoryRepository())
        vm.open("missing")
        runCurrent()
        assertNull(vm.openProjectId.value)
        assertFalse(vm.ready.value)
    }

    @Test
    fun `the format can change, as one undo step`() {
        val vm = viewModel()
        vm.setFormat(1080, 1920)
        assertEquals(1080 to 1920, vm.state.value.project.let { it.width to it.height })
        vm.undo()
        assertEquals(1920 to 1080, vm.state.value.project.let { it.width to it.height })
    }

    private fun com.example.klipa.core.model.Clip.end() = start + duration
}
