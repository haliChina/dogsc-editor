package dogsc.app

import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dogsc.core.AssetKind
import dogsc.core.AssetRef
import dogsc.core.Clip
import dogsc.core.Project
import dogsc.core.Track
import dogsc.core.TrackKind
import dogsc.ui.EditorScreen

/**
 * Android shell. Deliberately thin: all editing state lives in :core / :ui.
 * Import probes the picked video and adds it as the first clip.
 * Export is not wired yet on Android: the FFmpeg graph backend is desktop-first;
 * the Media3 Transformer backend lands behind this same button (see docs/ARCHITECTURE.md).
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                var project by remember {
                    mutableStateOf(
                        Project(
                            id = "android-project",
                            name = "未命名工程",
                            tracks = listOf(Track("t1", TrackKind.VIDEO)),
                        )
                    )
                }
                var importTick by remember { mutableStateOf(0) }

                val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
                    if (uri == null) return@rememberLauncherForActivityResult
                    val duration = probeDuration(uri)
                    val asset = AssetRef(
                        id = "a${System.currentTimeMillis()}",
                        uri = uri.toString(),
                        kind = AssetKind.VIDEO,
                        durationMs = duration,
                    )
                    val clip = Clip(
                        id = "c${System.currentTimeMillis()}",
                        assetId = asset.id,
                        timelineStartMs = 0,
                        durationMs = duration.coerceAtLeast(1),
                    )
                    project = project.copy(
                        assets = project.assets + asset,
                        tracks = project.tracks.map {
                            if (it.id == "t1") it.copy(clips = it.clips + clip) else it
                        },
                    )
                    importTick++
                }

                EditorScreen(
                    initialProject = project,
                    onImport = { picker.launch("video/*") },
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

    private fun probeDuration(uri: Uri): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(this, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
        } catch (e: Exception) {
            0L
        } finally {
            retriever.release()
        }
    }
}
