package com.example.klipa.core.media

import com.example.klipa.core.model.Clip
import com.example.klipa.core.model.Project
import com.example.klipa.core.model.TextStyle
import com.example.klipa.core.model.TrackKind
import com.example.klipa.core.model.createClip
import com.example.klipa.core.model.createProject
import com.example.klipa.core.model.createTrack
import com.example.klipa.core.model.testAsset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextLanesTest {
    private fun Project.title(track: Int, start: Double, duration: Double, words: String) =
        createClip(null, tracks[track].id, start, fps).copy(name = words, duration = duration, text = TextStyle(text = words))

    private fun List<List<Clip>>.names() = map { lane -> lane.map { it.name } }

    @Test
    fun `subtitles that follow each other share one lane`() {
        val base = createProject().copy(assets = listOf(testAsset))
        val p = base.copy(clips = (0 until 50).map { base.title(0, it * 2.0, 2.0, "line $it") })
        val lanes = planTextLanes(p)
        assertEquals(1, lanes.size)
        assertEquals(50, lanes.single().size)
    }

    @Test
    fun `overlapping titles stack, later ones above, and upper tracks draw last`() {
        val base = createProject()
        val upper = createTrack(TrackKind.Text, "Upper")
        val p = base.copy(tracks = listOf(upper) + base.tracks).let { withUpper ->
            withUpper.copy(
                clips = listOf(
                    withUpper.title(1, 0.0, 4.0, "a"),
                    withUpper.title(1, 1.0, 2.0, "b"),
                    withUpper.title(1, 4.0, 1.0, "c"),
                    withUpper.title(0, 0.0, 1.0, "top"),
                ),
            )
        }
        assertEquals(listOf(listOf("a", "c"), listOf("b"), listOf("top")), planTextLanes(p).names())
    }

    @Test
    fun `hidden tracks and media clips are left out`() {
        val base = createProject().copy(assets = listOf(testAsset))
        val p = base.copy(
            tracks = base.tracks.mapIndexed { index, track -> if (index == 0) track.copy(hidden = true) else track },
            clips = listOf(base.title(0, 0.0, 2.0, "hidden"), createClip(testAsset, base.tracks[1].id, 0.0, 30)),
        )
        assertTrue(planTextLanes(p).isEmpty())
    }
}
