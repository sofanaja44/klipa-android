package com.example.klipa

import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.klipa.core.designsystem.Klipa
import com.example.klipa.core.designsystem.KlipaTheme
import com.example.klipa.core.media.MediaFrameSource
import com.example.klipa.core.media.MediaProbe
import com.example.klipa.core.model.Project
import com.example.klipa.core.projects.ProjectSummary
import com.example.klipa.feature.export.ExportViewModel
import com.example.klipa.feature.timeline.EditorViewModel
import com.example.klipa.feature.timeline.ProjectRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val library by lazy { (application as KlipaApplication).projects }

    private val viewModel: EditorViewModel by viewModels {
        viewModelFactory {
            initializer {
                EditorViewModel(
                    repository = object : ProjectRepository {
                        override suspend fun load(id: String): Project? = library.load(id)

                        override suspend fun save(project: Project) = library.save(project)
                    },
                )
            }
        }
    }

    private val exporter: ExportViewModel by viewModels {
        viewModelFactory {
            initializer { ExportViewModel(application) }
        }
    }

    private val frames by lazy { MediaFrameSource(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The editor is always dark, so the system bars are too.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        val probe = MediaProbe(this)
        setContent {
            KlipaTheme {
                val scope = rememberCoroutineScope()
                val openId by viewModel.openProjectId.collectAsState()
                val ready by viewModel.ready.collectAsState()
                val projects by library.projects.collectAsState()
                // If Android ended the process in the background, reopen the project that was open.
                var restoreId by rememberSaveable { mutableStateOf<String?>(null) }
                LaunchedEffect(Unit) {
                    restoreId?.let { if (viewModel.openProjectId.value == null) viewModel.open(it) }
                    library.refresh()
                }
                LaunchedEffect(openId) { restoreId = openId }

                when {
                    openId == null -> HomeScreen(
                        projects = projects,
                        frames = frames,
                        actions = object : HomeActions {
                            override fun create(format: ProjectFormat) {
                                scope.launch {
                                    val name = getString(R.string.project_default_name, (projects?.size ?: 0) + 1)
                                    viewModel.open(library.create(name, format.width, format.height).id)
                                }
                            }

                            override fun open(id: String) = viewModel.open(id)

                            override fun rename(id: String, name: String) {
                                scope.launch { library.rename(id, name) }
                            }

                            override fun duplicate(project: ProjectSummary) {
                                scope.launch { library.duplicate(project.id, getString(R.string.project_copy_name, project.name)) }
                            }

                            override fun delete(id: String) {
                                scope.launch { library.delete(id) }
                            }
                        },
                    )
                    // A project is loading: an empty canvas, not an empty project.
                    !ready -> Box(Modifier.fillMaxSize().background(Klipa.colors.canvas))
                    else -> {
                        var importing by remember { mutableStateOf(false) }
                        fun import(uris: List<Uri>, overlay: Boolean = false) {
                            if (uris.isEmpty()) return
                            scope.launch {
                                importing = true
                                val result = withContext(Dispatchers.IO) { probe.probe(uris) }
                                if (overlay) viewModel.importOverlay(result.assets, result.failed) else viewModel.importMedia(result.assets, result.failed)
                                importing = false
                            }
                        }
                        val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { import(it) }
                        val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { import(it) }
                        val pickOverlay = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { import(it, overlay = true) }
                        EditorScreen(
                            viewModel = viewModel,
                            exporter = exporter,
                            frames = frames,
                            importing = importing,
                            onPickMedia = { pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
                            onPickAudio = { pickAudio.launch(arrayOf("audio/*")) },
                            onPickOverlay = { pickOverlay.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
                            onBack = viewModel::close,
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Each activity instance owns its decoders, including across configuration changes.
        frames.release()
    }
}
