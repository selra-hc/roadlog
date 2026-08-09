package app.roadlog.dashcam.watermark

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Typeface
import androidx.camera.effects.Frame
import androidx.core.content.res.ResourcesCompat
import app.roadlog.dashcam.R
import app.roadlog.dashcam.db.WatermarkCorner

// Draws the watermark text block (timestamp + speed) onto an OverlayEffect `Frame`'s
// canvas — proportional text sizing, drop shadow, corner placement (§5.1 step 3).
// `OverlayEffect` already handles compositing the drawn canvas onto every output surface;
// this class only needs to draw, not manage GL/textures itself.
class WatermarkRenderer(context: Context) {
    private val typeface = loadTypeface(context)

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = this@WatermarkRenderer.typeface
        // A backing plate (drawn below) is the primary legibility mechanism against
        // bright/dark road backgrounds — this shadow is a light secondary aid, not the
        // sole mechanism (§5.3).
        setShadowLayer(4f, 0f, 1f, Color.BLACK)
    }

    private val backingPlatePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(140, 0, 0, 0)
        style = Paint.Style.FILL
    }

    // Resource-consumption optimization, not a behavior change: `text` is now stable for
    // up to a second at a time (`WatermarkTextProvider`'s own caching), but this method
    // is still called once per rendered frame — up to 30-60x/sec while recording.
    // Re-splitting an unchanged string and re-measuring identical text via `measureText`
    // (a Skia text-shaping call, not free) on every one of those frames was pure waste
    // whenever neither the text nor the text size had actually changed since the last
    // frame. Cached and invalidated on either changing — measuring the SAME text at the
    // SAME `textPaint.textSize` always produces the SAME `blockWidth`, so this is exact,
    // not approximate.
    private var cachedText: String? = null
    private var cachedTextSize: Float = -1f
    private var cachedLines: List<String> = emptyList()
    private var cachedBlockWidth: Float = 0f

    // Returns whether anything was drawn (false when the watermark is off) — the return
    // value is what `OverlayEffect.setOnDrawListener` expects (§5.1).
    fun draw(frame: Frame, text: String?, corner: WatermarkCorner): Boolean {
        val canvas = frame.overlayCanvas
        // The overlay surface persists across frames — clear it first or last frame's
        // text would show through/ghost behind this frame's.
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        if (text == null) {
            return false
        }

        // `Frame`'s canvas is in raw sensor-buffer coordinates, not final display
        // orientation — CameraX's own docs for `Frame.getRotationDegrees()` say it's
        // "a clockwise rotation ... that needs to be applied to the frame" for it to
        // display correctly. That means a point drawn in final, upright "logical" space
        // must be PRE-rotated by the opposite (counter-clockwise) amount here, so that
        // whatever later applies that clockwise rotation brings our overlay back to
        // upright along with the camera image. Confirmed on-device: using `+rotation`
        // here (as an earlier version of this code did) put the watermark 2x rotation
        // off from correct — for the common rotation=90 case, that's 180° off, which is
        // exactly the originally-reported "top-left renders at bottom-right, upside
        // down" bug. `-rotation` is the fix (and is NOT the cropping bug below — the
        // rotation math itself was re-verified while fixing that and is unchanged here).
        //
        // Separately: `frame.size` is the full input buffer CameraX handed to this
        // effect (`SurfaceRequest.getResolution()`), but that is NOT necessarily what
        // ends up in the encoded video or on screen. Decompiling `Frame.of()`
        // (androidx.camera.effects, 1.4.2) shows `Frame.getCropRect()` is populated
        // straight from `SurfaceRequest.TransformationInfo#getCropRect()` — the same
        // crop rect CameraX's own "Transform output" guide says must be combined with
        // `rotationDegrees` when mapping overlay coordinates. `VideoCapture` (see
        // `VideoCapture.calculateCropRect()`/`adjustCropRectToValidSize()`/
        // `adjustCropRectByQuirk()` in camera-video 1.4.2) can shrink that crop rect
        // below the full buffer size to satisfy encoder width/height alignment
        // requirements, or to reconcile Preview/VideoCapture aspect ratios — which is
        // exactly the gap this app hit with no `ViewPort` set (see
        // `VideoRecorderService.openCamera()`). Drawing the watermark's margin relative
        // to `frame.size` (the previous approach) can place it inside a strip that this
        // later crop step throws away before the encoder ever sees it — that's the
        // "watermark cropped out of the video" bug. Anchoring to `frame.cropRect`
        // instead means the margin is always measured from the boundary of what
        // actually survives into the encoded/displayed frame, for whichever target
        // (`PREVIEW` or `VIDEO_CAPTURE`) this particular `Frame` belongs to.
        val cropRect = frame.cropRect
        val rotation = frame.rotationDegrees
        val rotatedQuarterTurn = rotation % 180 != 0
        val logicalWidth = if (rotatedQuarterTurn) cropRect.height() else cropRect.width()
        val logicalHeight = if (rotatedQuarterTurn) cropRect.width() else cropRect.height()

        canvas.save()
        try {
            canvas.translate(cropRect.exactCenterX(), cropRect.exactCenterY())
            if (frame.isMirroring) {
                canvas.scale(-1f, 1f)
            }
            canvas.rotate(-rotation.toFloat())
            canvas.translate(-logicalWidth / 2f, -logicalHeight / 2f)

            drawUpright(canvas, logicalWidth, logicalHeight, text, corner)
        } finally {
            canvas.restore()
        }

        return true
    }

    private fun drawUpright(canvas: Canvas, width: Int, height: Int, text: String, corner: WatermarkCorner) {
        textPaint.textSize =
            (TEXT_SIZE_FRACTION * width).coerceIn(MIN_TEXT_SIZE_PX, MAX_TEXT_SIZE_PX)

        val lines: List<String>
        val blockWidth: Float
        if (text == cachedText && textPaint.textSize == cachedTextSize) {
            lines = cachedLines
            blockWidth = cachedBlockWidth
        } else {
            lines = text.split("\n")
            blockWidth = lines.maxOf { textPaint.measureText(it) }
            cachedText = text
            cachedTextSize = textPaint.textSize
            cachedLines = lines
            cachedBlockWidth = blockWidth
        }

        val lineHeight = textPaint.fontSpacing
        val margin = width * MARGIN_FRACTION
        val blockHeight = lineHeight * lines.size

        val platePaddingX = textPaint.textSize * 0.4f
        val platePaddingY = textPaint.textSize * 0.3f
        val plateWidth = blockWidth + platePaddingX * 2
        val plateHeight = blockHeight + platePaddingY * 2

        val plateLeft = when (corner) {
            WatermarkCorner.TOP_LEFT, WatermarkCorner.BOTTOM_LEFT -> margin
            WatermarkCorner.TOP_RIGHT, WatermarkCorner.BOTTOM_RIGHT -> width - margin - plateWidth
        }
        val plateTop = when (corner) {
            WatermarkCorner.TOP_LEFT, WatermarkCorner.TOP_RIGHT -> margin
            WatermarkCorner.BOTTOM_LEFT, WatermarkCorner.BOTTOM_RIGHT -> height - margin - plateHeight
        }

        val plateCornerRadius = textPaint.textSize * 0.25f
        canvas.drawRoundRect(
            plateLeft,
            plateTop,
            plateLeft + plateWidth,
            plateTop + plateHeight,
            plateCornerRadius,
            plateCornerRadius,
            backingPlatePaint,
        )

        var baselineY = plateTop + platePaddingY - textPaint.ascent()
        for (line in lines) {
            canvas.drawText(line, plateLeft + platePaddingX, baselineY, textPaint)
            baselineY += lineHeight
        }
    }

    companion object {
        // 0.020 × encoderWidth, clamped ~16–48px (§5.1 step 3) — drawn directly rather
        // than via a separate bitmap-to-texture upload step.
        private const val TEXT_SIZE_FRACTION = 0.020f
        private const val MIN_TEXT_SIZE_PX = 16f
        private const val MAX_TEXT_SIZE_PX = 48f

        // Bumped up from an original 0.02 (§5.1 step 3) as defensive insurance on
        // top of the `frame.cropRect`-based anchoring above: even though the plate is
        // now positioned relative to the boundary CameraX itself reports as "what
        // survives into the final frame," device/encoder-specific quirks
        // (`adjustCropRectByQuirk` in camera-video 1.4.2 exists precisely because some
        // OEM encoders impose crop/alignment behavior beyond what `TransformationInfo`
        // captures) could still nibble a pixel or two off that boundary. A larger
        // margin costs a little more empty space around the plate but makes the
        // watermark robust to that residual, unverifiable-without-a-device risk.
        private const val MARGIN_FRACTION = 0.05f

        // Plain `android.graphics.Typeface` (unlike Compose's `FontVariation.Settings`
        // API, §8.2/Type.kt) has no supported way to re-derive a specific weight
        // instance from an already-loaded variable-font `Typeface` — `Typeface.Builder`
        // only builds from a file/asset/fd source. Rather than re-reading the font file
        // from disk on every renderer construction to work around that, this just uses
        // the font's default instance (regular weight) — a cosmetic simplification, not
        // a functional gap; a bolder watermark weight can be revisited later if desired.
        private fun loadTypeface(context: Context): Typeface =
            ResourcesCompat.getFont(context, R.font.inter_variable) ?: Typeface.DEFAULT
    }
}
