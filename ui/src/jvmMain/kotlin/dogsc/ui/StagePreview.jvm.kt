package dogsc.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import dogsc.core.Effect

actual fun platformHasVideoPreview(): Boolean = false

/** Desktop schematic stage: same state, drawn instead of decoded. */
@Composable
actual fun StagePreview(
    assetUri: String?,
    sourceStartMs: Long,
    sourceDurationMs: Long,
    speed: Double,
    volume: Float,
    playheadLocalMs: Long,
    isPlaying: Boolean,
    zoomScale: Float,
    presentation: Effect.Presentation?,
    canvasWidth: Int,
    canvasHeight: Int,
    backgroundArgb: Long,
    onPlayheadChange: (Long) -> Unit,
    onEnded: () -> Unit,
    modifier: Modifier,
) {
    Box(modifier.background(Color(backgroundArgb))) {
        if (assetUri != null) {
            Canvas(Modifier.fillMaxSize()) {
                val padRatio = (presentation?.paddingPx ?: 0).toFloat() / canvasWidth.coerceAtLeast(1)
                val padX = size.width * padRatio
                val padY = size.height * padRatio
                val innerW = size.width - padX * 2
                val innerH = size.height - padY * 2
                val zw = innerW / zoomScale
                val zh = innerH / zoomScale
                drawRoundRect(
                    color = DogscPalette.ClipBlue,
                    topLeft = Offset(padX + (innerW - zw) / 2, padY + (innerH - zh) / 2),
                    size = Size(zw, zh),
                    cornerRadius = CornerRadius((presentation?.cornerRadiusPx ?: 0).toFloat()),
                )
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.65f),
                    topLeft = Offset(padX, padY),
                    size = Size(innerW, innerH),
                    cornerRadius = CornerRadius((presentation?.cornerRadiusPx ?: 0).toFloat()),
                    style = Stroke(width = 1.5f),
                )
            }
        }
    }
}
