package com.example.klipa

import android.app.Application
import com.example.klipa.core.projects.ProjectLibrary
import java.io.File

/** Holds what outlives activities: the one project library every screen shares. */
class KlipaApplication : Application() {
    val projects: ProjectLibrary by lazy { ProjectLibrary(File(filesDir, "projects")) }
}
