package dogsc.desktop

import androidx.compose.runtime.remember
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import dogsc.core.AgentTools
import dogsc.core.AssetKind
import dogsc.core.AssetRef
import dogsc.core.Clip
import dogsc.core.EditorEngine
import dogsc.core.FfmpegCompiler
import dogsc.core.Op
import dogsc.core.Project
import dogsc.core.Timeline
import dogsc.core.Track
import dogsc.core.TrackKind
import dogsc.ui.DogscTheme
import dogsc.ui.EditorScreen
import java.io.File
import javax.swing.JFileChooser

private data class ProbeMeta(val durationMs: Long, val width: Int, val height: Int)

/** Real metadata via ffprobe; falls back to a 60s placeholder when unavailable. */
private fun probeWithFfprobe(file: File): ProbeMeta {
    return try {
        val proc = ProcessBuilder(
            "ffprobe", "-v", "error",
            "-select_streams", "v:0",
            "-show_entries", "stream=width,height",
            "-show_entries", "format=duration",
            "-of", "csv=p=0",
            file.absolutePath,
        ).redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        var width = 0
        var height = 0
        var durationMs = 60_000L
        for (line in out.lines()) {
            val parts = line.split(",").map { it.trim() }
            if (parts.size == 2 && parts[0].toIntOrNull() != null && parts[1].toIntOrNull() != null) {
                width = parts[0].toInt()
                height = parts[1].toInt()
            } else if (parts.size == 1) {
                parts[0].toDoubleOrNull()?.let { durationMs = (it * 1000).toLong() }
            }
        }
        ProbeMeta(durationMs, width, height)
    } catch (e: Exception) {
        ProbeMeta(60_000, 0, 0)
    }
}

/**
 * Windows desktop shell (Compose Desktop / JVM; packages to MSI/EXE).
 * Export is real on this platform: compile the project to an FFmpeg graph and run it.
 * The engine lives here and is shared with the screen - same rule as Android.
 */
fun main(args: Array<String>) {
    if (args.firstOrNull() == "export-cli") {
        // Headless export: dogsc export-cli <project.json> <output.mp4>
        val projectFile = File(args[1])
        val project = AgentTools.projectFromJson(projectFile.readText())
        val paths = project.assets.associate { it.id to it.uri }
        val result = FfmpegCompiler.compile(project, paths, args[2])
        result.notes.forEach { println("note: $it") }
        val proc = ProcessBuilder(listOf("ffmpeg") + result.command.args)
            .inheritIO().start()
        System.exit(proc.waitFor())
    }
    application {
        Window(onCloseRequest = ::exitApplication, title = "DogSC Editor") {
            DogscTheme {
                val engine = remember {
                    EditorEngine(
                        Project(
                            id = "desktop-project",
                            name = "未命名工程",
                            tracks = listOf(Track("t1", TrackKind.VIDEO)),
                        )
                    )
                }
                EditorScreen(
                    engine = engine,
                    onImport = {
                        val chooser = JFileChooser().apply {
                            fileFilter = javax.swing.filechooser.FileNameExtensionFilter(
                                "Video", "mp4", "mov", "mkv", "webm",
                            )
                        }
                        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                            val file = chooser.selectedFile
                            val meta = probeWithFfprobe(file)
                            val now = System.currentTimeMillis()
                            val asset = AssetRef(
                                id = "a$now",
                                uri = file.absolutePath,
                                kind = AssetKind.VIDEO,
                                durationMs = meta.durationMs,
                                width = meta.width,
                                height = meta.height,
                            )
                            val start = Timeline.durationMs(engine.project)
                            engine.apply(Op.AddAsset(asset))
                            engine.apply(
                                Op.AddClip(
                                    "t1",
                                    Clip(
                                        id = "c$now",
                                        assetId = asset.id,
                                        timelineStartMs = start,
                                        durationMs = meta.durationMs.coerceAtLeast(1),
                                    ),
                                )
                            )
                        }
                    },
                    onExport = { current ->
                        val paths = current.assets.associate { it.id to it.uri }
                        try {
                            val out = File(System.getProperty("user.home"), "dogsc-export.mp4")
                            val result = FfmpegCompiler.compile(current, paths, out.absolutePath)
                            ProcessBuilder(listOf("ffmpeg") + result.command.args)
                                .redirectErrorStream(true).start()
                            println("exporting to ${out.absolutePath}; notes: ${result.notes}")
                        } catch (e: Exception) {
                            println("export failed: ${e.message}")
                        }
                    },
                )
            }
        }
    }
}
