package dogsc.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CoreTest {

    private fun sampleProject(): Project {
        val asset = AssetRef("a1", "/tmp/screen.mp4", AssetKind.VIDEO, durationMs = 60_000, width = 1920, height = 1080)
        val clip = Clip("c1", "a1", timelineStartMs = 0, sourceStartMs = 0, durationMs = 20_000)
        return Project(
            id = "p1", name = "demo",
            assets = listOf(asset),
            tracks = listOf(Track("t1", TrackKind.VIDEO, listOf(clip))),
        )
    }

    @Test
    fun splitProducesTwoClipsAndUndoRestores() {
        val engine = EditorEngine(sampleProject())
        engine.apply(Op.SplitClip("t1", "c1", atTimelineMs = 8_000, newClipId = "c2"))
        val clips = engine.project.track("t1")!!.clips
        assertEquals(2, clips.size)
        assertEquals(8_000, clips[0].durationMs)
        assertEquals(12_000, clips[1].durationMs)
        assertEquals(8_000, clips[1].sourceStartMs)
        assertEquals(20_000, Timeline.durationMs(engine.project))
        assertTrue(engine.undo())
        assertEquals(1, engine.project.track("t1")!!.clips.size)
        assertTrue(engine.redo())
        assertEquals(2, engine.project.track("t1")!!.clips.size)
    }

    @Test
    fun splitOutsideClipFails() {
        val engine = EditorEngine(sampleProject())
        assertFailsWith<OpException> {
            engine.apply(Op.SplitClip("t1", "c1", atTimelineMs = 25_000, newClipId = "x"))
        }
        assertEquals(0, engine.project.revision)
    }

    @Test
    fun trimAndSpeedAffectSourceMath() {
        val engine = EditorEngine(sampleProject())
        engine.apply(Op.SetSpeed("t1", "c1", 2.0))
        val clip = engine.project.clip("t1", "c1")!!
        assertEquals(40_000, clip.sourceDurationMs)
        engine.apply(Op.TrimClip("t1", "c1", newTimelineStartMs = 1_000, newSourceStartMs = 5_000, newDurationMs = 10_000))
        val trimmed = engine.project.clip("t1", "c1")!!
        assertEquals(11_000, trimmed.timelineEndMs)
        assertEquals(20_000, trimmed.sourceDurationMs)
    }

    @Test
    fun zoomSegmentAddAndScaleMath() {
        val engine = EditorEngine(sampleProject())
        engine.apply(
            Op.AddZoomSegment("t1", "c1", ZoomSegment(startMs = 2_000, durationMs = 4_000, scale = 2f, easeInMs = 1_000, easeOutMs = 1_000))
        )
        val clip = engine.project.clip("t1", "c1")!!
        val segments = clip.zoomSegments()
        assertEquals(1, segments.size)
        assertEquals(1f, ZoomMath.scaleAt(segments, 0))
        assertEquals(2f, ZoomMath.scaleAt(segments, 4_000))
        val mid = ZoomMath.scaleAt(segments, 2_500)
        assertTrue(mid > 1f && mid < 2f, "ease-in should be between, was $mid")
        assertTrue(engine.undo())
        assertEquals(0, engine.project.clip("t1", "c1")!!.zoomSegments().size)
    }

    @Test
    fun snapToEdgesAndFrames() {
        val p = sampleProject()
        assertEquals(0, Timeline.snap(p, 50))
        assertEquals(20_000, Timeline.snap(p, 19_950))
        assertEquals(5_000, Timeline.snap(p, 5_000))
        assertEquals(33, Timeline.snapToFrame(40, 30))
        assertEquals(0, Timeline.snapToFrame(10, 30))
    }

    @Test
    fun projectJsonRoundTrip() {
        val engine = EditorEngine(sampleProject())
        engine.apply(Op.SetPresentation("t1", "c1", Effect.Presentation(paddingPx = 80, cornerRadiusPx = 16)))
        engine.apply(Op.AddZoomSegment("t1", "c1", ZoomSegment(1_000, 2_000, 1.5f)))
        val text = AgentTools.projectToJson(engine.project)
        val back = AgentTools.projectFromJson(text)
        assertEquals(engine.project, back)
    }

    @Test
    fun agentToolExecutesAndReceipts() {
        val engine = EditorEngine(sampleProject())
        val args = Json.parseToJsonElement(
            """{"trackId":"t1","clipId":"c1","atTimelineMs":5000,"newClipId":"c9"}"""
        ).jsonObject
        val receipt = AgentTools.execute(engine, "split_clip", args)
        assertTrue(receipt.ok, receipt.message)
        assertEquals(0, receipt.revisionBefore)
        assertEquals(1, receipt.revisionAfter)
        assertTrue(receipt.undoable)
        assertEquals(2, engine.project.track("t1")!!.clips.size)

        val bad = AgentTools.execute(engine, "split_clip",
            Json.parseToJsonElement("""{"trackId":"t1","clipId":"nope","atTimelineMs":1,"newClipId":"z"}""").jsonObject)
        assertFalse(bad.ok)
    }

    @Test
    fun ffmpegCompileBuildsGraph() {
        val engine = EditorEngine(sampleProject())
        engine.apply(Op.AddZoomSegment("t1", "c1", ZoomSegment(2_000, 4_000, 2f)))
        engine.apply(Op.SetPresentation("t1", "c1", Effect.Presentation(paddingPx = 96, cornerRadiusPx = 24)))
        val result = FfmpegCompiler.compile(engine.project, mapOf("a1" to "/tmp/screen.mp4"), "/tmp/out.mp4")
        val fc = result.command.filterComplex
        assertTrue(fc.contains("trim=start=0.0:duration=20.0"), fc)
        assertTrue(fc.contains("crop=w='iw/(if(between(t,2.0,6.0)"), fc)
        assertTrue(fc.contains("concat=n=1:v=1:a=0"), fc)
        assertTrue(result.command.args.contains("/tmp/out.mp4"))
        // shadow is preview-only in v1 and must be reported, not silently dropped
        assertTrue(result.notes.any { it.contains("shadow") }, result.notes.toString())
    }

    @Test
    fun ffmpegCompileFillsGaps() {
        var p = sampleProject()
        val engine = EditorEngine(p)
        engine.apply(Op.TrimClip("t1", "c1", newTimelineStartMs = 3_000, newSourceStartMs = 0, newDurationMs = 10_000))
        val result = FfmpegCompiler.compile(engine.project, mapOf("a1" to "/tmp/screen.mp4"), "/tmp/out.mp4", withAudio = false)
        assertTrue(result.command.filterComplex.contains("concat=n=2:v=1:a=0"), result.command.filterComplex)
    }
}
