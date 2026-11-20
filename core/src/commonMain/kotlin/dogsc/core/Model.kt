package dogsc.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Platform-neutral project model.
 *
 * Design rules (see docs/ARCHITECTURE.md):
 * - All times are milliseconds on the timeline. Frame snapping is a helper, not a storage rule.
 * - Effects are declarative parameters only. No platform object (no Android Bitmap,
 *   no Compose state, no FFmpeg string) ever enters the model, so the same JSON can be
 *   rendered by the Android backend, the Windows backend, or the FFmpeg graph compiler.
 * - Assets are referenced by URI/path, never copied into the project.
 */

@Serializable
data class CanvasSpec(
    val width: Int = 1920,
    val height: Int = 1080,
    val fps: Int = 30,
    val background: BackgroundSpec = BackgroundSpec.SolidColor(0xFFF4F1EA),
)

@Serializable
sealed interface BackgroundSpec {
    @Serializable
    @SerialName("color")
    data class SolidColor(val argb: Long) : BackgroundSpec

    @Serializable
    @SerialName("image")
    data class Image(val assetId: String) : BackgroundSpec

    @Serializable
    @SerialName("blur")
    data class BlurFromSource(val radius: Float = 40f) : BackgroundSpec
}

@Serializable
enum class TrackKind { VIDEO, AUDIO, TEXT }

@Serializable
enum class AssetKind { VIDEO, AUDIO, IMAGE }

@Serializable
data class AssetRef(
    val id: String,
    /** Content URI on Android, file path on desktop. Always a reference, never an embedded copy. */
    val uri: String,
    val kind: AssetKind,
    val durationMs: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
)

@Serializable
data class ShadowSpec(
    val blurPx: Float = 32f,
    val offsetXPx: Float = 0f,
    val offsetYPx: Float = 12f,
    val argb: Long = 0x66000000,
)

@Serializable
data class TiltSpec(
    /** Degrees around the X axis (perspective tilt back/forward). */
    val rotateXDeg: Float = 0f,
    /** Degrees around the Y axis. */
    val rotateYDeg: Float = 0f,
    /** Perspective strength 0..1 as used by the preview; exporters map it to their own model. */
    val perspective: Float = 0.6f,
)

@Serializable
enum class Easing { LINEAR, EASE_IN_OUT, EASE_OUT, EASE_IN }

@Serializable
data class ZoomSegment(
    /** Start relative to the clip's timeline start. */
    val startMs: Long,
    val durationMs: Long,
    /** 1.0 = no zoom. Typical range 1.0..4.0. */
    val scale: Float,
    /** Focus point in source-frame fractions, 0..1. */
    val focusX: Float = 0.5f,
    val focusY: Float = 0.5f,
    val easeInMs: Long = 350,
    val easeOutMs: Long = 350,
    val easing: Easing = Easing.EASE_IN_OUT,
)

@Serializable
sealed interface Effect {
    @Serializable
    @SerialName("zoom")
    data class Zoom(val segments: List<ZoomSegment> = emptyList()) : Effect

    @Serializable
    @SerialName("presentation")
    data class Presentation(
        /** Padding between the source frame and the canvas edge, in canvas pixels. */
        val paddingPx: Int = 96,
        val cornerRadiusPx: Int = 24,
        val shadow: ShadowSpec? = ShadowSpec(),
        val tilt: TiltSpec? = null,
    ) : Effect

    @Serializable
    @SerialName("crop")
    data class Crop(
        /** Fractions of the source frame to cut away, each 0..0.9. */
        val left: Float = 0f,
        val top: Float = 0f,
        val right: Float = 0f,
        val bottom: Float = 0f,
    ) : Effect

    @Serializable
    @SerialName("opacity")
    data class Opacity(val value: Float = 1f) : Effect
}

@Serializable
data class Clip(
    val id: String,
    val assetId: String,
    val timelineStartMs: Long,
    val sourceStartMs: Long = 0,
    val durationMs: Long,
    /** Playback speed; timeline duration = source duration / speed is NOT stored:
     *  [durationMs] is always the timeline (output) duration. */
    val speed: Double = 1.0,
    val volume: Float = 1f,
    val effects: List<Effect> = emptyList(),
) {
    val timelineEndMs: Long get() = timelineStartMs + durationMs
    /** How much source material this clip consumes. */
    val sourceDurationMs: Long get() = (durationMs * speed).toLong()
    val sourceEndMs: Long get() = sourceStartMs + sourceDurationMs

    fun zoomSegments(): List<ZoomSegment> =
        effects.filterIsInstance<Effect.Zoom>().flatMap { it.segments }

    fun presentation(): Effect.Presentation? =
        effects.filterIsInstance<Effect.Presentation>().firstOrNull()
}

@Serializable
data class Track(
    val id: String,
    val kind: TrackKind,
    val clips: List<Clip> = emptyList(),
    val muted: Boolean = false,
)

@Serializable
data class Project(
    val id: String,
    val name: String,
    val canvas: CanvasSpec = CanvasSpec(),
    val assets: List<AssetRef> = emptyList(),
    val tracks: List<Track> = emptyList(),
    /** Monotonic revision, bumped by every applied op. Agent receipts report this. */
    val revision: Long = 0,
) {
    fun track(id: String): Track? = tracks.firstOrNull { it.id == id }
    fun clip(trackId: String, clipId: String): Clip? =
        track(trackId)?.clips?.firstOrNull { it.id == clipId }
    fun asset(id: String): AssetRef? = assets.firstOrNull { it.id == id }
}
