package com.example.klipa.feature.timeline

import com.example.klipa.core.edit.EditRejectedException
import com.example.klipa.core.edit.EditorState
import com.example.klipa.core.edit.TrimEdge
import com.example.klipa.core.edit.setProject
import com.example.klipa.core.model.MediaKind
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

    @Test
    fun `restores the saved project and autosaves after edits settle`() = runTest(dispatcher) {
        val saved = fixture()
        val repository = object : ProjectRepository {
            val writes = mutableListOf<Project>()
            override suspend fun load() = saved
            override suspend fun save(project: Project) {
                writes += project
            }
        }
        val vm = EditorViewModel(repository = repository)
        assertFalse(vm.ready.value)
        runCurrent()
        assertTrue(vm.ready.value)
        assertEquals(saved, vm.state.value.project)
        assertNull(repository.writes.firstOrNull())
        vm.addText()
        vm.addText()
        advanceTimeBy(500)
        assertEquals(0, repository.writes.size)
        advanceTimeBy(1000)
        assertEquals(listOf(vm.state.value.project), repository.writes)
    }

    private fun com.example.klipa.core.model.Clip.end() = start + duration
}
