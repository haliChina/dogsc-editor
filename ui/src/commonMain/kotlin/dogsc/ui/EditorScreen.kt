package dogsc.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import dogsc.core.Clip
import dogsc.core.EditorEngine
import dogsc.core.Effect
import dogsc.core.Op
import dogsc.core.Project
import dogsc.core.Timeline
import dogsc.core.ZoomMath
import dogsc.core.ZoomSegment

/**
 * Shared editor UI (Android + Windows desktop run this same composable).
 * Platform shells provide [onImport] and [onExport]; everything else is core ops.
 *
 * Preview is a schematic stage in this MVP: it shows the canvas, the active clip's
 * presentation (padding / corner radius) and the live zoom scale at the playhead.
 * The real video preview plugs in per platform (Media3 on Android, mpv/ffmpeg on
 * Windows) behind this same state.
 */
@Composable
fun EditorScreen(
    initialProject: Project,
    onImport: () -> Unit,
    onExport: (Project) -> Unit,
    modifier: Modifier = Modifier,
) {
    val engine = remember { EditorEngine(initialProject) }
    var project by remember { mutableStateOf(engine.project) }
    var playheadMs by remember { mutableLongStateOf(0L) }
    var selectedClipId by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("") }

    fun apply(op: Op) {
        try {
            engine.apply(op)
            project = engine.project
            status = ""
        } catch (e: Exception) {
            status = e.message ?: "op failed"
        }
    }

    val videoTrack = project.tracks.firstOrNull { it.kind.name == "VIDEO" }
    val duration = Timeline.durationMs(project).coerceAtLeast(1)
    val activeClip = videoTrack?.let { Timeline.clipAt(it, playheadMs) }
    val zoomScale = activeClip?.let {
        ZoomMath.scaleAt(it.zoomSegments(), playheadMs - it.timelineStartMs)
    } ?: 1f

    Column(modifier = modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(project.name, style = MaterialTheme.typography.titleMedium)
            Text("rev ${project.revision}", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onImport) { Text("导入视频") }
            Button(onClick = { onExport(project) }) { Text("导出") }
        }

        // ---- Stage / preview placeholder ----
        Box(
            Modifier.fillMaxWidth().height(260.dp)
                .background(Color(project.canvas.background.let {
                    if (it is dogsc.core.BackgroundSpec.SolidColor) it.argb else 0xFF202020
                })),
            contentAlignment = Alignment.Center,
        ) {
            val pres = activeClip?.presentation()
            val pad = (pres?.paddingPx ?: 0).toFloat()
            Canvas(Modifier.fillMaxSize()) {
                val scale = zoomScale
                val w = (size.width - pad * 2) / scale + pad * 2 * 0f
                val innerW = size.width - pad * 2
                val innerH = size.height - pad * 2
                val zw = innerW / scale
                val zh = innerH / scale
                drawRoundRect(
                    color = Color(0xFF3A6EA5),
                    topLeft = Offset(pad + (innerW - zw) / 2, pad + (innerH - zh) / 2),
                    size = Size(zw, zh),
                    cornerRadius = CornerRadius((pres?.cornerRadiusPx ?: 0).toFloat()),
                )
                drawRoundRect(
                    color = Color.White,
                    topLeft = Offset(pad, pad),
                    size = Size(innerW, innerH),
                    cornerRadius = CornerRadius((pres?.cornerRadiusPx ?: 0).toFloat()),
                    style = Stroke(width = 2f),
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    activeClip?.let { "片段 ${it.id} · x${"%.2f".format(zoomScale)}" } ?: "无片段（先导入视频）",
                    color = Color.White,
                )
                Text("${playheadMs}ms / ${duration}ms", color = Color.White)
            }
        }

        Slider(
            value = playheadMs.toFloat(),
            onValueChange = { playheadMs = Timeline.snap(project, it.toLong()) },
            valueRange = 0f..duration.toFloat(),
        )

        // ---- Timeline ----
        TimelineView(
            project = project,
            playheadMs = playheadMs,
            selectedClipId = selectedClipId,
            onSeek = { playheadMs = it },
            onSelect = { selectedClipId = it },
            modifier = Modifier.fillMaxWidth().height(72.dp),
        )

        // ---- Op buttons: everything goes through the engine, same as agent tools ----
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
            OutlinedButton(enabled = engine.canUndo, onClick = { engine.undo(); project = engine.project }) { Text("撤销") }
            OutlinedButton(enabled = engine.canRedo, onClick = { engine.redo(); project = engine.project }) { Text("重做") }
        }
        if (status.isNotEmpty()) Text(status, color = MaterialTheme.colorScheme.error)
        Text(
            "片段: " + (videoTrack?.clips?.joinToString { "${it.id}[${it.timelineStartMs}..${it.timelineEndMs}]" } ?: "无"),
            style = MaterialTheme.typography.bodySmall,
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
    Canvas(
        modifier.background(Color(0xFF1E1E1E)).pointerInput(duration) {
            detectTapGestures { offset ->
                val t = (offset.x / size.width * duration).toLong()
                onSeek(t)
                val track = project.tracks.firstOrNull()
                track?.let { tr -> Timeline.clipAt(tr, t)?.let { c: Clip -> onSelect(c.id) } }
            }
        },
    ) {
        val track = project.tracks.firstOrNull() ?: return@Canvas
        val pxPerMs = size.width / duration.toFloat()
        for (clip in track.clips) {
            val x = clip.timelineStartMs * pxPerMs
            val w = clip.durationMs * pxPerMs
            drawRect(
                color = if (clip.id == selectedClipId) Color(0xFFFF8A3D) else Color(0xFF3A6EA5),
                topLeft = Offset(x, 10f),
                size = Size(w.coerceAtLeast(2f), size.height - 20f),
            )
            // zoom segment markers
            for (seg in clip.zoomSegments()) {
                val zx = x + seg.startMs * pxPerMs
                drawRect(
                    color = Color(0xFFFFD54F),
                    topLeft = Offset(zx, 4f),
                    size = Size((seg.durationMs * pxPerMs).coerceAtLeast(2f), 4f),
                )
            }
        }
        val px = playheadMs * pxPerMs
        drawLine(Color.Red, Offset(px, 0f), Offset(px, size.height), strokeWidth = 2f)
    }
}
