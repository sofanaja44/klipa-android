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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.klipa.core.designsystem.KlipaTheme
import com.example.klipa.core.media.MediaFrameSource
import com.example.klipa.core.media.MediaProbe
import com.example.klipa.feature.export.ExportViewModel
import com.example.klipa.feature.timeline.EditorViewModel
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val viewModel: EditorViewModel by viewModels {
        viewModelFactory {
            initializer { EditorViewModel(repository = ProjectFileRepository(File(filesDir, "projects"))) }
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
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Each activity instance owns its decoders, including across configuration changes.
        frames.release()
    }
}
