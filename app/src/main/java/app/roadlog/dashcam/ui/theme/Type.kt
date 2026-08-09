@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package app.roadlog.dashcam.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import app.roadlog.dashcam.R

// Inter (SIL OFL 1.1), bundled locally as a single variable font — see
// THIRD_PARTY_LICENSES/Inter-OFL.txt. Deliberately not fetched via Google's Downloadable
// Fonts provider, which depends on Play Services (§13).
//
// Variable-font weight axis requires API 26+; on API 24/25 devices the system falls back
// to the font's default instance (regular weight) — a cosmetic-only degradation, not a
// crash, and an acceptable tradeoff for minSdk 24 (§16).
val InterFontFamily = FontFamily(
    Font(
        R.font.inter_variable,
        weight = FontWeight.Normal,
        variationSettings = FontVariation.Settings(FontVariation.weight(400)),
    ),
    Font(
        R.font.inter_variable,
        weight = FontWeight.Medium,
        variationSettings = FontVariation.Settings(FontVariation.weight(500)),
    ),
    Font(
        R.font.inter_variable,
        weight = FontWeight.SemiBold,
        variationSettings = FontVariation.Settings(FontVariation.weight(600)),
    ),
    Font(
        R.font.inter_variable,
        weight = FontWeight.Bold,
        variationSettings = FontVariation.Settings(FontVariation.weight(700)),
    ),
)

// OpenType feature tag for tabular (fixed-width) figures, an Inter built-in feature.
// Apply via `TextStyle(fontFeatureSettings = TabularFigures)` on the elapsed-recording
// timer digits so they don't shift width as they change (§8.2) — wired up when that
// composable is built.
const val TabularFigures = "tnum"

// Material3's default Typography, with every text style's font family swapped to Inter —
// keeps Material's default sizes/weights/line-heights/letter-spacing intact.
private val defaultTypography = Typography()
val Typography = with(defaultTypography) {
    Typography(
        displayLarge = displayLarge.copy(fontFamily = InterFontFamily),
        displayMedium = displayMedium.copy(fontFamily = InterFontFamily),
        displaySmall = displaySmall.copy(fontFamily = InterFontFamily),
        headlineLarge = headlineLarge.copy(fontFamily = InterFontFamily),
        headlineMedium = headlineMedium.copy(fontFamily = InterFontFamily),
        headlineSmall = headlineSmall.copy(fontFamily = InterFontFamily),
        titleLarge = titleLarge.copy(fontFamily = InterFontFamily),
        titleMedium = titleMedium.copy(fontFamily = InterFontFamily),
        titleSmall = titleSmall.copy(fontFamily = InterFontFamily),
        bodyLarge = bodyLarge.copy(fontFamily = InterFontFamily),
        bodyMedium = bodyMedium.copy(fontFamily = InterFontFamily),
        bodySmall = bodySmall.copy(fontFamily = InterFontFamily),
        labelLarge = labelLarge.copy(fontFamily = InterFontFamily),
        labelMedium = labelMedium.copy(fontFamily = InterFontFamily),
        labelSmall = labelSmall.copy(fontFamily = InterFontFamily),
    )
}
