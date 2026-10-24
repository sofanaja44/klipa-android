package com.example.klipa

import androidx.annotation.StringRes
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.klipa.core.designsystem.Klipa
import com.example.klipa.core.designsystem.KlipaShapes
import com.example.klipa.core.designsystem.KlipaSpacing

/** Frame sizes offered for projects, at 1080p: portrait first, since phones shoot it most. */
internal enum class ProjectFormat(val width: Int, val height: Int, val ratio: String, @param:StringRes val use: Int) {
    Portrait(1080, 1920, "9:16", R.string.format_portrait_use),
    Landscape(1920, 1080, "16:9", R.string.format_landscape_use),
    Square(1080, 1080, "1:1", R.string.format_square_use),
    Feed(1080, 1350, "4:5", R.string.format_feed_use),
    ;

    companion object {
        fun of(width: Int, height: Int): ProjectFormat? = entries.firstOrNull { it.width * height == it.height * width }
    }
}

/** The project's shape as a short ratio, e.g. "9:16", or its pixel size when it is unusual. */
internal fun formatLabel(width: Int, height: Int): String = ProjectFormat.of(width, height)?.ratio ?: "$width×$height"

/**
 * A sheet of format tiles. [current] is highlighted; [onPick] receives the chosen format.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FormatSheet(title: String, hint: String?, current: ProjectFormat?, onPick: (ProjectFormat) -> Unit, onDismiss: () -> Unit) {
    val colors = Klipa.colors
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.raised) {
        Column(Modifier.fillMaxWidth().padding(horizontal = KlipaSpacing.lg).padding(bottom = KlipaSpacing.xl)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
            if (hint != null) {
                Spacer(Modifier.height(KlipaSpacing.xs))
                Text(hint, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
            }
            Spacer(Modifier.height(KlipaSpacing.lg))
            Row(horizontalArrangement = Arrangement.spacedBy(KlipaSpacing.sm)) {
                for (format in ProjectFormat.entries) {
                    FormatTile(format, selected = format == current, onClick = { onPick(format) }, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun FormatTile(format: ProjectFormat, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Klipa.colors
    val use = stringResource(format.use)
    Column(
        modifier
            .border(if (selected) 2.dp else 1.dp, if (selected) colors.accent else colors.outline, KlipaShapes.control)
            .clickable(role = Role.Button, onClickLabel = "${format.ratio} $use", onClick = onClick)
            .padding(vertical = KlipaSpacing.md, horizontal = KlipaSpacing.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The frame's shape, drawn to scale in a fixed square.
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .aspectRatio(format.width.toFloat() / format.height, matchHeightConstraintsFirst = format.height > format.width)
                    .background(if (selected) colors.accentMuted else colors.panel, KlipaShapes.clip)
                    .border(1.5.dp, if (selected) colors.accent else colors.textSecondary, KlipaShapes.clip),
            )
        }
        Spacer(Modifier.height(KlipaSpacing.sm))
        Text(format.ratio, style = Klipa.textStyles.timecode, color = if (selected) colors.accent else colors.textPrimary)
        Text(use, style = MaterialTheme.typography.labelSmall, color = colors.textTertiary, textAlign = TextAlign.Center, minLines = 2, maxLines = 2)
    }
}
