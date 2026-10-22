package com.example.klipa

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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.klipa.core.designsystem.Klipa
import com.example.klipa.core.designsystem.KlipaSpacing
import com.example.klipa.core.edit.EditorState
import com.example.klipa.core.model.MediaKind
import com.example.klipa.core.model.formatTime
import com.example.klipa.core.model.projectDuration
import com.example.klipa.feature.preview.FrameSource
import com.example.klipa.feature.preview.ProgramMonitor
import com.example.klipa.feature.timeline.EditorViewModel
import com.example.klipa.feature.timeline.Timeline
import com.example.klipa.feature.timeline.describe
import com.example.klipa.feature.timeline.formatSpeed
import kotlinx.coroutines.isActive

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
) {
    val state by viewModel.state.collectAsState()
    val colors = Klipa.colors
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    var speedSheet by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { snackbar.showSnackbar(it.describe(resources)) }
    }
    // Playback clock: advances the playhead by real elapsed time each frame.
    LaunchedEffect(state.playing) {
        if (!state.playing) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (isActive) {
            withFrameNanos { now ->
                viewModel.advance((now - last) / 1e9)
                last = now
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
        )
    }
    val importingDescription = stringResource(R.string.importing)
    val timeline = @Composable { modifier: Modifier ->
        Box(modifier) {
            Timeline(viewModel, onAddMedia = onPickMedia, modifier = Modifier.fillMaxSize())
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
                            EditorTopBar(state, onUndo = viewModel::undo, onRedo = viewModel::redo)
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
                        EditorTopBar(state, onUndo = viewModel::undo, onRedo = viewModel::redo)
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
    if (speedSheet && selected != null) {
        SpeedSheet(current = selected.speed, onSpeed = viewModel::setSpeed, onDismiss = { speedSheet = false })
    }
}

@Composable
private fun EditorTopBar(state: EditorState, onUndo: () -> Unit, onRedo: () -> Unit) {
    val colors = Klipa.colors
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
                ToolButton(Icons.Rounded.Speed, R.string.tool_speed, enabled = !locked && primary.text == null, onClick = onSpeed)
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
