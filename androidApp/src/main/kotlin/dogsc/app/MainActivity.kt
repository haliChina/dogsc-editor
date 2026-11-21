package dogsc.app

import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.remember
import dogsc.core.AssetKind
import dogsc.core.AssetRef
import dogsc.core.Clip
import dogsc.core.EditorEngine
import dogsc.core.Op
import dogsc.core.Project
import dogsc.core.Timeline
import dogsc.core.Track
import dogsc.core.TrackKind
import dogsc.ui.DogscTheme
import dogsc.ui.EditorScreen

/**
 * Android shell. Deliberately thin: all editing state lives in :core / :ui.
 *
 * The EditorEngine is created HERE and shared with the screen. v0.1 passed a
 * Project snapshot into the screen, which kept its own engine - imports then
 * never appeared. Import now applies AddAsset/AddClip ops to this engine.
 *
 * Export is not wired yet on Android (Media3 Transformer lands behind the
 * same button; the project format and FFmpeg graph are shared with desktop).
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DogscTheme {
                val engine = remember {
                    EditorEngine(
                        Project(
                            id = "android-project",
                            name = "未命名工程",
                            tracks = listOf(Track("t1", TrackKind.VIDEO)),
                        )
                    )
                }

                val picker = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument(),
                ) { uri: Uri? ->
                    if (uri == null) return@rememberLauncherForActivityResult
                    try {
                        contentResolver.takePersistableUriPermission(
                            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    } catch (e: Exception) {
                        // Some providers don't grant persistable permission; the
                        // session-scoped grant still covers this editing session.
                    }
                    val meta = probeMetadata(uri)
                    if (meta.durationMs <= 0) {
                        Toast.makeText(this, "读不到这段视频的时长，换一段试试", Toast.LENGTH_LONG).show()
                        return@rememberLauncherForActivityResult
                    }
                    val now = System.currentTimeMillis()
                    val asset = AssetRef(
                        id = "a$now",
                        uri = uri.toString(),
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
                                durationMs = meta.durationMs,
                            ),
                        )
                    )
                    Toast.makeText(this, "已导入：${meta.durationMs}ms", Toast.LENGTH_SHORT).show()
                }

                EditorScreen(
                    engine = engine,
                    onImport = { picker.launch(arrayOf("video/*")) },
                    onExport = {
                        Toast.makeText(
                            this, "安卓导出后端（Media3）尚未接通；工程格式与桌面端通用",
                            Toast.LENGTH_LONG,
                        ).show()
                    },
                )
            }
        }
    }

    private data class Meta(val durationMs: Long, val width: Int, val height: Int)

    private fun probeMetadata(uri: Uri): Meta {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(this, uri)
            Meta(
                durationMs = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION,
                )?.toLongOrNull() ?: 0L,
                width = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH,
                )?.toIntOrNull() ?: 0,
                height = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT,
                )?.toIntOrNull() ?: 0,
            )
        } catch (e: Exception) {
            Meta(0, 0, 0)
        } finally {
            retriever.release()
        }
    }
}
