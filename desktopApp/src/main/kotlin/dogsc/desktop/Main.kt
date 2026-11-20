package dogsc.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import dogsc.core.AgentTools
import dogsc.core.FfmpegCompiler
import dogsc.core.Project
import dogsc.core.Track
import dogsc.core.TrackKind
import dogsc.ui.EditorScreen
import java.io.File
import javax.swing.JFileChooser

/**
 * Windows desktop shell (Compose Desktop / JVM; packages to MSI/EXE).
 * Export is real on this platform: compile the project to an FFmpeg graph and run it.
 */
fun main(args: Array<String>) {
    if (args.firstOrNull() == "export-cli") {
        // Headless export: dogsc export-cli <project.json> <output.mp4>
        // Asset URIs in the project file are used as paths.
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
            MaterialTheme {
                var project by remember {
                    mutableStateOf(
                        Project(
                            id = "desktop-project",
                            name = "未命名工程",
                            tracks = listOf(Track("t1", TrackKind.VIDEO)),
                        )
                    )
                }
                EditorScreen(
                    initialProject = project,
                    onImport = {
                        val chooser = JFileChooser().apply {
                            fileFilter = javax.swing.filechooser.FileNameExtensionFilter(
                                "Video", "mp4", "mov", "mkv", "webm",
                            )
                        }
                        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                            val file = chooser.selectedFile
                            // Duration probing on desktop goes through ffprobe in the full
                            // implementation; the scaffold registers the asset with a
                            // placeholder duration the user can trim against the preview.
                            val asset = dogsc.core.AssetRef(
                                id = "a${System.currentTimeMillis()}",
                                uri = file.absolutePath,
                                kind = dogsc.core.AssetKind.VIDEO,
                                durationMs = 60_000,
                            )
                            val clip = dogsc.core.Clip(
                                id = "c${System.currentTimeMillis()}",
                                assetId = asset.id,
                                timelineStartMs = 0,
                                durationMs = 60_000,
                            )
                            project = project.copy(
                                assets = project.assets + asset,
                                tracks = project.tracks.map {
                                    if (it.id == "t1") it.copy(clips = it.clips + clip) else it
                                },
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
