package dogsc.core

import kotlin.math.abs

object Timeline {

    fun durationMs(project: Project): Long =
        project.tracks.flatMap { it.clips }.maxOfOrNull { it.timelineEndMs } ?: 0L

    fun clipAt(track: Track, timeMs: Long): Clip? =
        track.clips.firstOrNull { timeMs >= it.timelineStartMs && timeMs < it.timelineEndMs }

    /** All clip edges across all tracks, sorted — the snap targets. */
    fun edges(project: Project): List<Long> =
        project.tracks.flatMap { t -> t.clips.flatMap { listOf(it.timelineStartMs, it.timelineEndMs) } }
            .distinct().sorted()

    /** Snap [timeMs] to the nearest edge within [thresholdMs], else return it unchanged. */
    fun snap(project: Project, timeMs: Long, thresholdMs: Long = 120): Long {
        val best = edges(project).minByOrNull { abs(it - timeMs) } ?: return timeMs
        return if (abs(best - timeMs) <= thresholdMs) best else timeMs
    }

    /** Snap a time to the nearest frame boundary for the project fps. */
    fun snapToFrame(timeMs: Long, fps: Int): Long {
        if (fps <= 0) return timeMs
        val frameMs = 1000.0 / fps
        // Work in doubles: at 30 fps a frame is 33.33ms, integer frame math would drift.
        return Math.round(Math.round(timeMs / frameMs) * frameMs)
    }

    /** Overlapping clips on one track are a model error; report pairs for validation/UI warnings. */
    fun overlaps(track: Track): List<Pair<Clip, Clip>> {
        val sorted = track.clips.sortedBy { it.timelineStartMs }
        val out = mutableListOf<Pair<Clip, Clip>>()
        for (i in 0 until sorted.size - 1) {
            if (sorted[i].timelineEndMs > sorted[i + 1].timelineStartMs) out += sorted[i] to sorted[i + 1]
        }
        return out
    }
}

/** Zoom math shared by the preview (per-platform) and the FFmpeg graph compiler. */
object ZoomMath {

    /**
     * Scale at [clipLocalMs] for a clip's zoom segments.
     * Inside a segment: eased ramp up over easeIn, hold, eased ramp down over easeOut.
     * Outside all segments: 1.0. Overlapping segments: the maximum wins.
     */
    fun scaleAt(segments: List<ZoomSegment>, clipLocalMs: Long): Float {
        var result = 1f
        for (seg in segments) {
            val local = clipLocalMs - seg.startMs
            if (local < 0 || local > seg.durationMs) continue
            val s = seg.scale
            val value = when {
                seg.easeInMs > 0 && local < seg.easeInMs ->
                    1f + (s - 1f) * ease(seg.easing, local.toFloat() / seg.easeInMs)
                seg.easeOutMs > 0 && local > seg.durationMs - seg.easeOutMs ->
                    1f + (s - 1f) * ease(seg.easing, (seg.durationMs - local).toFloat() / seg.easeOutMs)
                else -> s
            }
            if (value > result) result = value
        }
        return result
    }

    /** Focus point of the strongest active segment at [clipLocalMs] (for preview/compilers). */
    fun focusAt(segments: List<ZoomSegment>, clipLocalMs: Long): Pair<Float, Float> {
        val active = segments.filter { clipLocalMs in it.startMs..(it.startMs + it.durationMs) }
            .maxByOrNull { it.scale } ?: return 0.5f to 0.5f
        return active.focusX to active.focusY
    }

    fun ease(easing: Easing, x: Float): Float {
        val t = x.coerceIn(0f, 1f)
        return when (easing) {
            Easing.LINEAR -> t
            Easing.EASE_IN -> t * t
            Easing.EASE_OUT -> 1f - (1f - t) * (1f - t)
            Easing.EASE_IN_OUT -> if (t < 0.5f) 2f * t * t else 1f - Math.pow((-2.0 * t + 2.0), 3.0).toFloat() / 2f
        }
    }
}
