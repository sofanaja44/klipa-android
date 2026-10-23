package com.example.klipa.core.media

import com.example.klipa.core.model.Clip
import com.example.klipa.core.model.Project

/**
 * The project as last edited, for effects that follow edits while a composition plays:
 * the preview updates it on every edit, and effects read it for every frame on the GL
 * thread. Only values that do not change the composition's structure are read live (see
 * [playbackSignature]).
 */
class LiveProject(project: Project) {
    @Volatile
    private var clips: Map<String, Clip> = project.clips.associateBy { it.id }

    fun update(project: Project) {
        clips = project.clips.associateBy { it.id }
    }

    fun clip(id: String): Clip? = clips[id]
}
