package dogsc.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Every edit is a serializable [Op]. The UI buttons and the agent tools both go through
 * [EditorEngine.apply]; there is no second path that mutates a [Project].
 * An applied op produces its inverse, which powers undo and the agent receipt.
 */
@Serializable
sealed interface Op {
    @Serializable
    @SerialName("add_track")
    data class AddTrack(val track: Track) : Op

    @Serializable
    @SerialName("remove_track")
    data class RemoveTrack(val trackId: String) : Op

    @Serializable
    @SerialName("add_asset")
    data class AddAsset(val asset: AssetRef) : Op

    @Serializable
    @SerialName("add_clip")
    data class AddClip(val trackId: String, val clip: Clip) : Op

    @Serializable
    @SerialName("remove_clip")
    data class RemoveClip(val trackId: String, val clipId: String) : Op

    @Serializable
    @SerialName("split_clip")
    data class SplitClip(
        val trackId: String,
        val clipId: String,
        /** Timeline position to split at; must be strictly inside the clip. */
        val atTimelineMs: Long,
        val newClipId: String,
    ) : Op

    @Serializable
    @SerialName("trim_clip")
    data class TrimClip(
        val trackId: String,
        val clipId: String,
        val newTimelineStartMs: Long,
        val newSourceStartMs: Long,
        val newDurationMs: Long,
    ) : Op

    @Serializable
    @SerialName("move_clip")
    data class MoveClip(
        val fromTrackId: String,
        val toTrackId: String,
        val clipId: String,
        val newTimelineStartMs: Long,
    ) : Op

    @Serializable
    @SerialName("set_speed")
    data class SetSpeed(val trackId: String, val clipId: String, val speed: Double) : Op

    @Serializable
    @SerialName("set_volume")
    data class SetVolume(val trackId: String, val clipId: String, val volume: Float) : Op

    @Serializable
    @SerialName("add_effect")
    data class AddEffect(val trackId: String, val clipId: String, val effect: Effect) : Op

    @Serializable
    @SerialName("remove_effect")
    data class RemoveEffect(val trackId: String, val clipId: String, val effectIndex: Int) : Op

    @Serializable
    @SerialName("add_zoom_segment")
    data class AddZoomSegment(val trackId: String, val clipId: String, val segment: ZoomSegment) : Op

    @Serializable
    @SerialName("set_presentation")
    data class SetPresentation(
        val trackId: String,
        val clipId: String,
        val presentation: Effect.Presentation,
    ) : Op

    @Serializable
    @SerialName("update_canvas")
    data class UpdateCanvas(val canvas: CanvasSpec) : Op

    @Serializable
    @SerialName("rename")
    data class Rename(val name: String) : Op
}

class OpException(message: String) : Exception(message)

/** Pure project transforms. Each returns the new project plus the inverse op. */
object ProjectOps {

    fun apply(project: Project, op: Op): Pair<Project, Op> {
        val (next, inverse) = when (op) {
            is Op.AddTrack -> {
                if (project.track(op.track.id) != null) throw OpException("track exists: ${op.track.id}")
                project.copy(tracks = project.tracks + op.track) to Op.RemoveTrack(op.track.id)
            }
            is Op.RemoveTrack -> {
                val track = project.track(op.trackId) ?: throw OpException("no track: ${op.trackId}")
                project.copy(tracks = project.tracks - track) to Op.AddTrack(track)
            }
            is Op.AddAsset -> {
                if (project.asset(op.asset.id) != null) throw OpException("asset exists: ${op.asset.id}")
                project.copy(assets = project.assets + op.asset) to
                    // No RemoveAsset op in the MVP set; inverse is a no-op rename-free marker.
                    // Undo of AddAsset is handled by the engine snapshot for this op type.
                    Op.Rename(project.name)
            }
            is Op.AddClip -> {
                val track = project.track(op.trackId) ?: throw OpException("no track: ${op.trackId}")
                if (track.clips.any { it.id == op.clip.id }) throw OpException("clip exists: ${op.clip.id}")
                replaceTrack(project, track.copy(clips = (track.clips + op.clip).sortedBy { it.timelineStartMs })) to
                    Op.RemoveClip(op.trackId, op.clip.id)
            }
            is Op.RemoveClip -> {
                val clip = project.clip(op.trackId, op.clipId) ?: throw OpException("no clip: ${op.clipId}")
                val track = project.track(op.trackId)!!
                replaceTrack(project, track.copy(clips = track.clips - clip)) to Op.AddClip(op.trackId, clip)
            }
            is Op.SplitClip -> split(project, op)
            is Op.TrimClip -> {
                val clip = project.clip(op.trackId, op.clipId) ?: throw OpException("no clip: ${op.clipId}")
                if (op.newDurationMs <= 0) throw OpException("duration must be > 0")
                val updated = clip.copy(
                    timelineStartMs = op.newTimelineStartMs,
                    sourceStartMs = op.newSourceStartMs,
                    durationMs = op.newDurationMs,
                )
                replaceClip(project, op.trackId, updated) to Op.TrimClip(
                    op.trackId, op.clipId, clip.timelineStartMs, clip.sourceStartMs, clip.durationMs,
                )
            }
            is Op.MoveClip -> {
                val clip = project.clip(op.fromTrackId, op.clipId) ?: throw OpException("no clip: ${op.clipId}")
                val from = project.track(op.fromTrackId)!!
                val to = project.track(op.toTrackId) ?: throw OpException("no track: ${op.toTrackId}")
                var next = replaceTrack(project, from.copy(clips = from.clips - clip))
                val moved = clip.copy(timelineStartMs = op.newTimelineStartMs)
                val toNow = next.track(op.toTrackId)!!
                next = replaceTrack(next, toNow.copy(clips = (toNow.clips + moved).sortedBy { it.timelineStartMs }))
                next to Op.MoveClip(op.toTrackId, op.fromTrackId, op.clipId, clip.timelineStartMs)
            }
            is Op.SetSpeed -> {
                val clip = project.clip(op.trackId, op.clipId) ?: throw OpException("no clip: ${op.clipId}")
                if (op.speed <= 0) throw OpException("speed must be > 0")
                replaceClip(project, op.trackId, clip.copy(speed = op.speed)) to
                    Op.SetSpeed(op.trackId, op.clipId, clip.speed)
            }
            is Op.SetVolume -> {
                val clip = project.clip(op.trackId, op.clipId) ?: throw OpException("no clip: ${op.clipId}")
                replaceClip(project, op.trackId, clip.copy(volume = op.volume.coerceIn(0f, 2f))) to
                    Op.SetVolume(op.trackId, op.clipId, clip.volume)
            }
            is Op.AddEffect -> {
                val clip = project.clip(op.trackId, op.clipId) ?: throw OpException("no clip: ${op.clipId}")
                replaceClip(project, op.trackId, clip.copy(effects = clip.effects + op.effect)) to
                    Op.RemoveEffect(op.trackId, op.clipId, clip.effects.size)
            }
            is Op.RemoveEffect -> {
                val clip = project.clip(op.trackId, op.clipId) ?: throw OpException("no clip: ${op.clipId}")
                val removed = clip.effects.getOrNull(op.effectIndex) ?: throw OpException("no effect at ${op.effectIndex}")
                replaceClip(project, op.trackId, clip.copy(effects = clip.effects.filterIndexed { i, _ -> i != op.effectIndex })) to
                    Op.AddEffect(op.trackId, op.clipId, removed)
            }
            is Op.AddZoomSegment -> {
                val clip = project.clip(op.trackId, op.clipId) ?: throw OpException("no clip: ${op.clipId}")
                val zoomIdx = clip.effects.indexOfFirst { it is Effect.Zoom }
                val updated = if (zoomIdx >= 0) {
                    val zoom = clip.effects[zoomIdx] as Effect.Zoom
                    clip.copy(effects = clip.effects.mapIndexed { i, e ->
                        if (i == zoomIdx) Effect.Zoom((zoom.segments + op.segment).sortedBy { s -> s.startMs }) else e
                    })
                } else {
                    clip.copy(effects = clip.effects + Effect.Zoom(listOf(op.segment)))
                }
                // If a Zoom effect already existed, removing one segment is not a single op;
                // the engine snapshot covers exact restoration, so the log inverse is a marker.
                val inverse: Op = if (zoomIdx >= 0) {
                    Op.Rename(project.name)
                } else {
                    Op.RemoveEffect(op.trackId, op.clipId, clip.effects.size)
                }
                replaceClip(project, op.trackId, updated) to inverse
            }
            is Op.SetPresentation -> {
                val clip = project.clip(op.trackId, op.clipId) ?: throw OpException("no clip: ${op.clipId}")
                val idx = clip.effects.indexOfFirst { it is Effect.Presentation }
                val updated = if (idx >= 0) {
                    clip.copy(effects = clip.effects.mapIndexed { i, e -> if (i == idx) op.presentation else e })
                } else {
                    clip.copy(effects = clip.effects + op.presentation)
                }
                val inverse: Op = if (idx >= 0) {
                    Op.SetPresentation(op.trackId, op.clipId, clip.effects[idx] as Effect.Presentation)
                } else {
                    Op.RemoveEffect(op.trackId, op.clipId, clip.effects.size)
                }
                replaceClip(project, op.trackId, updated) to inverse
            }
            is Op.UpdateCanvas -> project.copy(canvas = op.canvas) to Op.UpdateCanvas(project.canvas)
            is Op.Rename -> project.copy(name = op.name) to Op.Rename(project.name)
        }
        return next.copy(revision = project.revision + 1) to inverse
    }

    private fun split(project: Project, op: Op.SplitClip): Pair<Project, Op> {
        val clip = project.clip(op.trackId, op.clipId) ?: throw OpException("no clip: ${op.clipId}")
        val at = op.atTimelineMs
        if (at <= clip.timelineStartMs || at >= clip.timelineEndMs) {
            throw OpException("split point $at outside clip ${clip.timelineStartMs}..${clip.timelineEndMs}")
        }
        val leftDuration = at - clip.timelineStartMs
        val rightDuration = clip.durationMs - leftDuration
        val left = clip.copy(durationMs = leftDuration)
        val right = clip.copy(
            id = op.newClipId,
            timelineStartMs = at,
            sourceStartMs = clip.sourceStartMs + (leftDuration * clip.speed).toLong(),
            durationMs = rightDuration,
        )
        val track = project.track(op.trackId)!!
        val next = replaceTrack(project, track.copy(clips = (track.clips - clip + left + right).sortedBy { it.timelineStartMs }))
        // Undo of a split needs a merge, which is not a single op; the engine snapshot covers it.
        // Structural inverse returned for the log: remove the new right clip and re-trim the left.
        return next to Op.RemoveClip(op.trackId, op.newClipId)
    }

    private fun replaceTrack(project: Project, track: Track): Project =
        project.copy(tracks = project.tracks.map { if (it.id == track.id) track else it })

    private fun replaceClip(project: Project, trackId: String, clip: Clip): Project {
        val track = project.track(trackId) ?: throw OpException("no track: $trackId")
        return replaceTrack(project, track.copy(clips = track.clips.map { if (it.id == clip.id) clip else it }))
    }
}

/**
 * Stateful editor: current project + undo/redo.
 * Undo uses full snapshots paired with each op (projects are small value objects;
 * media is referenced, never copied), which makes every op exactly reversible —
 * including split and zoom-segment edits whose structural inverse is not a single op.
 */
class EditorEngine(initial: Project) {
    var project: Project = initial
        private set

    private data class Entry(val op: Op, val inverse: Op, val before: Project)

    private val undoStack = ArrayDeque<Entry>()
    private val redoStack = ArrayDeque<Entry>()

    val opLog: List<Op> get() = undoStack.map { it.op }
    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    // Shells and UI observe every mutation (button ops, imports, agent ops) through this.
    private val listeners = mutableListOf<() -> Unit>()
    fun addListener(listener: () -> Unit) { listeners += listener }
    fun removeListener(listener: () -> Unit) { listeners -= listener }
    private fun notifyChanged() { listeners.toList().forEach { it() } }

    fun apply(op: Op): Op {
        val before = project
        val (next, inverse) = ProjectOps.apply(before, op)
        project = next
        undoStack.addLast(Entry(op, inverse, before))
        redoStack.clear()
        notifyChanged()
        return inverse
    }

    fun undo(): Boolean {
        val entry = undoStack.removeLastOrNull() ?: return false
        project = entry.before
        redoStack.addLast(entry)
        notifyChanged()
        return true
    }

    fun redo(): Boolean {
        val entry = redoStack.removeLastOrNull() ?: return false
        val (next, _) = ProjectOps.apply(project, entry.op)
        project = next
        undoStack.addLast(entry)
        notifyChanged()
        return true
    }
}
