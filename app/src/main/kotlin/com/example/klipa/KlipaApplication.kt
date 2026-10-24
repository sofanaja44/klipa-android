package com.example.klipa

import android.app.Application
import com.example.klipa.core.projects.ProjectLibrary
import com.example.klipa.feature.export.ExportController
import com.example.klipa.feature.export.ExportHost
import java.io.File

/**
 * Holds what outlives activities: the project library every screen shares, and the
 * exporter, so an export keeps running when the activity goes away.
 */
class KlipaApplication : Application(), ExportHost {
    val projects: ProjectLibrary by lazy { ProjectLibrary(File(filesDir, "projects")) }

    override val exporter: ExportController by lazy { ExportController(this) }
}
