package com.example.klipa

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.klipa.core.designsystem.Klipa
import com.example.klipa.core.designsystem.KlipaSpacing
import com.example.klipa.core.model.Adjustments
import com.example.klipa.core.model.ChromaKey
import com.example.klipa.core.model.Clip
import com.example.klipa.core.model.MaskShape
import com.example.klipa.core.model.ShapeMask
import com.example.klipa.feature.preview.parseColor
import kotlin.math.roundToInt

/** A one-tap look: ReaCut's color presets (src/components/EffectsPanel.tsx). */
internal data class FilterPreset(@param:StringRes val name: Int, val swatch: List<String>, val adjustments: Adjustments)

internal val FilterPresets = listOf(
    FilterPreset(R.string.filter_original, listOf("#526658", "#baac84", "#65828d"), Adjustments()),
    FilterPreset(R.string.filter_cinema, listOf("#243744", "#b49868", "#406d7a"), Adjustments(brightness = -0.04, contrast = 1.22, saturation = 0.78, temperature = -0.08)),
    FilterPreset(R.string.filter_warm, listOf("#927b51", "#ead092", "#c4a570"), Adjustments(brightness = 0.05, contrast = 1.05, saturation = 1.1, temperature = 0.3)),
    FilterPreset(R.string.filter_cool, listOf("#2c5367", "#86acbc", "#55788e"), Adjustments(brightness = 0.015, contrast = 1.12, saturation = 0.9, temperature = -0.35)),
    FilterPreset(R.string.filter_mono, listOf("#343839", "#bebdb4", "#6d7270"), Adjustments(contrast = 1.15, saturation = 0.0)),
    FilterPreset(R.string.filter_vintage, listOf("#686a4c", "#c5b680", "#8c987d"), Adjustments(brightness = 0.045, contrast = 0.86, saturation = 0.72, temperature = 0.22)),
    FilterPreset(R.string.filter_vivid, listOf("#45836b", "#f6cf72", "#679baa"), Adjustments(brightness = 0.02, contrast = 1.1, saturation = 1.45)),
    FilterPreset(R.string.filter_soft, listOf("#8d9d9b", "#d6c9af", "#a4aeb9"), Adjustments(brightness = 0.03, contrast = 0.95, saturation = 0.9, temperature = 0.05, blur = 2.0)),
)

/** ReaCut's starting chroma key: a familiar green, modest tolerance, a real soft edge. */
internal val DefaultChromaKey = ChromaKey(enabled = true, color = "#00ff00", similarity = 0.1, blend = 0.05)

/** Key colors offered: pure and studio greens and blues. */
private val KeyColors = listOf(
    "#00ff00" to R.string.color_green,
    "#00b140" to R.string.color_studio_green,
    "#0000ff" to R.string.color_blue,
    "#0047bb" to R.string.color_studio_blue,
)

/** A new mask starts smaller than the frame, so its edge is visible right away. */
private fun defaultMask(shape: MaskShape) = ShapeMask(shape = shape, width = 0.8, height = 0.8, feather = 0.0, invert = false)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LookSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    // No scrim: the monitor above must show colors exactly while they are adjusted.
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Klipa.colors.raised, scrimColor = Color.Transparent) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = KlipaSpacing.lg)
                .padding(bottom = KlipaSpacing.xl),
            content = content,
        )
    }
}

@Composable
private fun SheetHeader(title: String, action: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = Klipa.colors.textPrimary, modifier = Modifier.weight(1f))
        action()
    }
}

@Composable
internal fun AdjustSheet(clip: Clip, onChange: ((Clip) -> Clip) -> Unit, onDismiss: () -> Unit) {
    val colors = Klipa.colors
    val adjust = clip.adjustments
    fun update(change: (Adjustments) -> Adjustments) = onChange { it.copy(adjustments = change(it.adjustments)) }
    LookSheet(onDismiss) {
        SheetHeader(stringResource(R.string.adjust_title)) {
            TextButton(onClick = { update { Adjustments() } }, enabled = adjust != Adjustments()) {
                Text(stringResource(R.string.adjust_reset))
            }
        }
        Text(stringResource(R.string.adjust_filters), style = MaterialTheme.typography.labelLarge, color = colors.textSecondary)
        Spacer(Modifier.height(KlipaSpacing.sm))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(KlipaSpacing.md)) {
            for (preset in FilterPresets) {
                FilterTile(preset, selected = adjust == preset.adjustments, onClick = { update { preset.adjustments } })
            }
        }
        Spacer(Modifier.height(KlipaSpacing.lg))
        LabeledSlider(
            label = stringResource(R.string.adjust_brightness),
            value = adjust.brightness.toFloat(),
            range = -1f..1f,
            display = signed(adjust.brightness * 100),
            onChange = { value -> update { it.copy(brightness = value.toDouble()) } },
        )
        LabeledSlider(
            label = stringResource(R.string.adjust_contrast),
            value = adjust.contrast.toFloat(),
            range = 0f..3f,
            display = percent(adjust.contrast),
            onChange = { value -> update { it.copy(contrast = value.toDouble()) } },
        )
        LabeledSlider(
            label = stringResource(R.string.adjust_saturation),
            value = adjust.saturation.toFloat(),
            range = 0f..3f,
            display = percent(adjust.saturation),
            onChange = { value -> update { it.copy(saturation = value.toDouble()) } },
        )
        LabeledSlider(
            label = stringResource(R.string.adjust_temperature),
            value = adjust.temperature.toFloat(),
            range = -1f..1f,
            display = signed(adjust.temperature * 100),
            onChange = { value -> update { it.copy(temperature = value.toDouble()) } },
        )
        LabeledSlider(
            label = stringResource(R.string.adjust_blur),
            value = adjust.blur.toFloat(),
            range = 0f..100f,
            display = "${adjust.blur.roundToInt()}",
            // Whole pixels, as ReaCut's slider steps.
            onChange = { value -> update { it.copy(blur = value.roundToInt().toDouble()) } },
        )
    }
}

@Composable
private fun FilterTile(preset: FilterPreset, selected: Boolean, onClick: () -> Unit) {
    val colors = Klipa.colors
    val name = stringResource(preset.name)
    Column(
        Modifier
            .width(64.dp)
            .clickable(role = Role.RadioButton, onClickLabel = name, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(52.dp)
                .border(if (selected) 2.dp else 1.dp, if (selected) colors.accent else colors.outlineStrong, CircleShape)
                .padding(4.dp)
                .background(Brush.linearGradient(preset.swatch.map { parseColor(it) ?: Color.Gray }), CircleShape),
        )
        Spacer(Modifier.height(KlipaSpacing.xs))
        Text(
            name,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) colors.accent else colors.textSecondary,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

@Composable
internal fun ChromaKeySheet(clip: Clip, onChange: ((Clip) -> Clip) -> Unit, onDismiss: () -> Unit) {
    val colors = Klipa.colors
    val key = clip.chromaKey
    val enabled = key?.enabled == true
    fun update(change: (ChromaKey) -> ChromaKey) = onChange { it.copy(chromaKey = change(it.chromaKey ?: DefaultChromaKey)) }
    LookSheet(onDismiss) {
        SheetHeader(stringResource(R.string.chroma_title)) {
            Switch(
                checked = enabled,
                onCheckedChange = { on -> update { it.copy(enabled = on) } },
                colors = SwitchDefaults.colors(checkedThumbColor = colors.onAccent, checkedTrackColor = colors.accent),
            )
        }
        Text(stringResource(R.string.chroma_hint), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        Spacer(Modifier.height(KlipaSpacing.lg))
        Text(stringResource(R.string.chroma_color), style = MaterialTheme.typography.labelLarge, color = colors.textSecondary)
        Spacer(Modifier.height(KlipaSpacing.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(KlipaSpacing.sm)) {
            for ((swatch, label) in KeyColors) {
                val selected = enabled && swatch.equals(key?.color, ignoreCase = true)
                val name = stringResource(label)
                Box(
                    Modifier
                        .size(40.dp)
                        .border(if (selected) 2.dp else 1.dp, if (selected) colors.accent else colors.outlineStrong, CircleShape)
                        .padding(4.dp)
                        .background(parseColor(swatch) ?: Color.Green, CircleShape)
                        .clickable(role = Role.RadioButton, onClickLabel = name) { update { it.copy(enabled = true, color = swatch) } },
                )
            }
        }
        Spacer(Modifier.height(KlipaSpacing.lg))
        val current = key ?: DefaultChromaKey
        LabeledSlider(
            label = stringResource(R.string.chroma_similarity),
            value = current.similarity.toFloat(),
            range = 0.01f..1f,
            display = percent(current.similarity),
            enabled = enabled,
            onChange = { value -> update { it.copy(similarity = value.toDouble()) } },
        )
        LabeledSlider(
            label = stringResource(R.string.chroma_blend),
            value = current.blend.toFloat(),
            range = 0f..1f,
            display = percent(current.blend),
            enabled = enabled,
            onChange = { value -> update { it.copy(blend = value.toDouble()) } },
        )
    }
}

@Composable
internal fun MaskSheet(clip: Clip, onChange: ((Clip) -> Clip) -> Unit, onDismiss: () -> Unit) {
    val colors = Klipa.colors
    val mask = clip.mask
    fun update(change: (ShapeMask) -> ShapeMask) = onChange { c -> c.mask?.let { c.copy(mask = change(it)) } ?: c }
    LookSheet(onDismiss) {
        SheetHeader(stringResource(R.string.mask_title))
        Row(horizontalArrangement = Arrangement.spacedBy(KlipaSpacing.sm)) {
            val chip = FilterChipDefaults.filterChipColors(selectedContainerColor = colors.accentMuted, selectedLabelColor = colors.accent)
            FilterChip(selected = mask == null, onClick = { onChange { it.copy(mask = null) } }, label = { Text(stringResource(R.string.mask_none)) }, colors = chip)
            for ((shape, label) in listOf(MaskShape.Rectangle to R.string.mask_rectangle, MaskShape.Ellipse to R.string.mask_ellipse)) {
                FilterChip(
                    selected = mask?.shape == shape,
                    onClick = { onChange { it.copy(mask = it.mask?.copy(shape = shape) ?: defaultMask(shape)) } },
                    label = { Text(stringResource(label)) },
                    colors = chip,
                )
            }
        }
        Spacer(Modifier.height(KlipaSpacing.lg))
        val current = mask ?: defaultMask(MaskShape.Rectangle)
        LabeledSlider(
            label = stringResource(R.string.mask_width),
            value = current.width.toFloat(),
            range = 0.01f..1f,
            display = percent(current.width),
            enabled = mask != null,
            onChange = { value -> update { it.copy(width = value.toDouble()) } },
        )
        LabeledSlider(
            label = stringResource(R.string.mask_height),
            value = current.height.toFloat(),
            range = 0.01f..1f,
            display = percent(current.height),
            enabled = mask != null,
            onChange = { value -> update { it.copy(height = value.toDouble()) } },
        )
        LabeledSlider(
            label = stringResource(R.string.mask_feather),
            value = current.feather.toFloat(),
            range = 0f..0.5f,
            display = percent(current.feather),
            enabled = mask != null,
            onChange = { value -> update { it.copy(feather = value.toDouble()) } },
        )
        Spacer(Modifier.height(KlipaSpacing.sm))
        FilterChip(
            selected = current.invert && mask != null,
            onClick = { update { it.copy(invert = !it.invert) } },
            enabled = mask != null,
            label = { Text(stringResource(R.string.mask_invert)) },
            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = colors.accentMuted, selectedLabelColor = colors.accent),
        )
    }
}

/** `+12`, `−30`, `0`: a signed whole number with a true minus sign. */
private fun signed(value: Double): String {
    val rounded = value.roundToInt()
    return when {
        rounded > 0 -> "+$rounded"
        rounded < 0 -> "−${-rounded}"
        else -> "0"
    }
}

private fun percent(value: Double): String = "${(value * 100).roundToInt()}%"
