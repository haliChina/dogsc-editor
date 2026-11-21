package dogsc.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dogsc.core.AgentAction
import dogsc.core.BackgroundSpec
import dogsc.core.EditorEngine
import dogsc.core.Effect
import dogsc.core.LocalAgentPlanner
import dogsc.core.Op
import dogsc.core.Project
import dogsc.core.Timeline
import dogsc.core.TrackKind
import dogsc.core.ZoomMath
import dogsc.core.ZoomSegment

/** Editor palette: dark like a real NLE, industrial-orange accent. */
object DogscPalette {
    val Bg = Color(0xFF0F1115)
    val Surface = Color(0xFF171A20)
    val Surface2 = Color(0xFF1E222B)
    val Line = Color(0xFF2A2F3A)
    val TextMain = Color(0xFFE8EAF0)
    val Muted = Color(0xFF9AA1B1)
    val Accent = Color(0xFFFF4D00)
    val ClipBlue = Color(0xFF2E6DA4)
    val ZoomMark = Color(0xFFFFD54F)
}

@Composable
fun DogscTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = DogscPalette.Accent,
            background = DogscPalette.Bg,
            surface = DogscPalette.Surface,
            onBackground = DogscPalette.TextMain,
            onSurface = DogscPalette.TextMain,
        ),
        content = content,
    )
}

fun formatTime(ms: Long): String {
    val totalSec = ms / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    val tenth = (ms % 1000) / 100
    val sStr = if (s < 10) "0$s" else s.toString()
    return "$m:$sStr.$tenth"
}

fun formatScale(scale: Float): String {
    val centi = (scale * 100).toInt()
    val frac = (centi % 100).toString().padStart(2, '0')
    return "x${centi / 100}.$frac"
}

/**
 * Shared editor UI (Android + Windows run this same composable).
 *
 * State rule that v0.1 got wrong and this version fixes: the [EditorEngine] is
 * owned by the platform shell, never created inside the screen. Imports in the
 * shell apply ops to the same engine the screen observes via its listener, so
 * an import shows up immediately. Every mutation - buttons, agent, import -
 * goes through EditorEngine.apply.
 *
 * Preview is still the schematic stage (live zoom / presentation at the
 * playhead); real video preview plugs in per platform behind this state.
 */
@Composable
fun EditorScreen(
    engine: EditorEngine,
    onImport: () -> Unit,
    onExport: (Project) -> Unit,
    modifier: Modifier = Modifier,
) {
    var project by remember { mutableStateOf(engine.project) }
    DisposableEffect(engine) {
        val listener: () -> Unit = { project = engine.project }
        engine.addListener(listener)
        onDispose { engine.removeListener(listener) }
    }

    var playheadMs by remember { mutableLongStateOf(0L) }
    var selectedClipId by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("") }
    var isPlaying by remember { mutableStateOf(false) }
    val agentLog = remember { mutableStateListOf("Agent 在岗。试试：在 5 秒分割 / 加缩放 1.8 倍 / 演示画布 / 变速 2 倍 / 音量 50% / 画布竖屏 / 撤销") }
    var agentInput by remember { mutableStateOf("") }

    val videoTrack = project.tracks.firstOrNull { it.kind == TrackKind.VIDEO }
    val duration = Timeline.durationMs(project).coerceAtLeast(1)
    val activeClip = videoTrack?.let { Timeline.clipAt(it, playheadMs) }
        ?: videoTrack?.clips?.firstOrNull { it.id == selectedClipId }
    val zoomScale = activeClip?.let {
        ZoomMath.scaleAt(it.zoomSegments(), (playheadMs - it.timelineStartMs).coerceAtLeast(0))
    } ?: 1f

    // Schematic playback: advances the playhead so zoom/presentation animate.
    LaunchedEffect(isPlaying) {
        if (!isPlaying) return@LaunchedEffect
        var last = -1L
        while (true) {
            withFrameNanos { now ->
                if (last < 0) last = now
                val dtMs = (now - last) / 1_000_000
                last = now
                val total = Timeline.durationMs(engine.project).coerceAtLeast(1)
                playheadMs = (playheadMs + dtMs).coerceAtMost(total)
                if (playheadMs >= total) isPlaying = false
            }
        }
    }

    fun apply(op: Op) {
        try {
            engine.apply(op)
            status = ""
        } catch (e: Exception) {
            status = e.message ?: "操作失败"
        }
    }

    fun runAgent(text: String) {
        val cmd = text.trim()
        if (cmd.isEmpty()) return
        try {
            when (val action = LocalAgentPlanner.plan(engine.project, cmd, playheadMs)) {
                is AgentAction.Apply -> {
                    action.ops.forEach { engine.apply(it) }
                    agentLog += "已执行：${action.summary}（rev ${engine.project.revision}）"
                }
                AgentAction.Undo -> { engine.undo(); agentLog += "已撤销（rev ${engine.project.revision}）" }
                AgentAction.Redo -> { engine.redo(); agentLog += "已重做（rev ${engine.project.revision}）" }
                is AgentAction.Info -> agentLog += action.text
                is AgentAction.Unknown -> agentLog += "没办成：${action.reason}"
            }
            status = ""
        } catch (e: Exception) {
            agentLog += "执行出错：${e.message}"
        }
        if (agentLog.size > 30) agentLog.removeRange(0, agentLog.size - 30)
    }

    Column(
        modifier = modifier.fillMaxSize().background(DogscPalette.Bg)
            .verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // ---- top bar ----
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column {
                Text(project.name, style = MaterialTheme.typography.titleMedium, color = DogscPalette.TextMain)
                Text(
                    "rev ${project.revision} · ${project.assets.size} 素材 · ${videoTrack?.clips?.size ?: 0} 片段",
                    style = MaterialTheme.typography.bodySmall, color = DogscPalette.Muted,
                )
            }
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onImport) { Text("导入视频") }
            Button(
                onClick = { onExport(engine.project) },
                colors = ButtonDefaults.buttonColors(containerColor = DogscPalette.Accent),
            ) { Text("导出") }
        }

        // ---- stage ----
        val canvasRatio = (project.canvas.width.toFloat() / project.canvas.height.toFloat()).coerceIn(0.4f, 2.6f)
        Box(
            Modifier.fillMaxWidth().aspectRatio(canvasRatio)
                .background(Color.Black, RoundedCornerShape(8.dp))
                .border(1.dp, DogscPalette.Line, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            val pres = activeClip?.presentation()
            val bgColor = (project.canvas.background as? BackgroundSpec.SolidColor)?.argb ?: 0xFF101014
            Canvas(Modifier.fillMaxSize().padding(6.dp)) {
                drawRect(Color(bgColor))
                if (activeClip != null) {
                    val padRatio = (pres?.paddingPx ?: 0).toFloat() / project.canvas.width
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
                        cornerRadius = CornerRadius((pres?.cornerRadiusPx ?: 0).toFloat()),
                    )
                    drawRoundRect(
                        color = Color.White.copy(alpha = 0.65f),
                        topLeft = Offset(padX, padY),
                        size = Size(innerW, innerH),
                        cornerRadius = CornerRadius((pres?.cornerRadiusPx ?: 0).toFloat()),
                        style = Stroke(width = 1.5f),
                    )
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    activeClip?.let { "片段 ${it.id}" } ?: "还没有片段",
                    color = Color.White, style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    activeClip?.let { "缩放 ${formatScale(zoomScale)} · 变速 ${it.speed}x · 音量 ${(it.volume * 100).toInt()}%" }
                        ?: "点「导入视频」选一段视频开始",
                    color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        // ---- transport ----
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { if (playheadMs >= duration) playheadMs = 0; isPlaying = !isPlaying },
                colors = ButtonDefaults.buttonColors(containerColor = DogscPalette.Surface2),
            ) { Text(if (isPlaying) "暂停" else "播放", color = DogscPalette.TextMain) }
            Text(
                "${formatTime(playheadMs)} / ${formatTime(duration)}",
                color = DogscPalette.TextMain, style = MaterialTheme.typography.bodyMedium,
            )
            Slider(
                value = playheadMs.toFloat(),
                onValueChange = { playheadMs = Timeline.snap(project, it.toLong()) },
                valueRange = 0f..duration.toFloat(),
                colors = SliderDefaults.colors(thumbColor = DogscPalette.Accent, activeTrackColor = DogscPalette.Accent),
                modifier = Modifier.weight(1f),
            )
        }

        // ---- timeline ----
        TimelineView(
            project = project,
            playheadMs = playheadMs,
            selectedClipId = activeClip?.id,
            onSeek = { playheadMs = it },
            onSelect = { selectedClipId = it },
            modifier = Modifier.fillMaxWidth().height(96.dp),
        )

        // ---- op toolbar ----
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                enabled = activeClip != null && videoTrack != null &&
                    playheadMs > (activeClip?.timelineStartMs ?: 0) && playheadMs < (activeClip?.timelineEndMs ?: 0),
                onClick = {
                    val clip = activeClip!!
                    apply(Op.SplitClip(videoTrack!!.id, clip.id, playheadMs, "c${project.revision + 1}-${clip.id}"))
                },
            ) { Text("在播放头分割") }
            OutlinedButton(
                enabled = activeClip != null && videoTrack != null,
                onClick = {
                    val clip = activeClip!!
                    apply(
                        Op.AddZoomSegment(
                            videoTrack!!.id, clip.id,
                            ZoomSegment(
                                startMs = (playheadMs - clip.timelineStartMs).coerceAtLeast(0),
                                durationMs = 2_000, scale = 1.8f,
                            ),
                        )
                    )
                },
            ) { Text("加缩放") }
            OutlinedButton(
                enabled = activeClip != null && videoTrack != null,
                onClick = {
                    val clip = activeClip!!
                    apply(Op.SetPresentation(videoTrack!!.id, clip.id, Effect.Presentation()))
                },
            ) { Text("演示画布") }
            OutlinedButton(enabled = engine.canUndo, onClick = { engine.undo() }) { Text("撤销") }
            OutlinedButton(enabled = engine.canRedo, onClick = { engine.redo() }) { Text("重做") }
        }
        if (status.isNotEmpty()) Text(status, color = MaterialTheme.colorScheme.error)

        // ---- agent panel ----
        Column(
            Modifier.fillMaxWidth().background(DogscPalette.Surface, RoundedCornerShape(10.dp))
                .border(1.dp, DogscPalette.Line, RoundedCornerShape(10.dp)).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Agent", style = MaterialTheme.typography.titleSmall, color = DogscPalette.TextMain)
                Spacer(Modifier.weight(1f))
                Text("本地指令 · 与按钮同一套 Op", style = MaterialTheme.typography.bodySmall, color = DogscPalette.Muted)
            }
            agentLog.takeLast(5).forEach { line ->
                Text(line, style = MaterialTheme.typography.bodySmall, color = DogscPalette.Muted)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = agentInput,
                    onValueChange = { agentInput = it },
                    placeholder = { Text("例：在 5 秒分割") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = { runAgent(agentInput); agentInput = "" },
                    colors = ButtonDefaults.buttonColors(containerColor = DogscPalette.Accent),
                ) { Text("执行") }
            }
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf("在播放头分割", "加缩放 1.8 倍", "演示画布", "变速 2 倍", "工程信息").forEach { chip ->
                    OutlinedButton(onClick = {
                        runAgent(if (chip == "在播放头分割") "在 ${playheadMs / 1000.0} 秒分割" else chip)
                    }) { Text(chip) }
                }
            }
        }

        Text(
            "片段: " + (videoTrack?.clips?.joinToString { "${it.id} [${formatTime(it.timelineStartMs)}..${formatTime(it.timelineEndMs)}]" } ?: "无"),
            style = MaterialTheme.typography.bodySmall, color = DogscPalette.Muted,
        )
    }
}

@Composable
private fun TimelineView(
    project: Project,
    playheadMs: Long,
    selectedClipId: String?,
    onSeek: (Long) -> Unit,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val duration = Timeline.durationMs(project).coerceAtLeast(1)
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = DogscPalette.Muted, fontSize = 9.sp)
    Canvas(
        modifier.background(DogscPalette.Surface, RoundedCornerShape(8.dp))
            .border(1.dp, DogscPalette.Line, RoundedCornerShape(8.dp))
            .pointerInput(duration, project.revision) {
                detectTapGestures { offset ->
                    val t = (offset.x / size.width * duration).toLong().coerceIn(0, duration)
                    onSeek(t)
                    project.tracks.firstOrNull { it.kind == TrackKind.VIDEO }
                        ?.let { tr -> Timeline.clipAt(tr, t)?.let { onSelect(it.id) } }
                }
            },
    ) {
        val track = project.tracks.firstOrNull { it.kind == TrackKind.VIDEO } ?: return@Canvas
        val pxPerMs = size.width / duration.toFloat()
        val rulerH = 16.dp.toPx()
        val clipTop = rulerH + 6.dp.toPx()
        val clipH = size.height - clipTop - 6.dp.toPx()
        // ruler: tick each second, label each 5 seconds
        var t = 0L
        while (t <= duration) {
            val x = t * pxPerMs
            drawLine(
                DogscPalette.Line, Offset(x, rulerH - 5.dp.toPx()), Offset(x, rulerH),
                strokeWidth = 1f,
            )
            if ((t / 1000) % 5 == 0L) {
                drawText(measurer, formatTime(t), topLeft = Offset(x + 2f, 1f), style = labelStyle)
            }
            t += 1000
        }
        drawLine(DogscPalette.Line, Offset(0f, rulerH), Offset(size.width, rulerH), strokeWidth = 1f)
        for (clip in track.clips) {
            val x = clip.timelineStartMs * pxPerMs
            val w = (clip.durationMs * pxPerMs).coerceAtLeast(2f)
            val selected = clip.id == selectedClipId
            drawRoundRect(
                color = if (selected) DogscPalette.Accent else DogscPalette.ClipBlue,
                topLeft = Offset(x, clipTop),
                size = Size(w, clipH),
                cornerRadius = CornerRadius(6f),
            )
            drawText(
                measurer, clip.id, topLeft = Offset(x + 5f, clipTop + 4f),
                style = TextStyle(color = Color.White, fontSize = 10.sp),
            )
            for (seg in clip.zoomSegments()) {
                val zx = x + seg.startMs * pxPerMs
                drawRect(
                    color = DogscPalette.ZoomMark,
                    topLeft = Offset(zx, clipTop + 2f),
                    size = Size((seg.durationMs * pxPerMs).coerceAtLeast(2f), 4f),
                )
            }
        }
        val px = playheadMs * pxPerMs
        drawLine(DogscPalette.Accent, Offset(px, 0f), Offset(px, size.height), strokeWidth = 2f)
    }
}
