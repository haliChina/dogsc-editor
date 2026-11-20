package dogsc.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * End-to-end: generate a 6s testsrc clip, compile a project that trims it, zooms and
 * presents it with rounded corners, run the produced ffmpeg command, probe the output.
 * Skipped automatically when ffmpeg/ffprobe are not on PATH.
 */
class FfmpegRenderTest {

    private fun ffmpegAvailable(): Boolean = try {
        ProcessBuilder("ffmpeg", "-version").start().waitFor() == 0
    } catch (e: Exception) {
        false
    }

    @Test
    fun rendersCompiledProject() {
        if (!ffmpegAvailable()) return
        val dir = File(System.getProperty("java.io.tmpdir"), "dogsc-render-test").apply { mkdirs() }
        val src = File(dir, "src.mp4")
        val gen = ProcessBuilder(
            "ffmpeg", "-y", "-f", "lavfi", "-i", "testsrc2=size=1280x720:rate=30:duration=6",
            "-f", "lavfi", "-i", "sine=frequency=440:duration=6",
            "-shortest", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", src.absolutePath,
        ).redirectErrorStream(true).start()
        assertTrue(gen.waitFor() == 0, "testsrc generation failed")

        val project = Project(
            id = "p", name = "render",
            canvas = CanvasSpec(1280, 720, 30),
            assets = listOf(AssetRef("a1", src.absolutePath, AssetKind.VIDEO, 6_000, 1280, 720)),
            tracks = listOf(
                Track(
                    "t1", TrackKind.VIDEO,
                    listOf(
                        Clip(
                            "c1", "a1", timelineStartMs = 0, sourceStartMs = 500, durationMs = 4_000,
                            effects = listOf(
                                Effect.Zoom(listOf(ZoomSegment(1_000, 2_000, 1.8f))),
                                Effect.Presentation(paddingPx = 80, cornerRadiusPx = 24, shadow = null),
                            ),
                        )
                    ),
                )
            ),
        )
        val out = File(dir, "out.mp4")
        val result = FfmpegCompiler.compile(project, mapOf("a1" to src.absolutePath), out.absolutePath)
        val proc = ProcessBuilder(listOf("ffmpeg") + result.command.args)
            .redirectErrorStream(true).start()
        val log = proc.inputStream.readBytes().decodeToString()
        assertTrue(proc.waitFor() == 0, "ffmpeg render failed:\n$log\nargs=${result.command.args}")
        assertTrue(out.exists() && out.length() > 10_000, "output missing/small: ${out.length()}")

        val probe = ProcessBuilder(
            "ffprobe", "-v", "error", "-show_entries", "format=duration",
            "-of", "default=noprint_wrappers=1:nokey=1", out.absolutePath,
        ).redirectErrorStream(true).start()
        val duration = probe.inputStream.readBytes().decodeToString().trim().toDouble()
        probe.waitFor()
        assertTrue(duration in 3.5..4.5, "expected ~4s output, got $duration")
    }
}
