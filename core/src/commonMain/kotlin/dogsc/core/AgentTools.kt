package dogsc.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

/**
 * The agent surface. Every tool maps 1:1 onto an [Op]; the built-in assistant and any
 * external MCP client end up calling these same definitions, so a human edit and an
 * agent edit are indistinguishable in the op log and equally undoable.
 *
 * Tool arguments are the op's own JSON (the op types are serializable), which keeps one
 * schema: the op definition. A receipt is returned for every call (Kinocut-style
 * provenance): what ran, the project revision before/after, and whether it is undoable.
 */
@Serializable
data class ToolDef(
    val name: String,
    val description: String,
    /** Name of the Op type this tool applies; arguments are that op's JSON object. */
    val opType: String,
)

@Serializable
data class Receipt(
    val tool: String,
    val ok: Boolean,
    val revisionBefore: Long,
    val revisionAfter: Long,
    val undoable: Boolean,
    val message: String,
)

object AgentTools {

    private val json = Json { ignoreUnknownKeys = true; classDiscriminator = "type" }

    val definitions: List<ToolDef> = listOf(
        ToolDef("project_info", "Read the current project: tracks, clips, canvas, revision.", "info"),
        ToolDef("split_clip", "Split a clip at a timeline position. Args: SplitClip op JSON.", "split_clip"),
        ToolDef("trim_clip", "Change a clip's timeline start, source start and duration. Args: TrimClip op JSON.", "trim_clip"),
        ToolDef("move_clip", "Move a clip to a new timeline position / track. Args: MoveClip op JSON.", "move_clip"),
        ToolDef("add_clip", "Add a clip referencing an existing asset. Args: AddClip op JSON.", "add_clip"),
        ToolDef("remove_clip", "Remove a clip. Args: RemoveClip op JSON.", "remove_clip"),
        ToolDef("set_speed", "Set a clip's playback speed. Args: SetSpeed op JSON.", "set_speed"),
        ToolDef("set_volume", "Set a clip's volume 0..2. Args: SetVolume op JSON.", "set_volume"),
        ToolDef("add_zoom_segment", "Add a zoom (camera move) segment to a clip. Args: AddZoomSegment op JSON.", "add_zoom_segment"),
        ToolDef("set_presentation", "Set a clip's presentation: padding, corner radius, shadow, tilt. Args: SetPresentation op JSON.", "set_presentation"),
        ToolDef("update_canvas", "Change canvas size/fps/background. Args: UpdateCanvas op JSON.", "update_canvas"),
        ToolDef("undo", "Undo the last applied op.", "undo"),
        ToolDef("redo", "Redo the last undone op.", "redo"),
    )

    /** Execute one tool call against the engine. Never throws; failures come back as receipts. */
    fun execute(engine: EditorEngine, tool: String, args: JsonObject?): Receipt {
        val before = engine.project.revision
        return try {
            when (tool) {
                "project_info" -> Receipt(tool, true, before, before, false,
                    json.encodeToString(Project.serializer(), engine.project))
                "undo" -> {
                    val ok = engine.undo()
                    Receipt(tool, ok, before, engine.project.revision, false,
                        if (ok) "undone" else "nothing to undo")
                }
                "redo" -> {
                    val ok = engine.redo()
                    Receipt(tool, ok, before, engine.project.revision, false,
                        if (ok) "redone" else "nothing to redo")
                }
                else -> {
                    val op = decodeOp(tool, args ?: throw OpException("missing args"))
                    engine.apply(op)
                    Receipt(tool, true, before, engine.project.revision, true, "applied $tool")
                }
            }
        } catch (e: Exception) {
            Receipt(tool, false, before, engine.project.revision, false, e.message ?: "error")
        }
    }

    private fun decodeOp(tool: String, args: JsonObject): Op {
        val element: Op = when (tool) {
            "split_clip" -> json.decodeFromJsonElement<Op.SplitClip>(args)
            "trim_clip" -> json.decodeFromJsonElement<Op.TrimClip>(args)
            "move_clip" -> json.decodeFromJsonElement<Op.MoveClip>(args)
            "add_clip" -> json.decodeFromJsonElement<Op.AddClip>(args)
            "remove_clip" -> json.decodeFromJsonElement<Op.RemoveClip>(args)
            "set_speed" -> json.decodeFromJsonElement<Op.SetSpeed>(args)
            "set_volume" -> json.decodeFromJsonElement<Op.SetVolume>(args)
            "add_zoom_segment" -> json.decodeFromJsonElement<Op.AddZoomSegment>(args)
            "set_presentation" -> json.decodeFromJsonElement<Op.SetPresentation>(args)
            "update_canvas" -> json.decodeFromJsonElement<Op.UpdateCanvas>(args)
            else -> throw OpException("unknown tool: $tool")
        }
        return element
    }

    fun opToJson(op: Op): String = json.encodeToString(Op.serializer(), op)
    fun projectToJson(project: Project): String =
        Json { prettyPrint = true; classDiscriminator = "type" }
            .encodeToString(Project.serializer(), project)
    fun projectFromJson(text: String): Project =
        Json { ignoreUnknownKeys = true; classDiscriminator = "type" }
            .decodeFromString(Project.serializer(), text)
}
