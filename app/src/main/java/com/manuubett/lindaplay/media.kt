package com.manuubett.lindaplay

import android.content.ContentUris
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One song or one video found on the phone. */
class Track(
    val id: Long,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val isVideo: Boolean,
    val uri: Uri
) {
    // Saved in the approved list: "a:123" for a song, "v:123" for a video
    val key: String
        get() = (if (isVideo) "v:" else "a:") + id
}

/** Reads the phone's music and videos from the media library. */
object MediaLibrary {

    fun loadAudio(context: Context): List<Track> {
        val list = ArrayList<Track>()
        val base = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION
        )

        try {
            context.contentResolver.query(
                base,
                projection,
                MediaStore.Audio.Media.IS_MUSIC + " != 0",
                null,
                MediaStore.Audio.Media.TITLE + " COLLATE NOCASE ASC"
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val durCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)

                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    val artist = c.getString(artistCol) ?: ""

                    list.add(
                        Track(
                            id = id,
                            title = c.getString(titleCol) ?: "Unknown song",
                            artist = if (artist == "<unknown>") "" else artist,
                            durationMs = c.getLong(durCol),
                            isVideo = false,
                            uri = ContentUris.withAppendedId(base, id)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            // No permission or no media: return what we have
        }

        return list
    }

    fun loadVideo(context: Context): List<Track> {
        val list = ArrayList<Track>()
        val base = MediaStore.Video.Media.EXTERNAL_CONTENT_URI

        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.TITLE,
            MediaStore.Video.Media.DURATION
        )

        try {
            context.contentResolver.query(
                base,
                projection,
                null,
                null,
                MediaStore.Video.Media.TITLE + " COLLATE NOCASE ASC"
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val titleCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.TITLE)
                val durCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)

                while (c.moveToNext()) {
                    val id = c.getLong(idCol)

                    list.add(
                        Track(
                            id = id,
                            title = c.getString(titleCol) ?: "Unknown video",
                            artist = "",
                            durationMs = c.getLong(durCol),
                            isVideo = true,
                            uri = ContentUris.withAppendedId(base, id)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            // No permission or no media: return what we have
        }

        return list
    }
}

/**
 * Daily listening and watching time. The song player and the video player
 * both add to it, and it is written to storage every 15 seconds (not every
 * second), so slow phone storage is never a problem.
 */
object Usage {

    private var pendingMs = 0L

    private fun today(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    fun limitMs(prefs: SharedPreferences): Long =
        prefs.getInt("limit_minutes", 60) * 60_000L

    @Synchronized
    fun usedMs(prefs: SharedPreferences): Long {
        val saved = if (prefs.getString("used_day", "") == today()) {
            prefs.getLong("used_ms", 0L)
        } else {
            0L
        }

        return saved + pendingMs
    }

    fun remainingMs(prefs: SharedPreferences): Long =
        (limitMs(prefs) - usedMs(prefs)).coerceAtLeast(0L)

    @Synchronized
    fun tick(prefs: SharedPreferences, ms: Long) {
        pendingMs += ms

        if (pendingMs >= 15_000L) flush(prefs)
    }

    @Synchronized
    fun flush(prefs: SharedPreferences) {
        if (pendingMs <= 0L) return

        val day = today()
        val saved = if (prefs.getString("used_day", "") == day) {
            prefs.getLong("used_ms", 0L)
        } else {
            0L
        }

        prefs.edit()
            .putString("used_day", day)
            .putLong("used_ms", saved + pendingMs)
            .apply()

        pendingMs = 0L
    }
}
