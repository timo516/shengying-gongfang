package com.mediakit.app

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.util.Locale

/** 媒体信息探测。先用 MediaMetadataRetriever（宽容），拿不到再用 MediaExtractor 兜底。 */
object MediaInfo {

    data class Info(
        val durationMs: Long,
        val width: Int,
        val height: Int,
        val hasAudio: Boolean,
        val hasVideo: Boolean
    ) {
        val durationText: String
            get() {
                if (durationMs <= 0) return "未知"
                val s = durationMs / 1000
                return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
            }
    }

    fun probe(ctx: Context, uri: Uri): Info {
        var duration = 0L
        var w = 0
        var h = 0
        var hasAudio = false
        var hasVideo = false

        // 1) MediaMetadataRetriever：对 mp4/mkv/mp3 这些都稳
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, uri)
            duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val hasVideoMeta = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)
            val hasAudioMeta = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
            hasVideo = hasVideoMeta == "yes" || w > 0
            hasAudio = hasAudioMeta == "yes"
        } catch (_: Throwable) {
        } finally {
            try {
                r.release()
            } catch (_: Throwable) {
            }
        }

        // 2) MediaExtractor 兜底：拿到真实轨道
        if ((!hasAudio && !hasVideo) || duration <= 0) {
            val ex = MediaExtractor()
            try {
                ex.setDataSource(ctx, uri, null)
                for (i in 0 until ex.trackCount) {
                    val f = ex.getTrackFormat(i)
                    val m = f.getString(MediaFormat.KEY_MIME) ?: ""
                    if (m.startsWith("audio/")) {
                        hasAudio = true
                    } else if (m.startsWith("video/")) {
                        hasVideo = true
                        if (w == 0 && f.containsKey(MediaFormat.KEY_WIDTH)) w = f.getInteger(MediaFormat.KEY_WIDTH)
                        if (h == 0 && f.containsKey(MediaFormat.KEY_HEIGHT)) h = f.getInteger(MediaFormat.KEY_HEIGHT)
                    }
                    if (duration <= 0 && f.containsKey(MediaFormat.KEY_DURATION)) {
                        val d = f.getLong(MediaFormat.KEY_DURATION) / 1000
                        if (d > duration) duration = d
                    }
                }
            } catch (_: Throwable) {
            } finally {
                try {
                    ex.release()
                } catch (_: Throwable) {
                }
            }
        }

        return Info(duration, w, h, hasAudio, hasVideo)
    }
}
