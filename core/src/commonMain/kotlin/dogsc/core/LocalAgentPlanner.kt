package dogsc.core

/**
 * Built-in offline "agent": turns a short natural-language instruction
 * (Chinese or English) into the same [Op]s the UI and [AgentTools] use.
 *
 * This is the deterministic fallback planner. An LLM planner can replace it
 * behind the same interface later; both paths end at EditorEngine.apply, so
 * undo/redo and receipts behave identically.
 */
sealed interface AgentAction {
    data class Apply(val ops: List<Op>, val summary: String) : AgentAction
    data object Undo : AgentAction
    data object Redo : AgentAction
    data class Info(val text: String) : AgentAction
    data class Unknown(val reason: String) : AgentAction
}

object LocalAgentPlanner {

    private val secondsRe = Regex("""(\d+(?:\.\d+)?)\s*(?:秒|s\b)""")
    private val scaleRe = Regex("""(\d+(?:\.\d+)?)\s*(?:倍|x|X)""")
    private val percentRe = Regex("""(\d+(?:\.\d+)?)\s*%""")
    private val plainNumberRe = Regex("""(\d+(?:\.\d+)?)""")

    fun plan(project: Project, rawText: String, playheadMs: Long): AgentAction {
        val text = rawText.trim()
        if (text.isEmpty()) return AgentAction.Unknown("空指令")
        val lower = text.lowercase()

        if (text.contains("撤销") || lower.contains("undo")) return AgentAction.Undo
        if (text.contains("重做") || lower.contains("redo")) return AgentAction.Redo

        val track = project.tracks.firstOrNull { it.kind == TrackKind.VIDEO }
            ?: return AgentAction.Info("工程里还没有视频轨，先导入一段视频。")
        val clip = Timeline.clipAt(track, playheadMs) ?: track.clips.firstOrNull()
            ?: return AgentAction.Info("视频轨是空的，先导入一段视频。")

        fun secondsIn(t: String): Long? =
            secondsRe.find(t)?.groupValues?.get(1)?.toDoubleOrNull()
                ?.let { (it * 1000).toLong() }

        // --- presentation canvas (checked before canvas size: it also contains 画布) ---
        if (text.contains("演示画布") || lower.contains("presentation") || text.contains("圆角")) {
            return AgentAction.Apply(
                listOf(Op.SetPresentation(track.id, clip.id, Effect.Presentation())),
                "给片段 ${clip.id} 套演示画布（留白+圆角+阴影）",
            )
        }

        // --- canvas size ---
        if (text.contains("画布") || lower.contains("canvas") || text.contains("竖屏") || text.contains("分辨率")) {
            val (w, h) = when {
                text.contains("竖屏") || text.contains("1080x1920") || text.contains("1080×1920") -> 1080 to 1920
                text.contains("720") -> 1280 to 720
                text.contains("4K") || lower.contains("4k") -> 3840 to 2160
                else -> 1920 to 1080
            }
            return AgentAction.Apply(
                listOf(Op.UpdateCanvas(project.canvas.copy(width = w, height = h))),
                "画布改为 ${w}x${h}",
            )
        }

        // --- split ---
        if (text.contains("分割") || text.contains("切开") || text.contains("剪开") || lower.contains("split")) {
            val at = secondsIn(text) ?: playheadMs
            if (at <= clip.timelineStartMs || at >= clip.timelineEndMs) {
                return AgentAction.Unknown("分割点 ${at}ms 不在片段 ${clip.id}（${clip.timelineStartMs}-${clip.timelineEndMs}ms）内")
            }
            val newId = "${clip.id}-b${project.revision + 1}"
            return AgentAction.Apply(
                listOf(Op.SplitClip(track.id, clip.id, at, newId)),
                "在 ${at}ms 处分割片段 ${clip.id}",
            )
        }

        // --- zoom ---
        if (text.contains("缩放") || lower.contains("zoom")) {
            val scale = scaleRe.find(text)?.groupValues?.get(1)?.toFloatOrNull() ?: 1.8f
            val atAbs = secondsIn(text) ?: playheadMs
            val local = (atAbs - clip.timelineStartMs).coerceIn(0, (clip.durationMs - 500).coerceAtLeast(0))
            val seg = ZoomSegment(startMs = local, durationMs = 2_000, scale = scale)
            return AgentAction.Apply(
                listOf(Op.AddZoomSegment(track.id, clip.id, seg)),
                "在片段 ${clip.id} 的 ${local}ms 处加 ${scale}x 缩放（2 秒）",
            )
        }

        // --- speed ---
        if (text.contains("变速") || text.contains("速度") || lower.contains("speed")) {
            val speed = scaleRe.find(text)?.groupValues?.get(1)?.toDoubleOrNull()
                ?: plainNumberRe.find(text.substringAfter("速度").substringAfter("变速"))?.groupValues?.get(1)?.toDoubleOrNull()
                ?: return AgentAction.Unknown("没读出倍速，例：变速 2倍 / 速度 0.5x")
            if (speed <= 0.0) return AgentAction.Unknown("倍速必须大于 0")
            return AgentAction.Apply(
                listOf(Op.SetSpeed(track.id, clip.id, speed)),
                "片段 ${clip.id} 变速为 ${speed}x",
            )
        }

        // --- volume ---
        if (text.contains("音量") || lower.contains("volume")) {
            val pct = percentRe.find(text)?.groupValues?.get(1)?.toFloatOrNull()
            val vol = when {
                pct != null -> pct / 100f
                text.contains("静音") -> 0f
                else -> plainNumberRe.find(text)?.groupValues?.get(1)?.toFloatOrNull()
                    ?: return AgentAction.Unknown("没读出音量，例：音量 50%")
            }
            return AgentAction.Apply(
                listOf(Op.SetVolume(track.id, clip.id, vol.coerceIn(0f, 1f))),
                "片段 ${clip.id} 音量设为 ${(vol * 100).toInt()}%",
            )
        }

        // --- project info ---
        if (text.contains("信息") || text.contains("状态") || lower.contains("info") || text.contains("工程")) {
            val total = Timeline.durationMs(project)
            return AgentAction.Info(
                "工程「${project.name}」rev ${project.revision}：素材 ${project.assets.size} 个、" +
                    "轨道 ${project.tracks.size} 条、片段 ${track.clips.size} 个、总时长 ${total}ms、" +
                    "画布 ${project.canvas.width}x${project.canvas.height}",
            )
        }

        return AgentAction.Unknown(
            "听不懂这条。可试：在 5 秒分割 / 加缩放 1.8 倍 / 演示画布 / 变速 2 倍 / 音量 50% / 画布竖屏 / 撤销",
        )
    }
}
