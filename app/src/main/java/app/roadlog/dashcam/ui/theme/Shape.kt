package app.roadlog.dashcam.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

// Secondary/icon buttons.
val ButtonCornerRadius = 15.dp

// Dashboard cards.
val CardCornerRadius = 10.dp

// The primary Start/Stop button is fully pill-shaped.
val PillShape = CircleShape

val RoadLogShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(CardCornerRadius),
    medium = RoundedCornerShape(CardCornerRadius),
    large = RoundedCornerShape(ButtonCornerRadius),
    extraLarge = RoundedCornerShape(28.dp),
)
