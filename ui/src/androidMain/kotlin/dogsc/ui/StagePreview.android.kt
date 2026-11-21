package dogsc.ui

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import dogsc.core.Effect
import kotlinx.coroutines.delay

actual fun platformHasVideoPreview(): Boolean = true

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
    val context = LocalContext.current
    val player = remember { ExoPlayer.Builder(context).build() }
    DisposableEffect(player) { onDispose { player.release() } }

    val playheadCb = rememberUpdatedState(onPlayheadChange)
    val endedCb = rememberUpdatedState(onEnded)

    // (Re)load the active clip's source range.
    LaunchedEffect(assetUri, sourceStartMs, sourceDurationMs) {
        if (assetUri == null) {
            player.clearMediaItems()
            return@LaunchedEffect
        }
        val item = MediaItem.Builder()
            .setUri(assetUri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(sourceStartMs)
                    .setEndPositionMs(sourceStartMs + sourceDurationMs)
                    .build(),
            )
            .build()
        player.setMediaItem(item)
        player.prepare()
        player.seekTo((playheadLocalMs * speed).toLong().coerceAtLeast(0))
    }

    LaunchedEffect(speed) { player.playbackParameters = PlaybackParameters(speed.toFloat()) }
    LaunchedEffect(volume) { player.volume = volume }
    LaunchedEffect(isPlaying) { player.playWhenReady = isPlaying }

    // External seeks (slider / timeline tap): follow when paused, or when the
    // drift while playing is large (e.g. user dragged mid-playback).
    LaunchedEffect(playheadLocalMs, isPlaying) {
        if (assetUri == null) return@LaunchedEffect
        val target = (playheadLocalMs * speed).toLong()
        val drift = target - player.currentPosition
        if (!isPlaying || drift > 600 || drift < -600) {
            player.seekTo(target.coerceAtLeast(0))
        }
    }

    // While playing, report the position back as clip-local timeline ms.
    LaunchedEffect(isPlaying, assetUri) {
        if (!isPlaying || assetUri == null) return@LaunchedEffect
        while (true) {
            if (player.playbackState == Player.STATE_ENDED) {
                endedCb.value()
                break
            }
            playheadCb.value((player.currentPosition / speed).toLong())
            delay(100)
        }
    }

    Box(modifier.background(Color(backgroundArgb))) {
        if (assetUri != null) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val padFraction = (presentation?.paddingPx ?: 0).toFloat() / canvasWidth.coerceAtLeast(1)
                val padX = maxWidth * padFraction
                val padY = maxHeight * padFraction
                val radius = (presentation?.cornerRadiusPx ?: 0).toFloat() / canvasWidth.coerceAtLeast(1)
                Box(
                    Modifier.fillMaxSize()
                        .padding(horizontal = padX, vertical = padY)
                        .graphicsLayer {
                            scaleX = zoomScale
                            scaleY = zoomScale
                            clip = radius > 0f
                            shape = RoundedCornerShape(maxWidth * radius)
                        },
                ) {
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                layoutParams = FrameLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                )
                                useController = false
                                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                                this.player = player
                            }
                        },
                        update = { it.player = player },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}
