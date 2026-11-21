package dogsc.app

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.effect.Presentation
import androidx.media3.effect.SpeedChangeEffect
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import dogsc.core.Project
import dogsc.core.TrackKind
import java.io.File

/**
 * Android export v1 (Media3 Transformer).
 *
 * Applied: clip order, trim (source in/out), speed (video + audio), canvas
 * size via Presentation scale-to-fit.
 * Not yet applied (reported back in the result message, never silently):
 * timeline gaps (clips are concatenated), dynamic zoom segments, volume,
 * tilt/shadow. Those land in export v2; the desktop FFmpeg graph covers the
 * full model today.
 */
object AndroidExporter {

    @Volatile
    private var running: Transformer? = null

    fun export(context: Context, project: Project, onDone: (Boolean, String) -> Unit) {
        val track = project.tracks.firstOrNull { it.kind == TrackKind.VIDEO }
        val clips = track?.clips?.sortedBy { it.timelineStartMs }.orEmpty()
        if (clips.isEmpty()) {
            onDone(false, "没有片段可导出，先导入视频")
            return
        }
        val notes = mutableListOf<String>()
        var cursor = 0L
        for (clip in clips) {
            if (clip.timelineStartMs != cursor) notes += "片段间隙会被压缩"
            cursor = clip.timelineEndMs
            if (clip.zoomSegments().isNotEmpty()) notes += "动态缩放未应用"
            if (clip.volume != 1f) notes += "音量未应用"
            if (clip.presentation()?.tilt != null || clip.presentation()?.shadow != null) {
                notes += "倾斜/阴影未应用"
            }
        }

        val items = clips.map { clip ->
            val asset = project.asset(clip.assetId)
                ?: return onDone(false, "片段 ${clip.id} 的素材丢失")
            val mediaItem = MediaItem.Builder()
                .setUri(asset.uri)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(clip.sourceStartMs)
                        .setEndPositionMs(clip.sourceEndMs)
                        .build(),
                )
                .build()
            val audioProcessors = mutableListOf<androidx.media3.common.audio.AudioProcessor>()
            val videoEffects = mutableListOf<androidx.media3.common.Effect>(
                Presentation.createForWidthAndHeight(
                    project.canvas.width,
                    project.canvas.height,
                    Presentation.LAYOUT_SCALE_TO_FIT,
                ),
            )
            if (clip.speed != 1.0) {
                audioProcessors += SonicAudioProcessor().apply { setSpeed(clip.speed.toFloat()) }
                videoEffects += SpeedChangeEffect(clip.speed.toFloat())
            }
            EditedMediaItem.Builder(mediaItem)
                .setEffects(Effects(audioProcessors, videoEffects))
                .build()
        }

        val composition = Composition.Builder(EditedMediaItemSequence(items)).build()
        val tempFile = File(context.cacheDir, "dogsc-export-${System.currentTimeMillis()}.mp4")
        val appContext = context.applicationContext

        val transformer = Transformer.Builder(appContext).build()
        running = transformer
        transformer.addListener(object : Transformer.Listener {
            override fun onCompleted(composition: Composition, result: ExportResult) {
                running = null
                val saved = saveToMovies(appContext, tempFile, project.name)
                val noteText = notes.distinct().joinToString("；")
                onDone(
                    saved != null,
                    if (saved != null) {
                        "导出完成：$saved" + if (noteText.isNotEmpty()) "（$noteText）" else ""
                    } else {
                        "导出完成但存入相册失败，文件在缓存目录"
                    },
                )
            }

            override fun onError(
                composition: Composition,
                result: ExportResult,
                exception: ExportException,
            ) {
                running = null
                onDone(false, "导出失败：${exception.message ?: exception.errorCodeName}")
            }
        })
        transformer.start(composition, tempFile.absolutePath)
    }

    /** Copies the finished MP4 into Movies/DogSC Editor (MediaStore, API 29+). */
    private fun saveToMovies(context: Context, file: File, projectName: String): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val name = "dogsc-${System.currentTimeMillis()}.mp4"
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(
                        MediaStore.Video.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_MOVIES + "/DogSC Editor",
                    )
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return null
                resolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                }
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                "相册 Movies/DogSC Editor/$name"
            } else {
                val dir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
                val out = File(dir, "dogsc-${System.currentTimeMillis()}.mp4")
                file.copyTo(out, overwrite = true)
                out.absolutePath
            }
        } catch (e: Exception) {
            null
        }
    }
}
