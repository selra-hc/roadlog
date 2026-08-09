package app.roadlog.dashcam.ui.theme

import android.app.Activity
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// RoadLog is dark-first by design (§8.2) — a dashcam sitting on a dashboard has no use
// for a light theme, so there's no light color scheme and no system/dynamic-color
// switching. The only user-facing choice is the AMOLED variant
// (pure black backgrounds, for OLED screens) — see AppSettings.Theme. The accent itself
// is user-configurable (AppSettings.accentColorArgb via the settings color picker), so
// the on-accent color is computed for contrast rather than hardcoded to a fixed value.
private fun roadLogColorScheme(amoled: Boolean, accentColor: Color): ColorScheme {
    val onAccentColor = if (accentColor.luminance() > 0.5f) Color.Black else Color.White

    return darkColorScheme(
        primary = accentColor,
        onPrimary = onAccentColor,
        secondary = accentColor,
        onSecondary = onAccentColor,
        background = if (amoled) BackgroundAmoled else BackgroundDark,
        onBackground = OnSurfaceLight,
        surface = if (amoled) SurfaceCardAmoled else SurfaceCard,
        onSurface = OnSurfaceLight,
        surfaceVariant = if (amoled) SurfaceVariantAmoled else SurfaceVariantDark,
        onSurfaceVariant = OnSurfaceMuted,
        error = DangerRed,
        onError = Color.White,
        outline = CardBorder,
    )
}

@Composable
fun RoadLogTheme(
    amoled: Boolean = false,
    accentColor: Color = DefaultAccentColor,
    content: @Composable () -> Unit,
) {
    val colorScheme = roadLogColorScheme(amoled, accentColor)

    // `view.context` is only an `Activity` when this theme wraps Activity-hosted content
    // (the normal case, e.g. `MainActivity`). The PIP overlay (§9.5) also wraps its
    // content in this theme now (to pick up the user's accent color), but its
    // `ComposeView` is attached directly via `WindowManager` from the recording
    // service's context — there's no `Activity`, and no window/status-bar of this
    // theme's own to style either. A plain `as Activity` cast here crashed the app the
    // instant the overlay was composed (i.e. on backgrounding, before the PIP ever
    // became visible) — switched to `as?` so non-Activity hosts just skip the
    // window-styling side effect and get the color scheme/typography/shapes only.
    val view = LocalView.current
    val activity = view.context as? Activity
    if (activity != null && !view.isInEditMode) {
        SideEffect {
            val window = activity.window

            window.navigationBarColor = colorScheme.background.toArgb()
            // Always dark: status/nav bar icons are always light-on-dark, never
            // toggled based on system theme.
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = false
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = RoadLogShapes,
        content = content,
    )
}

/**
 * Design tokens that aren't part of [MaterialTheme] — traffic-light recording controls,
 * card border stroke, etc. Mirrors `MaterialTheme.colorScheme`'s access pattern:
 * `RoadLogTheme.colors.recordStart`.
 */
object RoadLogTheme {
    val colors: RoadLogColors
        @Composable
        @ReadOnlyComposable
        get() = LocalRoadLogColors.current
}
