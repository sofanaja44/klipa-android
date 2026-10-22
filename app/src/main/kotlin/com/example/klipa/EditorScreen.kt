package com.example.klipa

import android.content.ClipData
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.unit.sp
import com.example.klipa.core.designsystem.KlipaShapes
import com.example.klipa.core.media.playbackSignature
import com.example.klipa.feature.preview.PreviewPlayer
import com.example.klipa.feature.timeline.EditorMessage
import java.util.Locale
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import androidx.compose.foundation.border
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.Color
import com.example.klipa.core.model.Clip
import kotlin.math.roundToInt
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallSplit
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.example.klipa.core.designsystem.Klipa
import com.example.klipa.core.designsystem.KlipaSpacing
import com.example.klipa.core.edit.EditorState
import com.example.klipa.core.model.MediaKind
import com.example.klipa.core.model.formatTime
import com.example.klipa.core.model.projectDuration
import com.example.klipa.core.media.FrameSource
import com.example.klipa.core.media.WaveformExtractor
import com.example.klipa.feature.preview.ProgramMonitor
import com.example.klipa.feature.preview.parseColor
import com.example.klipa.feature.preview.rememberPreviewPlayer
import com.example.klipa.feature.timeline.EditorViewModel
import com.example.klipa.feature.timeline.Timeline
import com.example.klipa.feature.timeline.describe
import com.example.klipa.feature.timeline.formatSpeed
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** How long an edit must settle before the player reloads the project. */
private const val PREVIEW_RELOAD_DELAY_MS = 150L

/** Minimum time between seeks while scrubbing a paused player. */
private const val SEEK_INTERVAL_MS = 40L

/** Speeds offered in the speed sheet. */
private val SpeedPresets = listOf(0.25, 0.5, 0.75, 1.0, 1.5, 2.0, 3.0, 4.0)

/**
 * The editor: program monitor on top, transport, the timeline, and a contextual toolbar
 * that offers clip tools while something is selected and import tools otherwise.
 */
@Composable
fun EditorScreen(
    viewModel: EditorViewModel,
    frames: FrameSource,
    importing: Boolean,
    onPickMedia: () -> Unit,
    onPickAudio: () -> Unit,
    realTimePreview: Boolean = true,
) {
    val state by viewModel.state.collectAsState()
    val colors = Klipa.colors
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    var speedSheet by remember { mutableStateOf(false) }
    var diagnosticsSheet by remember { mutableStateOf(false) }
    var openSheet by remember { mutableStateOf<ClipSheet?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            val details = if (message == EditorMessage.PreviewFallback) resources.getString(R.string.action_details) else null
            val result = snackbar.showSnackbar(message.describe(resources), actionLabel = details, withDismissAction = details != null)
            if (result == SnackbarResult.ActionPerformed) diagnosticsSheet = true
        }
    }
    val preview = rememberPreviewPlayer(
        onFailure = { viewModel.reportPreviewFallback() },
        onSystemPause = viewModel::pause,
    )
    // Reload the player once an edit that changes playback settles; renames, finished
    // waveforms and drags in progress do not interrupt it.
    val signature = remember(state.project) { playbackSignature(state.project) }
    LaunchedEffect(preview, signature, state.transactionOpen) {
        if (!realTimePreview || state.transactionOpen) return@LaunchedEffect
        delay(PREVIEW_RELOAD_DELAY_MS)
        preview.setProject(viewModel.state.value.project, viewModel.state.value.playhead)
    }
    // While paused, the player follows the playhead, at most every couple of frames.
    LaunchedEffect(preview, state.playing, preview.active) {
        if (state.playing || !preview.active) return@LaunchedEffect
        preview.pause()
        viewModel.state.map { it.playhead }.distinctUntilChanged().conflate().collect {
            preview.seekTo(it)
            delay(SEEK_INTERVAL_MS)
        }
    }
    // While playing, the player's clock drives the playhead; without a player, a frame
    // clock advances it and the monitor shows still frames.
    LaunchedEffect(preview, state.playing, preview.active) {
        if (!state.playing) return@LaunchedEffect
        if (preview.active) {
            preview.seekTo(viewModel.state.value.playhead)
            preview.play()
            while (isActive) {
                withFrameNanos { }
                if (preview.ended) {
                    viewModel.finishPlayback()
                    break
                }
                viewModel.syncPlayhead(preview.position)
            }
        } else {
            var last = withFrameNanos { it }
            while (isActive) {
                withFrameNanos { now ->
                    viewModel.advance((now - last) / 1e9)
                    last = now
                }
            }
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        viewModel.pause()
        // Do not wait for the autosave delay: the process may be killed in the background.
        viewModel.saveNow()
    }

    // Waveforms are computed once per file in the background, for imports and for
    // projects saved before they finished. Jobs outlive changes to the asset list.
    val context = LocalContext.current
    val waveforms = remember { WaveformExtractor(context) }
    val scope = rememberCoroutineScope()
    val waveformJobs = remember { mutableMapOf<String, Job>() }
    // One file at a time, so imports never compete with playback for every core.
    val waveformTurn = remember { Mutex() }
    LaunchedEffect(state.project.assets) {
        for (asset in state.project.assets) {
            if (!asset.hasAudio || asset.waveform != null || asset.missing == true || asset.path in waveformJobs) continue
            waveformJobs[asset.path] = scope.launch {
                waveformTurn.withLock { waveforms.extract(asset) }?.let { viewModel.attachWaveform(asset.path, it) }
            }
        }
    }

    val monitor = @Composable { modifier: Modifier ->
        ProgramMonitor(
            project = state.project,
            time = state.playhead,
            frames = frames,
            contentDescription = stringResource(R.string.monitor_description),
            modifier = modifier,
            player = preview,
            playing = state.playing,
        )
    }
    val importingDescription = stringResource(R.string.importing)
    val timeline = @Composable { modifier: Modifier ->
        Box(modifier) {
            Timeline(viewModel, onAddMedia = onPickMedia, modifier = Modifier.fillMaxSize(), frames = frames)
            if (importing) {
                LinearProgressIndicator(
                    Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .align(Alignment.TopCenter)
                        .semantics { contentDescription = importingDescription },
                )
            }
        }
    }
    val toolbar = @Composable {
        EditorToolbar(
            state = state,
            onPickMedia = onPickMedia,
            onPickAudio = onPickAudio,
            onAddText = viewModel::addText,
            onDone = viewModel::clearSelection,
            onSplit = viewModel::split,
            onSpeed = { speedSheet = true },
            onEditText = { openSheet = ClipSheet.Text },
            onVolume = { openSheet = ClipSheet.Volume },
            onDetachAudio = viewModel::detachAudio,
            onUnlink = viewModel::unlinkSelected,
            onDuplicate = viewModel::duplicate,
            onDelete = viewModel::deleteSelected,
        )
    }

    // The Surface sets the default content color for icons and text below it.
    Surface(Modifier.fillMaxSize(), color = colors.canvas, contentColor = colors.textPrimary) {
        Box(Modifier.fillMaxSize()) {
            BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
                if (maxWidth > maxHeight) {
                    // Landscape: the monitor keeps the left half, the timeline gets the full height on the right.
                    Row(Modifier.fillMaxSize()) {
                        Column(Modifier.weight(0.9f).fillMaxHeight()) {
                            EditorTopBar(state, onUndo = viewModel::undo, onRedo = viewModel::redo, onDiagnostics = { diagnosticsSheet = true })
                            monitor(Modifier.weight(1f).fillMaxWidth())
                            TransportBar(state, onTogglePlay = viewModel::togglePlay)
                        }
                        Box(Modifier.width(1.dp).fillMaxHeight().background(colors.outline))
                        Column(Modifier.weight(1.1f).fillMaxHeight()) {
                            timeline(Modifier.weight(1f).fillMaxWidth())
                            toolbar()
                        }
                    }
                } else {
                    val timelineHeight = (maxHeight * 0.4f).coerceIn(180.dp, 360.dp)
                    Column(Modifier.fillMaxSize()) {
                        EditorTopBar(state, onUndo = viewModel::undo, onRedo = viewModel::redo, onDiagnostics = { diagnosticsSheet = true })
                        monitor(Modifier.weight(1f).fillMaxWidth())
                        TransportBar(state, onTogglePlay = viewModel::togglePlay)
                        timeline(Modifier.fillMaxWidth().height(timelineHeight))
                        toolbar()
                    }
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(bottom = 72.dp))
        }
    }

    val selected = state.project.clips.find { it.id == state.selectedIds.firstOrNull() }
    val sheet = openSheet
    if (sheet != null && selected != null) {
        // Everything changed while a sheet is open is one undo step.
        DisposableEffect(sheet) {
            viewModel.beginEditSession()
            onDispose { viewModel.endEditSession() }
        }
        when (sheet) {
            ClipSheet.Text -> TextSheet(selected, onChange = viewModel::updateSelectedClip, onDismiss = { openSheet = null })
            ClipSheet.Volume -> VolumeSheet(selected, onChange = viewModel::updateSelectedClip, onDismiss = { openSheet = null })
        }
    } else if (sheet != null) {
        openSheet = null
    }
    if (speedSheet && selected != null) {
        SpeedSheet(current = selected.speed, onSpeed = viewModel::setSpeed, onDismiss = { speedSheet = false })
    }
    if (diagnosticsSheet) {
        DiagnosticsSheet(report = { diagnosticsReport(context, state, preview) }, revision = preview.revision, onDismiss = { diagnosticsSheet = false })
    }
}

/** Everything useful in a playback bug report. Media file names are left out. */
private fun diagnosticsReport(context: Context, state: EditorState, preview: PreviewPlayer): String = buildString {
    val version = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }
    appendLine("Klipa $version on ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
    val project = state.project
    appendLine("project: ${project.width}x${project.height} @ ${project.fps} fps, ${project.tracks.size} tracks, ${project.clips.size} clips, playhead ${"%.2f".format(Locale.ROOT, state.playhead)} s, playing ${state.playing}")
    project.assets.forEachIndexed { index, asset ->
        val scheme = asset.url.substringBefore(':')
        appendLine("asset $index: ${asset.kind.name.lowercase()} ${asset.width}x${asset.height} ${"%.2f".format(Locale.ROOT, asset.fps)} fps ${"%.2f".format(Locale.ROOT, asset.duration)} s audio=${asset.hasAudio} ($scheme)")
    }
    append(preview.diagnostics())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiagnosticsSheet(report: () -> String, revision: Int, onDismiss: () -> Unit) {
    val colors = Klipa.colors
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    // Re-read the report whenever the player's state changes.
    val text = remember(revision) { report() }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.raised) {
        Column(Modifier.fillMaxWidth().padding(horizontal = KlipaSpacing.lg).padding(bottom = KlipaSpacing.xl)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.diagnostics_title), style = MaterialTheme.typography.titleMedium, color = colors.textPrimary, modifier = Modifier.weight(1f))
                TextButton(onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Klipa diagnostics", text))) } }) {
                    Text(stringResource(R.string.action_copy))
                }
            }
            Text(stringResource(R.string.diagnostics_hint), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
            Spacer(Modifier.height(KlipaSpacing.md))
            Text(
                text,
                style = Klipa.textStyles.timecodeSmall.copy(fontSize = 11.sp, lineHeight = 15.sp),
                color = colors.textPrimary,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .background(colors.panel, KlipaShapes.control)
                    .verticalScroll(rememberScrollState())
                    .padding(KlipaSpacing.md),
            )
        }
    }
}

@Composable
private fun EditorTopBar(state: EditorState, onUndo: () -> Unit, onRedo: () -> Unit, onDiagnostics: () -> Unit) {
    val colors = Klipa.colors
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().height(52.dp).padding(start = KlipaSpacing.lg, end = KlipaSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(state.project.name, style = MaterialTheme.typography.titleSmall, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(R.string.project_format, state.project.width, state.project.height, state.project.fps),
                style = MaterialTheme.typography.labelSmall,
                color = colors.textTertiary,
            )
        }
        IconButton(onClick = onUndo, enabled = state.canUndo) {
            Icon(Icons.AutoMirrored.Rounded.Undo, contentDescription = stringResource(R.string.action_undo))
        }
        IconButton(onClick = onRedo, enabled = state.canRedo) {
            Icon(Icons.AutoMirrored.Rounded.Redo, contentDescription = stringResource(R.string.action_redo))
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.action_more))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.diagnostics_title)) },
                    onClick = {
                        menu = false
                        onDiagnostics()
                    },
                )
            }
        }
    }
}

@Composable
private fun TransportBar(state: EditorState, onTogglePlay: () -> Unit) {
    val colors = Klipa.colors
    val styles = Klipa.textStyles
    val fps = state.project.fps
    Box(Modifier.fillMaxWidth().height(52.dp).background(colors.panel).padding(horizontal = KlipaSpacing.lg)) {
        Column(Modifier.align(Alignment.CenterStart)) {
            Text(formatTime(state.playhead, fps), style = styles.timecode, color = colors.textPrimary)
            Text(formatTime(projectDuration(state.project), fps), style = styles.timecodeSmall, color = colors.textTertiary)
        }
        IconButton(
            onClick = onTogglePlay,
            modifier = Modifier.align(Alignment.Center).size(40.dp).background(colors.raised, CircleShape),
        ) {
            Icon(
                if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = stringResource(if (state.playing) R.string.action_pause else R.string.action_play),
                tint = colors.textPrimary,
            )
        }
    }
}

@Composable
private fun EditorToolbar(
    state: EditorState,
    onPickMedia: () -> Unit,
    onPickAudio: () -> Unit,
    onAddText: () -> Unit,
    onDone: () -> Unit,
    onSplit: () -> Unit,
    onSpeed: () -> Unit,
    onEditText: () -> Unit,
    onVolume: () -> Unit,
    onDetachAudio: () -> Unit,
    onUnlink: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = Klipa.colors
    val project = state.project
    val selected = project.clips.filter { it.id in state.selectedIds }
    val primary = selected.firstOrNull()
    Column(Modifier.fillMaxWidth().background(colors.panel)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.outline))
        Row(
            Modifier.fillMaxWidth().height(64.dp).horizontalScroll(rememberScrollState()).padding(horizontal = KlipaSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (primary == null) {
                ToolButton(Icons.Rounded.VideoLibrary, R.string.tool_media, onClick = onPickMedia)
                ToolButton(Icons.Rounded.MusicNote, R.string.tool_audio, onClick = onPickAudio)
                ToolButton(Icons.Rounded.TextFields, R.string.tool_text, onClick = onAddText)
            } else {
                val asset = project.assets.find { it.id == primary.assetId }
                val locked = selected.any { clip -> project.tracks.find { it.id == clip.trackId }?.locked != false }
                val underPlayhead = selected.any { state.playhead > it.start && state.playhead < it.start + it.duration }
                ToolButton(Icons.Rounded.Check, R.string.tool_done, accent = true, onClick = onDone)
                ToolButton(Icons.Rounded.ContentCut, R.string.tool_split, enabled = !locked && underPlayhead, onClick = onSplit)
                if (primary.text != null) {
                    ToolButton(Icons.Rounded.Edit, R.string.tool_edit_text, enabled = !locked, onClick = onEditText)
                } else {
                    ToolButton(Icons.Rounded.Speed, R.string.tool_speed, enabled = !locked, onClick = onSpeed)
                    if (asset?.hasAudio == true && asset.kind != MediaKind.Image) {
                        ToolButton(Icons.AutoMirrored.Rounded.VolumeUp, R.string.tool_volume, enabled = !locked, onClick = onVolume)
                    }
                }
                if (asset?.kind == MediaKind.Video && asset.hasAudio && primary.linkId == null) {
                    ToolButton(Icons.AutoMirrored.Rounded.CallSplit, R.string.tool_detach_audio, enabled = !locked, onClick = onDetachAudio)
                }
                if (selected.any { it.linkId != null }) {
                    ToolButton(Icons.Rounded.LinkOff, R.string.tool_unlink, enabled = !locked, onClick = onUnlink)
                }
                ToolButton(Icons.Rounded.ContentCopy, R.string.tool_duplicate, enabled = !locked, onClick = onDuplicate)
                ToolButton(Icons.Rounded.Delete, R.string.tool_delete, enabled = !locked, onClick = onDelete)
            }
        }
    }
}

@Composable
private fun ToolButton(icon: ImageVector, label: Int, enabled: Boolean = true, accent: Boolean = false, onClick: () -> Unit) {
    val colors = Klipa.colors
    val tint = if (accent) colors.accent else colors.textPrimary
    Column(
        Modifier
            .width(68.dp)
            .height(56.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.38f),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(KlipaSpacing.xs))
        Text(stringResource(label), style = MaterialTheme.typography.labelSmall, color = if (accent) colors.accent else colors.textSecondary, maxLines = 1)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SpeedSheet(current: Double, onSpeed: (Double) -> Unit, onDismiss: () -> Unit) {
    val colors = Klipa.colors
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.raised) {
        Column(Modifier.fillMaxWidth().padding(horizontal = KlipaSpacing.lg).padding(bottom = KlipaSpacing.xl)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(stringResource(R.string.speed_title), style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
                Spacer(Modifier.width(KlipaSpacing.sm))
                Text(formatSpeed(current), style = Klipa.textStyles.timecode, color = colors.accent)
            }
            Spacer(Modifier.height(KlipaSpacing.xs))
            Text(stringResource(R.string.speed_hint), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
            Spacer(Modifier.height(KlipaSpacing.lg))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(KlipaSpacing.sm), verticalArrangement = Arrangement.spacedBy(KlipaSpacing.sm)) {
                for (speed in SpeedPresets) {
                    FilterChip(
                        selected = speed == current,
                        onClick = { onSpeed(speed) },
                        label = { Text(formatSpeed(speed), style = Klipa.textStyles.timecode) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = colors.accentMuted,
                            selectedLabelColor = colors.accent,
                        ),
                    )
                }
            }
        }
    }
}

private enum class ClipSheet { Text, Volume }

/** Swatches offered for text color: white and black, the accent, and a few clear hues. */
private val TextColors = listOf("#ffffff", "#000000", "#ffc23d", "#ff5a5f", "#4cc9f0", "#7bd88f")

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun TextSheet(clip: Clip, onChange: ((Clip) -> Clip) -> Unit, onDismiss: () -> Unit) {
    val colors = Klipa.colors
    val style = clip.text ?: return
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.raised) {
        Column(Modifier.fillMaxWidth().padding(horizontal = KlipaSpacing.lg).padding(bottom = KlipaSpacing.xl)) {
            Text(stringResource(R.string.text_title), style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
            Spacer(Modifier.height(KlipaSpacing.md))
            OutlinedTextField(
                value = style.text,
                onValueChange = { value -> onChange { it.copy(name = value.lineSequence().firstOrNull().orEmpty().ifBlank { it.name }, text = it.text?.copy(text = value)) } },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )
            Spacer(Modifier.height(KlipaSpacing.lg))
            LabeledSlider(
                label = stringResource(R.string.text_size),
                value = style.fontSize.toFloat(),
                range = 16f..400f,
                display = "${style.fontSize.roundToInt()}",
                onChange = { size -> onChange { it.copy(text = it.text?.copy(fontSize = size.toDouble())) } },
            )
            Spacer(Modifier.height(KlipaSpacing.md))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(KlipaSpacing.sm), verticalArrangement = Arrangement.spacedBy(KlipaSpacing.sm)) {
                for (swatch in TextColors) {
                    val selected = swatch.equals(style.color, ignoreCase = true)
                    Box(
                        Modifier
                            .size(36.dp)
                            .border(if (selected) 2.dp else 1.dp, if (selected) colors.accent else colors.outlineStrong, CircleShape)
                            .padding(4.dp)
                            .background(parseColor(swatch) ?: Color.White, CircleShape)
                            .clickable(role = Role.RadioButton, onClickLabel = swatch) { onChange { it.copy(text = it.text?.copy(color = swatch)) } },
                    )
                }
                FilterChip(
                    selected = style.bold,
                    onClick = { onChange { it.copy(text = it.text?.copy(bold = !style.bold)) } },
                    label = { Text(stringResource(R.string.text_bold)) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = colors.accentMuted, selectedLabelColor = colors.accent),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VolumeSheet(clip: Clip, onChange: ((Clip) -> Clip) -> Unit, onDismiss: () -> Unit) {
    val colors = Klipa.colors
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.raised) {
        Column(Modifier.fillMaxWidth().padding(horizontal = KlipaSpacing.lg).padding(bottom = KlipaSpacing.xl)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.volume_title), style = MaterialTheme.typography.titleMedium, color = colors.textPrimary, modifier = Modifier.weight(1f))
                FilterChip(
                    selected = clip.muted,
                    onClick = { onChange { it.copy(muted = !clip.muted) } },
                    label = { Text(stringResource(R.string.volume_mute)) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = colors.accentMuted, selectedLabelColor = colors.accent),
                )
            }
            Spacer(Modifier.height(KlipaSpacing.md))
            LabeledSlider(
                label = stringResource(R.string.volume_level),
                value = clip.volume.toFloat(),
                range = 0f..2f,
                display = "${(clip.volume * 100).roundToInt()}%",
                enabled = !clip.muted,
                onChange = { volume -> onChange { it.copy(volume = volume.toDouble()) } },
            )
        }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: String,
    onChange: (Float) -> Unit,
    enabled: Boolean = true,
) {
    val colors = Klipa.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = colors.textSecondary, modifier = Modifier.weight(1f))
        Text(display, style = Klipa.textStyles.timecode, color = colors.textPrimary)
    }
    Slider(value = value.coerceIn(range), onValueChange = onChange, valueRange = range, enabled = enabled)
}
