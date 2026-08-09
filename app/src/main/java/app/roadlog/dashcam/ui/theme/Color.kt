package app.roadlog.dashcam.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Background / surface — dark-first, near-black.
// Two variants: the default near-black dark theme, and an optional AMOLED
// pure-black variant for OLED screens (RoadLogTheme picks between them).
val BackgroundDark = Color(0xFF1A1A1D)
val BackgroundAmoled = Color(0xFF000000)
val SurfaceCard = Color(0xFF232327)
val SurfaceCardAmoled = Color(0xFF121212)
val SurfaceVariantDark = Color(0xFF2A2A2E)
val SurfaceVariantAmoled = Color(0xFF1A1A1A)

// Text/icon colors on the dark backgrounds above.
val OnSurfaceLight = Color(0xFFECECEC)
val OnSurfaceMuted = Color(0xFFA8A8AC)

// Subtle 1dp stroke used on flat cards instead of Material elevation shadows —
// reads better on a dark, always-on dashboard screen (§8.2).
val CardBorder = Color(0x1FFFFFFF)

// The original purple accent — kept as one of the selectable presets in
// AccentColorPicker, but no longer the default (see DefaultAccentColor below).
val AccentPrimary = Color(0xFFCFBAFD)

// RoadLog's actual default accent (matches AppSettings.accentColorArgb's default and the
// "green" preset in AccentColorPicker) — used wherever a fallback is needed before
// settings have loaded from DataStore, so that fallback never flashes a different color
// than what will load a moment later.
val DefaultAccentColor = Color(0xFF81C784)

// Functional traffic-light colors — but of the original Start/Stop/Pause/torch
// convention, only Start ended up actually wired to a distinct color in the shipped UI:
// there's no separate literal Stop button (SaveButton doubles as it) and Pause/Save both
// deliberately use the accent color instead of a semantic one (see their own doc
// comments) — so Stop/Pause/torch's colors were never removed from this file when a
// real device-testing pass simplified those controls' visuals.
val RecordStart = Color(0xFF4CAF50)
val DangerRed = Color(0xFFD32F2F)
val WarningOrange = Color(0xFFFFA726)

/**
 * Functional colors that aren't part of Material3's [androidx.compose.material3.ColorScheme]
 * role set. Access via `RoadLogTheme.colors` inside a composable.
 */
@Immutable
data class RoadLogColors(
    val recordStart: Color,
    val dangerRed: Color,
    val warningOrange: Color,
    val cardBorder: Color,
)

val LocalRoadLogColors = staticCompositionLocalOf {
    RoadLogColors(
        recordStart = RecordStart,
        dangerRed = DangerRed,
        warningOrange = WarningOrange,
        cardBorder = CardBorder,
    )
}
