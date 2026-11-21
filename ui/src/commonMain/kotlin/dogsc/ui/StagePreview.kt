package dogsc.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dogsc.core.Effect

/** True when the platform renders real video frames (Android / Media3). */
expect fun platformHasVideoPreview(): Boolean

/**
 * Platform stage preview.
 *
 * Android: a real ExoPlayer view playing the active clip's source range with
 * its speed/volume, zoom applied as a view scale, presentation as padding +
 * rounded corners. [onPlayheadChange] reports clip-local timeline ms while
 * playing; [onEnded] fires when the clip's range finishes.
 *
 * Desktop (JVM): schematic rendering of the same state (mpv preview is a
 * later step); the screen's own playhead driver animates it.
 */
@Composable
expect fun StagePreview(
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
    modifier: Modifier = Modifier,
)
