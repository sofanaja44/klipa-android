package com.example.klipa.feature.export

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.klipa.core.designsystem.Klipa
import com.example.klipa.core.designsystem.KlipaSpacing
import com.example.klipa.core.media.planComposition
import com.example.klipa.core.model.Project
import com.example.klipa.core.model.projectDuration
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * The export sheet: pick a resolution, follow progress, then share or play the result.
 * It cannot be dismissed while an export runs; Cancel stops it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSheet(project: Project, exporter: ExportController, onDismiss: () -> Unit) {
    val colors = Klipa.colors
    val state by exporter.state.collectAsState()
    val busy = state is ExportState.Running || state is ExportState.Saving
    val currentBusy by rememberUpdatedState(busy)
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden || !currentBusy },
    )
    // Exports take a while; the screen staying on keeps the app in the foreground.
    val view = LocalView.current
    DisposableEffect(busy) {
        view.keepScreenOn = busy
        onDispose { view.keepScreenOn = false }
    }
    ModalBottomSheet(
        onDismissRequest = {
            if (!busy) {
                exporter.dismiss()
                onDismiss()
            }
        },
        sheetState = sheetState,
        containerColor = colors.raised,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = KlipaSpacing.lg).padding(bottom = KlipaSpacing.xl)) {
            when (val current = state) {
                ExportState.Idle -> SettingsContent(project, onExport = exporter::start)
                is ExportState.Running -> ProgressContent(current.progress, saving = false, onCancel = exporter::cancel)
                ExportState.Saving -> ProgressContent(1f, saving = true, onCancel = null)
                is ExportState.Done -> DoneContent(current.video, onClose = {
                    exporter.dismiss()
                    onDismiss()
                })
                is ExportState.Failed -> FailedContent(current, onRetry = exporter::dismiss)
            }
        }
    }
}

@Composable
private fun ColumnScope.SettingsContent(project: Project, onExport: (Project, ExportSettings) -> Unit) {
    val colors = Klipa.colors
    val context = LocalContext.current
    val options = remember(project.width, project.height) { exportResolutions(project) }
    var shortSide by rememberSaveable(project.width, project.height) { mutableIntStateOf(defaultExportSettings(project).shortSide) }
    val settings = ExportSettings(shortSide)
    val exportable = remember(project) { planComposition(project).videoSequences.isNotEmpty() }
    val missing = remember(project) { missingMediaClips(project) }
    val export = { onExport(project, settings) }
    // Before Android 10, saving into Movies needs storage permission. Without it the
    // export still runs and stays in the app, ready to share.
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { export() }

    Text(stringResource(R.string.export_title), style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
    Spacer(Modifier.height(KlipaSpacing.xxs))
    Text(
        stringResource(R.string.export_summary, formatClock(projectDuration(project)), project.width, project.height, project.fps),
        style = Klipa.textStyles.timecodeSmall,
        color = colors.textSecondary,
    )
    Spacer(Modifier.height(KlipaSpacing.lg))
    Text(stringResource(R.string.export_resolution), style = MaterialTheme.typography.labelLarge, color = colors.textSecondary)
    Spacer(Modifier.height(KlipaSpacing.sm))
    Row(horizontalArrangement = Arrangement.spacedBy(KlipaSpacing.sm)) {
        for (option in options) {
            FilterChip(
                selected = option == shortSide,
                onClick = { shortSide = option },
                label = { Text(stringResource(R.string.export_resolution_value, option), style = Klipa.textStyles.timecode) },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = colors.accentMuted, selectedLabelColor = colors.accent),
            )
        }
    }
    Spacer(Modifier.height(KlipaSpacing.sm))
    if (exportable) {
        val (width, height) = outputSize(project, settings)
        Text(
            stringResource(R.string.export_estimate, width, height, Formatter.formatShortFileSize(context, estimatedSizeBytes(project, settings))),
            style = MaterialTheme.typography.bodySmall,
            color = colors.textTertiary,
        )
        if (missing > 0) {
            Spacer(Modifier.height(KlipaSpacing.sm))
            Text(pluralStringResource(R.plurals.export_missing, missing, missing), style = MaterialTheme.typography.bodySmall, color = colors.danger)
        }
    } else {
        Text(stringResource(R.string.export_failure_nothing), style = MaterialTheme.typography.bodySmall, color = colors.danger)
    }
    Spacer(Modifier.height(KlipaSpacing.xl))
    Button(
        onClick = {
            // Android 9 and older need storage access for the gallery; 13 and newer ask
            // before showing the progress notification. Either way the export runs.
            val needed = when {
                Build.VERSION.SDK_INT < 29 -> Manifest.permission.WRITE_EXTERNAL_STORAGE
                Build.VERSION.SDK_INT >= 33 -> Manifest.permission.POST_NOTIFICATIONS
                else -> null
            }?.takeIf { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
            if (needed != null) permission.launch(needed) else export()
        },
        enabled = exportable,
        modifier = Modifier.fillMaxWidth().height(48.dp),
    ) {
        Text(stringResource(R.string.export_action))
    }
}

@Composable
private fun ColumnScope.ProgressContent(progress: Float?, saving: Boolean, onCancel: (() -> Unit)?) {
    val colors = Klipa.colors
    val description = stringResource(R.string.export_progress_description)
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            stringResource(if (saving) R.string.export_saving else R.string.export_running),
            style = MaterialTheme.typography.titleMedium,
            color = colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        if (progress != null) {
            Text("${(progress * 100).roundToInt()}%", style = Klipa.textStyles.timecode, color = colors.accent)
        }
    }
    Spacer(Modifier.height(KlipaSpacing.md))
    val barModifier = Modifier.fillMaxWidth().semantics { contentDescription = description }
    if (progress != null) {
        LinearProgressIndicator(progress = { progress }, modifier = barModifier, color = colors.accent, trackColor = colors.outline)
    } else {
        LinearProgressIndicator(modifier = barModifier, color = colors.accent, trackColor = colors.outline)
    }
    Spacer(Modifier.height(KlipaSpacing.md))
    Text(stringResource(R.string.export_keep_open), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
    Spacer(Modifier.height(KlipaSpacing.lg))
    OutlinedButton(onClick = { onCancel?.invoke() }, enabled = onCancel != null, modifier = Modifier.fillMaxWidth().height(48.dp)) {
        Text(stringResource(R.string.export_cancel))
    }
}

@Composable
private fun ColumnScope.DoneContent(video: ExportedVideo, onClose: () -> Unit) {
    val colors = Klipa.colors
    val context = LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = colors.accent, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(KlipaSpacing.sm))
        Text(
            stringResource(if (video.inGallery) R.string.export_done_gallery else R.string.export_done_app),
            style = MaterialTheme.typography.titleMedium,
            color = colors.textPrimary,
        )
    }
    Spacer(Modifier.height(KlipaSpacing.xs))
    Text(video.fileName, style = Klipa.textStyles.timecodeSmall, color = colors.textSecondary)
    Text(
        "${Formatter.formatShortFileSize(context, video.sizeBytes)} · ${formatClock(video.durationMs / 1000.0)}",
        style = Klipa.textStyles.timecodeSmall,
        color = colors.textTertiary,
    )
    if (!video.inGallery) {
        Spacer(Modifier.height(KlipaSpacing.sm))
        Text(stringResource(R.string.export_done_app_hint), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
    }
    Spacer(Modifier.height(KlipaSpacing.xl))
    Row(horizontalArrangement = Arrangement.spacedBy(KlipaSpacing.sm)) {
        Button(onClick = { shareVideo(context, video.uri) }, modifier = Modifier.weight(1f).height(48.dp)) {
            Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(KlipaSpacing.sm))
            Text(stringResource(R.string.export_share))
        }
        OutlinedButton(onClick = { playVideo(context, video.uri) }, modifier = Modifier.weight(1f).height(48.dp)) {
            Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(KlipaSpacing.sm))
            Text(stringResource(R.string.export_play))
        }
    }
    Spacer(Modifier.height(KlipaSpacing.xs))
    TextButton(onClick = onClose, modifier = Modifier.align(Alignment.CenterHorizontally)) {
        Text(stringResource(R.string.export_close))
    }
}

@Composable
private fun ColumnScope.FailedContent(failed: ExportState.Failed, onRetry: () -> Unit) {
    val colors = Klipa.colors
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = colors.danger, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(KlipaSpacing.sm))
        Text(stringResource(R.string.export_failed), style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
    }
    Spacer(Modifier.height(KlipaSpacing.sm))
    Text(stringResource(failed.failure.message), style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
    Spacer(Modifier.height(KlipaSpacing.xl))
    Row(horizontalArrangement = Arrangement.spacedBy(KlipaSpacing.sm)) {
        Button(onClick = onRetry, modifier = Modifier.weight(1f).height(48.dp)) {
            Text(stringResource(R.string.export_retry))
        }
        if (failed.detail.isNotBlank()) {
            OutlinedButton(
                onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Klipa export error", failed.detail))) } },
                modifier = Modifier.weight(1f).height(48.dp),
            ) {
                Text(stringResource(R.string.export_copy_details))
            }
        }
    }
}

internal val ExportFailure.message: Int
    get() = when (this) {
        ExportFailure.NothingToExport -> R.string.export_failure_nothing
        ExportFailure.MissingMedia -> R.string.export_failure_missing
        ExportFailure.UnsupportedMedia -> R.string.export_failure_unsupported
        ExportFailure.Encoder -> R.string.export_failure_encoder
        ExportFailure.Storage -> R.string.export_failure_storage
        ExportFailure.Unknown -> R.string.export_failure_unknown
    }

/** `m:ss`, or `h:mm:ss` from an hour. */
internal fun formatClock(seconds: Double): String {
    val total = seconds.roundToInt().coerceAtLeast(0)
    val hours = total / 3600
    val minutes = total / 60 % 60
    val secs = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(Locale.ROOT, hours, minutes, secs) else "%d:%02d".format(Locale.ROOT, minutes, secs)
}

private fun shareVideo(context: Context, uri: Uri) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("video/mp4")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    // The clip data carries the read grant through the chooser to the chosen app.
    send.clipData = ClipData.newRawUri(null, uri)
    context.startActivity(Intent.createChooser(send, null))
}

private fun playVideo(context: Context, uri: Uri) {
    val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(view)
    } catch (e: ActivityNotFoundException) {
        // No video player installed: sharing still works.
    }
}
