package com.example.klipa.core.projects

import com.example.klipa.core.model.MediaKind
import com.example.klipa.core.model.ProjectJson
import com.example.klipa.core.model.TextStyle
import com.example.klipa.core.model.createClip
import com.example.klipa.core.model.createProject
import com.example.klipa.core.model.testAsset
import java.io.File
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir

class ProjectLibraryTest {
    @TempDir
    lateinit var temp: Path

    private val dir: File get() = temp.toFile()
    private var now = Instant.parse("2026-10-24T08:00:00Z")

    private fun TestScope.library() = ProjectLibrary(dir, StandardTestDispatcher(testScheduler)) { now }

    @Test
    fun `creates projects in a format and lists the latest edit first`() = runTest {
        val library = library()
        library.refresh()
        assertEquals(emptyList(), library.projects.value)
        val wide = library.create("Wide", 1920, 1080)
        now = now.plusSeconds(60)
        val tall = library.create("Tall", 1080, 1920)
        assertEquals(listOf("Tall", "Wide"), library.projects.value!!.map { it.name })
        assertEquals(1080 to 1920, library.projects.value!!.first().let { it.width to it.height })
        assertEquals(tall, library.load(tall.id))

        // An edit to the older one brings it to the top.
        library.save(wide.copy(updatedAt = now.plusSeconds(60).toString()))
        assertEquals(listOf("Wide", "Tall"), library.projects.value!!.map { it.name })
    }

    @Test
    fun `summaries carry duration, clip count and a cover from the first picture`() = runTest {
        val music = testAsset.copy(id = "music", kind = MediaKind.Audio, width = 0, height = 0)
        val base = createProject("Trip").copy(assets = listOf(testAsset, music))
        val project = base.copy(
            clips = listOf(
                createClip(music, base.tracks[2].id, 0.0, 30).copy(duration = 12.0),
                createClip(null, base.tracks[0].id, 0.0, 30).copy(text = TextStyle()),
                createClip(testAsset, base.tracks[1].id, 1.0, 30).copy(duration = 4.0, sourceIn = 3.0),
            ),
        )
        val summary = summarize(project)
        assertEquals(12.0, summary.duration)
        assertEquals(3, summary.clipCount)
        assertEquals(testAsset.id, summary.cover?.asset?.id)
        assertEquals(3.0, summary.cover?.time)
        assertNull(summarize(base).cover)
    }

    @Test
    fun `renames, duplicates and deletes`() = runTest {
        val library = library()
        library.refresh()
        val project = library.create("Draft", 1080, 1080)
        library.rename(project.id, "  Beach day ")
        assertEquals("Beach day", library.load(project.id)?.name)
        library.rename(project.id, "   ")
        assertEquals("Beach day", library.load(project.id)?.name)

        now = now.plusSeconds(10)
        val copy = assertNotNull(library.duplicate(project.id, "Beach day (copy)"))
        assertNotEquals(project.id, copy.id)
        assertEquals(listOf("Beach day (copy)", "Beach day"), library.projects.value!!.map { it.name })

        library.delete(project.id)
        assertNull(library.load(project.id))
        assertEquals(listOf(copy.id), library.projects.value!!.map { it.id })
    }

    @Test
    fun `the list comes back from the index or the files themselves`() = runTest {
        val first = library()
        first.refresh()
        val project = first.create("Kept", 1920, 1080)

        val second = library()
        second.refresh()
        assertEquals(listOf(project.id), second.projects.value!!.map { it.id })

        // Without the index, every file is read again.
        File(dir, "index.json").writeText("not json")
        val third = library()
        third.refresh()
        assertEquals(listOf("Kept"), third.projects.value!!.map { it.name })
    }

    @Test
    fun `the single project of older versions joins the list`() = runTest {
        val old = createProject("Before the update")
        File(dir, "autosave.klipa.json").writeText(ProjectJson.encode(old))
        val library = library()
        library.refresh()
        assertEquals(listOf(old.id), library.projects.value!!.map { it.id })
        assertEquals(old, library.load(old.id))
        assertFalse(File(dir, "autosave.klipa.json").exists())
    }

    @Test
    fun `damaged files are set aside, not listed or overwritten`() = runTest {
        File(dir, "broken.klipa.json").writeText("""{"version":1,"name":42}""")
        val library = library()
        library.refresh()
        assertEquals(emptyList(), library.projects.value)
        assertTrue(dir.listFiles()!!.any { it.name.startsWith("broken.damaged-") })
    }
}
