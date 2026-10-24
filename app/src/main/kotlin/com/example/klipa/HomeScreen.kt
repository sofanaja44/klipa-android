package com.example.klipa

import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.klipa.core.designsystem.Klipa
import com.example.klipa.core.designsystem.KlipaShapes
import com.example.klipa.core.designsystem.KlipaSpacing
import com.example.klipa.core.media.FrameSource
import com.example.klipa.core.projects.Cover
import com.example.klipa.core.projects.ProjectSummary
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Locale
import kotlin.math.roundToInt

/** What the home screen can do with projects. */
internal interface HomeActions {
    fun create(format: ProjectFormat)

    fun open(id: String)

    fun rename(id: String, name: String)

    fun duplicate(project: ProjectSummary)

    fun delete(id: String)
}

/**
 * The home screen: a large button to start a project in a chosen format, then every
 * project, most recently edited first. [projects] is null while the list loads.
 */
@Composable
internal fun HomeScreen(projects: List<ProjectSummary>?, frames: FrameSource, actions: HomeActions) {
    val colors = Klipa.colors
    var newProject by rememberSaveable { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<ProjectSummary?>(null) }
    var deleting by remember { mutableStateOf<ProjectSummary?>(null) }

    Surface(Modifier.fillMaxSize(), color = colors.canvas, contentColor = colors.textPrimary) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(160.dp),
            modifier = Modifier.fillMaxSize().safeDrawingPadding(),
            contentPadding = PaddingValues(KlipaSpacing.lg),
            horizontalArrangement = Arrangement.spacedBy(KlipaSpacing.md),
            verticalArrangement = Arrangement.spacedBy(KlipaSpacing.lg),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, color = colors.textPrimary)
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                NewProjectButton(onClick = { newProject = true })
            }
            if (projects != null) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        if (projects.isEmpty()) stringResource(R.string.home_empty) else stringResource(R.string.home_projects, projects.size),
                        style = if (projects.isEmpty()) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.labelLarge,
                        color = colors.textSecondary,
                        modifier = Modifier.padding(top = KlipaSpacing.sm),
                    )
                }
                items(projects, key = { it.id }) { project ->
                    ProjectCard(
                        project = project,
                        frames = frames,
                        onOpen = { actions.open(project.id) },
                        onRename = { renaming = project },
                        onDuplicate = { actions.duplicate(project) },
                        onDelete = { deleting = project },
                    )
                }
            }
        }
    }

    if (newProject) {
        FormatSheet(
            title = stringResource(R.string.new_project_title),
            hint = stringResource(R.string.new_project_hint),
            current = null,
            onPick = { format ->
                newProject = false
                actions.create(format)
            },
            onDismiss = { newProject = false },
        )
    }
    renaming?.let { project ->
        RenameSheet(project.name, onRename = { name -> actions.rename(project.id, name) }, onDismiss = { renaming = null })
    }
    deleting?.let { project ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            containerColor = colors.raised,
            title = { Text(stringResource(R.string.delete_project_title)) },
            text = { Text(stringResource(R.string.delete_project_message, project.name)) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    actions.delete(project.id)
                }) { Text(stringResource(R.string.action_delete), color = colors.danger) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun NewProjectButton(onClick: () -> Unit) {
    val colors = Klipa.colors
    Row(
        Modifier
            .fillMaxWidth()
            .height(88.dp)
            .clip(KlipaShapes.control)
            .background(colors.raised)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = KlipaSpacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp).background(colors.accent, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Add, contentDescription = null, tint = colors.onAccent, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.width(KlipaSpacing.lg))
        Column {
            Text(stringResource(R.string.new_project), style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
            Text(stringResource(R.string.new_project_subtitle), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        }
    }
}

@Composable
private fun ProjectCard(
    project: ProjectSummary,
    frames: FrameSource,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = Klipa.colors
    var menu by remember { mutableStateOf(false) }
    // The whole card opens the project; its menu button handles its own taps.
    Column(
        Modifier
            .fillMaxWidth()
            .clip(KlipaShapes.control)
            .clickable(role = Role.Button, onClickLabel = project.name, onClick = onOpen),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 10)
                .clip(KlipaShapes.control)
                .background(colors.panel),
            contentAlignment = Alignment.Center,
        ) {
            // The cover keeps the project's own shape inside the card.
            Box(
                Modifier
                    .padding(KlipaSpacing.sm)
                    .aspectRatio(project.width.toFloat() / project.height, matchHeightConstraintsFirst = project.height * 16 > project.width * 10)
                    .clip(KlipaShapes.clip)
                    .background(Color.Black),
            ) {
                project.cover?.let { CoverImage(it, frames) }
            }
            Badge(formatLabel(project.width, project.height), Modifier.align(Alignment.TopStart))
            Badge(clock(project.duration), Modifier.align(Alignment.BottomEnd))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(top = KlipaSpacing.sm)) {
                Text(project.name, style = MaterialTheme.typography.titleSmall, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(edited(project.updatedAt), style = MaterialTheme.typography.labelSmall, color = colors.textTertiary, maxLines = 1)
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.project_options, project.name), tint = colors.textSecondary)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    for ((label, action) in listOf(R.string.action_rename to onRename, R.string.tool_duplicate to onDuplicate, R.string.action_delete to onDelete)) {
                        DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = {
                            menu = false
                            action()
                        })
                    }
                }
            }
        }
    }
}

@Composable
private fun CoverImage(cover: Cover, frames: FrameSource) {
    val maxPx = with(LocalDensity.current) { 240.dp.roundToPx() }
    var image by remember(cover) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(cover) { image = frames.thumbnail(cover.asset, cover.time, maxPx) }
    image?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
}

@Composable
private fun Badge(text: String, modifier: Modifier) {
    Text(
        text,
        style = Klipa.textStyles.timecodeSmall,
        color = Color.White,
        modifier = modifier
            .padding(KlipaSpacing.sm)
            .background(Color.Black.copy(alpha = 0.6f), KlipaShapes.clip)
            .padding(horizontal = KlipaSpacing.xs + KlipaSpacing.xxs, vertical = KlipaSpacing.xxs)
            .semantics { contentDescription = text },
    )
}

/** `m:ss`, or `h:mm:ss` from an hour. */
internal fun clock(seconds: Double): String {
    val total = seconds.roundToInt().coerceAtLeast(0)
    val hours = total / 3600
    return if (hours > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, total / 60 % 60, total % 60)
    } else {
        String.format(Locale.ROOT, "%d:%02d", total / 60, total % 60)
    }
}

/** "5 min. ago", "Yesterday", "Oct 12": when a project was last edited, as the system says it. */
@Composable
private fun edited(updatedAt: String): String {
    val millis = try {
        Instant.parse(updatedAt).toEpochMilli()
    } catch (e: DateTimeParseException) {
        return ""
    }
    return DateUtils.getRelativeTimeSpanString(millis, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
}

/** A sheet with the project's name, ready to edit, and a button to keep the new one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RenameSheet(current: String, onRename: (String) -> Unit, onDismiss: () -> Unit) {
    val colors = Klipa.colors
    var name by rememberSaveable { mutableStateOf(current) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.raised) {
        Column(Modifier.fillMaxWidth().padding(horizontal = KlipaSpacing.lg).padding(bottom = KlipaSpacing.xl)) {
            Text(stringResource(R.string.rename_project_title), style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
            Spacer(Modifier.height(KlipaSpacing.md))
            OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(KlipaSpacing.lg))
            Button(
                onClick = {
                    onRename(name)
                    onDismiss()
                },
                enabled = name.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) { Text(stringResource(R.string.action_rename)) }
        }
    }
}
