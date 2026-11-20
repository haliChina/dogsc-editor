package dogsc.core

import kotlin.math.max

data class FfmpegCommand(
    val args: List<String>,
    val filterComplex: String,
) {
    fun toShellLine(binary: String = "ffmpeg"): String =
        (listOf(binary) + args).joinToString(" ") { if (it.any { c -> c.isWhitespace() }) "'$it'" else it }
}

/**
 * Compiles a [Project] into one ffmpeg invocation.
 *
 * MVP scope (honest, see docs): the first VIDEO track is the picture track; its clips are
 * laid out in timeline order, gaps are filled with the canvas background color.
 * Per clip: trim -> speed -> crop effect -> zoom (dynamic crop, eased piecewise-linear
 * approximation of [ZoomMath]) -> presentation (scale into padded canvas, rounded corners
 * via an alpha mask, canvas background) -> concat -> optional audio from the same clips.
 * 3D tilt and drop shadows are preview-only in this version and are reported in
 * [CompileResult.notes] instead of being silently dropped.
 */
data class CompileResult(
    val command: FfmpegCommand,
    val notes: List<String>,
)

object FfmpegCompiler {

    fun compile(
        project: Project,
        assetPaths: Map<String, String>,
        outputPath: String,
        withAudio: Boolean = true,
    ): CompileResult {
        val notes = mutableListOf<String>()
        val canvas = project.canvas
        val track = project.tracks.firstOrNull { it.kind == TrackKind.VIDEO }
            ?: throw OpException("project has no VIDEO track")
        val clips = track.clips.sortedBy { it.timelineStartMs }
        if (clips.isEmpty()) throw OpException("video track is empty")

        val args = mutableListOf<String>("-y")
        // One input per distinct asset, in first-use order.
        val inputIndex = LinkedHashMap<String, Int>()
        for (clip in clips) {
            if (clip.assetId !in inputIndex) {
                val path = assetPaths[clip.assetId] ?: throw OpException("no path for asset ${clip.assetId}")
                inputIndex[clip.assetId] = inputIndex.size
                args += listOf("-i", path)
            }
        }
        val bgIndex = inputIndex.size
        val totalMs = Timeline.durationMs(project)
        val bgColor = when (val bg = canvas.background) {
            is BackgroundSpec.SolidColor -> "0x%06X".format(bg.argb and 0xFFFFFF)
            else -> {
                notes += "background ${bg::class.simpleName} exported as black; only SolidColor is compiled in v1"
                "black"
            }
        }
        args += listOf("-f", "lavfi", "-i", "color=c=$bgColor:s=${canvas.width}x${canvas.height}:r=${canvas.fps}:d=${totalMs / 1000.0}")

        val filters = mutableListOf<String>()
        val segmentLabels = mutableListOf<String>()
        val audioLabels = mutableListOf<String>()

        var cursorMs = 0L
        clips.forEachIndexed { i, clip ->
            // Gap before this clip: emit a slice of the background input.
            if (clip.timelineStartMs > cursorMs) {
                val gapLabel = "gap$i"
                val gapSec = (clip.timelineStartMs - cursorMs) / 1000.0
                filters += "[$bgIndex:v]trim=start=${cursorMs / 1000.0}:duration=$gapSec,setpts=PTS-STARTPTS[$gapLabel]"
                segmentLabels += gapLabel
            }
            val src = inputIndex.getValue(clip.assetId)
            val vLabel = "v$i"
            val chain = StringBuilder("[$src:v]")
            chain.append("trim=start=${clip.sourceStartMs / 1000.0}:duration=${clip.sourceDurationMs / 1000.0},setpts=PTS-STARTPTS")
            if (clip.speed != 1.0) chain.append(",setpts=PTS/${clip.speed}")
            // Crop effect
            clip.effects.filterIsInstance<Effect.Crop>().firstOrNull()?.let { crop ->
                chain.append(",crop=w='iw*(1-${crop.left + crop.right})':h='ih*(1-${crop.top + crop.bottom})':x='iw*${crop.left}':y='ih*${crop.top}'")
            }
            // Zoom: dynamic crop driven by a piecewise expression over clip-local time t.
            val zooms = clip.zoomSegments()
            if (zooms.isNotEmpty()) {
                chain.append(zoomCropFilter(zooms))
            }
            // Presentation: fit into padded canvas area, round the corners with an alpha mask.
            val pres = clip.presentation()
            val pad = pres?.paddingPx ?: 0
            val innerW = canvas.width - 2 * pad
            val innerH = canvas.height - 2 * pad
            chain.append(",scale=$innerW:$innerH:force_original_aspect_ratio=decrease")
            chain.append(",pad=$innerW:$innerH:(ow-iw)/2:(oh-ih)/2:color=$bgColor")
            if (pres != null && pres.cornerRadiusPx > 0) {
                val r = pres.cornerRadiusPx
                chain.append(",format=rgba")
                chain.append(
                    ",geq=lum='lum(X,Y)':cb='cb(X,Y)':cr='cr(X,Y)':a=" +
                        "'if(lte(X,$r)*lte(Y,$r),if(lte(pow(X-$r,2)+pow(Y-$r,2),pow($r,2)),255,0)," +
                        "if(lte(X,W-$r)*lte(Y,$r),if(lte(pow(X-(W-$r),2)+pow(Y-$r,2),pow($r,2)),255,0)," +
                        "if(lte(X,$r)*gte(Y,H-$r),if(lte(pow(X-$r,2)+pow(Y-(H-$r),2),pow($r,2)),255,0)," +
                        "if(lte(X,W-$r)*gte(Y,H-$r),if(lte(pow(X-(W-$r),2)+pow(Y-(H-$r),2),pow($r,2)),255,0),255))))'"
                )
            }
            if (pres?.tilt != null) notes += "clip ${clip.id}: 3D tilt is preview-only in v1 exports"
            if (pres?.shadow != null) notes += "clip ${clip.id}: drop shadow is preview-only in v1 exports"
            // Place on the canvas background-sized frame.
            chain.append(",pad=${canvas.width}:${canvas.height}:$pad:$pad:color=$bgColor")
            chain.append(",fps=${canvas.fps},format=yuv420p")
            chain.append("[$vLabel]")
            filters += chain.toString()
            segmentLabels += vLabel

            if (withAudio && !track.muted && clip.volume > 0f) {
                val aLabel = "a$i"
                val delay = clip.timelineStartMs
                filters += "[$src:a]atrim=start=${clip.sourceStartMs / 1000.0}:duration=${clip.sourceDurationMs / 1000.0}," +
                    "asetpts=PTS-STARTPTS,atempo=${clip.speed},volume=${clip.volume},adelay=$delay|$delay[$aLabel]"
                audioLabels += aLabel
            }
            cursorMs = max(cursorMs, clip.timelineEndMs)
        }
        if (cursorMs < totalMs) {
            filters += "[$bgIndex:v]trim=start=${cursorMs / 1000.0}:duration=${(totalMs - cursorMs) / 1000.0},setpts=PTS-STARTPTS[gapEnd]"
            segmentLabels += "gapEnd"
        }

        val concatInputs = segmentLabels.joinToString("") { "[$it]" }
        filters += "${concatInputs}concat=n=${segmentLabels.size}:v=1:a=0[outv]"
        var audioMap = "[outv]"
        if (audioLabels.isNotEmpty()) {
            val mixInputs = audioLabels.joinToString("") { "[$it]" }
            filters += "${mixInputs}amix=inputs=${audioLabels.size}:normalize=0:duration=longest," +
                "apad,atrim=0:${totalMs / 1000.0}[outa]"
            audioMap = "[outa]"
        }

        val filterComplex = filters.joinToString(";")
        args += listOf("-filter_complex", filterComplex, "-map", "[outv]")
        if (audioLabels.isNotEmpty()) args += listOf("-map", audioMap)
        args += listOf("-c:v", "libx264", "-preset", "medium", "-crf", "18")
        if (audioLabels.isNotEmpty()) args += listOf("-c:a", "aac", "-shortest")
        args += outputPath
        return CompileResult(FfmpegCommand(args, filterComplex), notes)
    }

    /**
     * Dynamic crop implementing zoom segments. The crop window is iw/scale wide; scale is a
     * piecewise expression over clip-local time t (seconds) following the same ramp shape as
     * [ZoomMath] but linear (ffmpeg expressions have no cubic easing; preview stays exact).
     */
    private fun zoomCropFilter(segments: List<ZoomSegment>): String {
        val sorted = segments.sortedBy { it.startMs }
        // Build scale expression: nested if(between(...)) from last segment to first, default 1.
        var expr = "1"
        for (seg in sorted.asReversed()) {
            val start = seg.startMs / 1000.0
            val end = (seg.startMs + seg.durationMs) / 1000.0
            val easeInEnd = (seg.startMs + seg.easeInMs) / 1000.0
            val easeOutStart = (seg.startMs + seg.durationMs - seg.easeOutMs) / 1000.0
            val s = seg.scale
            val segExpr = when {
                seg.easeInMs > 0 && seg.easeOutMs > 0 ->
                    "if(lt(t,$easeInEnd),1+($s-1)*(t-$start)/${seg.easeInMs / 1000.0}," +
                        "if(gt(t,$easeOutStart),1+($s-1)*($end-t)/${seg.easeOutMs / 1000.0},$s))"
                seg.easeInMs > 0 -> "if(lt(t,$easeInEnd),1+($s-1)*(t-$start)/${seg.easeInMs / 1000.0},$s)"
                seg.easeOutMs > 0 -> "if(gt(t,$easeOutStart),1+($s-1)*($end-t)/${seg.easeOutMs / 1000.0},$s)"
                else -> "$s"
            }
            expr = "if(between(t,$start,$end),$segExpr,$expr)"
        }
        val focus = sorted.maxByOrNull { it.scale }!!
        val fx = focus.focusX
        val fy = focus.focusY
        return ",crop=w='iw/($expr)':h='ih/($expr)':x='(iw-iw/($expr))*$fx':y='(ih-ih/($expr))*$fy'"
    }
}
