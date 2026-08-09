package app.roadlog.dashcam.ui.components.SettingsScreen.atoms

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.roadlog.dashcam.db.WatermarkCorner
import app.roadlog.dashcam.db.WatermarkSettings
import app.roadlog.dashcam.ui.theme.CardCornerRadius
import app.roadlog.dashcam.ui.theme.RoadLogTheme
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// §9.4's live preview. There is no camera hardware in this environment (and, more
// generally, this is a settings screen, not a recording surface) so the "camera feed"
// behind the watermark is a static placeholder gradient — not an attempt to reuse
// `RecordingPreview`/`CameraPreview`, which are built for very different, specific
// contexts (an actively bound CameraX session). The overlay itself is a simplified,
// Compose-native approximation of `watermark/WatermarkRenderer.kt`'s GL output (real
// backing-plate/text look, not the same renderer) — see §5.3 for the "translucent dark
// backing plate" design this mirrors.
@Composable
fun WatermarkPreview(
    watermark: WatermarkSettings,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(CardCornerRadius))
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF3A3A40), Color(0xFF1A1A1D)),
                )
            )
            .border(1.dp, RoadLogTheme.colors.cardBorder, RoundedCornerShape(CardCornerRadius))
    ) {
        if (watermark.enabled) {
            _WatermarkOverlay(
                watermark = watermark,
                modifier = Modifier
                    .align(watermark.corner.toBoxAlignment())
                    .padding(10.dp),
            )
        }
    }
}

private fun WatermarkCorner.toBoxAlignment(): Alignment = when (this) {
    WatermarkCorner.TOP_LEFT -> Alignment.TopStart
    WatermarkCorner.TOP_RIGHT -> Alignment.TopEnd
    WatermarkCorner.BOTTOM_LEFT -> Alignment.BottomStart
    WatermarkCorner.BOTTOM_RIGHT -> Alignment.BottomEnd
}

// Representative, not pixel-identical: a fake timestamp/speed pair, same two-line shape
// `WatermarkTextProvider.currentText` produces (§5.2), behind a ~55%-opacity black
// rounded-rect backing plate (§5.3).
@Composable
private fun _WatermarkOverlay(
    watermark: WatermarkSettings,
    modifier: Modifier = Modifier,
) {
    val timestamp = remember(watermark) {
        FORMATTER.format(LocalDateTime.now())
    }
    val speedText = "58 ${watermark.speedUnit.label}"

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = timestamp,
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
        )
        Text(
            text = speedText,
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

private val FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
