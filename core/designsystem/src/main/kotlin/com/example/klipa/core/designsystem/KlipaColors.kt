package com.example.klipa.core.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Klipa's palette. Quiet graphite chrome carries the UI so the footage is the brightest
 * thing on screen; a single amber accent marks the playhead, the selection and the
 * primary action, and nothing else.
 */
@Immutable
data class KlipaColors(
    /** Behind everything: the viewer surround and system bar area. */
    val canvas: Color,
    /** Docked panels: timeline, toolbar. */
    val panel: Color,
    /** Raised within a panel: track headers, ruler, pressed rows. */
    val raised: Color,
    val outline: Color,
    val outlineStrong: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val accent: Color,
    val onAccent: Color,
    /** Accent at low strength, for selection fills and focus halos. */
    val accentMuted: Color,
    val danger: Color,
    val clipVideo: Color,
    val clipImage: Color,
    val clipAudio: Color,
    val clipText: Color,
    /** Labels and glyphs drawn on top of clip fills. */
    val onClip: Color,
    /** Waveforms and fade ramps drawn inside clips. */
    val clipDetail: Color,
)

val KlipaDarkColors = KlipaColors(
    canvas = Color(0xFF0B0C0E),
    panel = Color(0xFF141619),
    raised = Color(0xFF1C1F23),
    outline = Color(0xFF2A2E34),
    outlineStrong = Color(0xFF3A3F47),
    textPrimary = Color(0xFFECEDEF),
    textSecondary = Color(0xFFA4A9B1),
    textTertiary = Color(0xFF858B94),
    accent = Color(0xFFFFC23D),
    onAccent = Color(0xFF1C1400),
    accentMuted = Color(0x33FFC23D),
    danger = Color(0xFFFF6B66),
    clipVideo = Color(0xFF34516E),
    clipImage = Color(0xFF45507A),
    clipAudio = Color(0xFF2B6152),
    clipText = Color(0xFF654A80),
    onClip = Color(0xFFE8ECF2),
    clipDetail = Color(0x8CE8ECF2),
)

val LocalKlipaColors = staticCompositionLocalOf { KlipaDarkColors }
