package com.local.vc

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

data class Plan(
    val durationMs: Long,
    val targetHeight: Int,
    val targetFps: Int,
    val videoBitrate: Int,
    val needScale: Boolean,
    val needFpsDrop: Boolean
)

object Planner {
    private val sizeMb = mapOf(
        (360 to 24) to 60, (360 to 30) to 80,
        (480 to 24) to 120, (480 to 30) to 150,
        (720 to 24) to 250, (720 to 30) to 350, (720 to 60) to 500,
        (1080 to 30) to 700, (1080 to 60) to 1500
    )

    private fun targetMb(res: Int, fps: Int): Double {
        sizeMb[res to fps]?.let { return it.toDouble() }
        val best = sizeMb.entries.filter { it.key.first == res }
            .minByOrNull { abs(it.key.second - fps) }!!
        return best.value * fps.toDouble() / best.key.second
    }

    fun plan(ctx: Context, uri: Uri, size: Long, selRes: Int, selFps: Int): Plan {
        val r = MediaMetadataRetriever()
        var dur = 0L
        var w = 0
        var h = 0
        var rot = 0
        var fps = 0f
        try {
            r.setDataSource(ctx, uri)
            dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            fps = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull() ?: 0f
            if (fps <= 0f && Build.VERSION.SDK_INT >= 28) {
                val frames = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toLongOrNull()
                if (frames != null && dur > 0) fps = frames * 1000f / dur
            }
        } finally {
            r.release()
        }
        if (fps <= 0f) fps = 30f

        val displayH = if (rot == 90 || rot == 270) w else h
        val needScale = displayH > selRes
        val tH = if (displayH > 0) min(selRes, displayH) else selRes
        val needDrop = selFps < fps - 1f
        val tFps = if (needDrop) selFps else fps.roundToInt().coerceAtLeast(1)

        var mb = targetMb(selRes, min(selFps, tFps))
        if (tH < selRes) {
            val f = tH.toDouble() / selRes
            mb *= f * f
        }
        val sec = (dur / 1000.0).coerceAtLeast(1.0)
        val audio = 128_000.0
        var video = mb * 1048576.0 * 8.0 / sec - audio
        if (size > 0) {
            val origTotal = size * 8.0 / sec
            val limit = origTotal * 0.85 - audio
            if (video > limit) video = limit
        }
        val bitrate = video.coerceIn(150_000.0, 20_000_000.0).toInt()
        return Plan(dur, tH, tFps, bitrate, needScale, needDrop)
    }
}
