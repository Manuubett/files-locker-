package com.manuubett.lindaplay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import java.util.Random

/**
 * Plays the approved songs. It runs as a foreground service, so music keeps
 * playing with the screen off and the phone does not put the app to sleep.
 * It also counts the listening time against the daily limit.
 */
class PlayerService : Service() {

    companion object {
        @Volatile
        var instance: PlayerService? = null

        private const val CHANNEL = "lindaplay_playback"
        private const val NOTIFICATION_ID = 21

        const val ACTION_TOGGLE = "com.manuubett.lindaplay.TOGGLE"
        const val ACTION_NEXT = "com.manuubett.lindaplay.NEXT"
        const val ACTION_PREVIOUS = "com.manuubett.lindaplay.PREVIOUS"
        const val ACTION_STOP = "com.manuubett.lindaplay.STOP"
    }

    inner class LocalBinder : Binder() {
        val service: PlayerService
            get() = this@PlayerService
    }

    private val binder = LocalBinder()
    private val handler = Handler(Looper.getMainLooper())
    private val random = Random()

    private val prefs by lazy {
        getSharedPreferences("lindaplay_settings", Context.MODE_PRIVATE)
    }

    private var player: MediaPlayer? = null
    private var queue: List<Track> = emptyList()
    private var errorsInRow = 0
    private var audioManager: AudioManager? = null

    var index = -1
        private set

    var shuffle = false

    // The screen listens here to refresh its mini player
    var listener: (() -> Unit)? = null

    val current: Track?
        get() = queue.getOrNull(index)

    val isPlaying: Boolean
        get() = try {
            player?.isPlaying == true
        } catch (e: Exception) {
            false
        }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS ||
            change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
        ) {
            pause()
        }
    }

    // Once a second while playing: count the time and enforce the limit
    private val ticker = object : Runnable {
        override fun run() {
            if (isPlaying) {
                Usage.tick(prefs, 1000L)

                if (Usage.remainingMs(prefs) <= 0L) {
                    pause()
                }
            }

            if (player != null) {
                handler.postDelayed(this, 1000L)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE)
                as NotificationManager

            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL, "Music playback", NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> toggle()
            ACTION_NEXT -> next()
            ACTION_PREVIOUS -> previous()
            ACTION_STOP -> stopPlayback()
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        releasePlayer()
        audioManager?.abandonAudioFocus(focusListener)
        Usage.flush(prefs)
        instance = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------
    // Controls
    // ---------------------------------------------------------------

    fun playQueue(tracks: List<Track>, start: Int) {
        if (tracks.isEmpty()) return

        queue = tracks
        errorsInRow = 0

        // Keeps the service alive after the screen is closed
        startService(Intent(applicationContext, PlayerService::class.java))

        playIndex(start.coerceIn(0, tracks.size - 1))
    }

    private fun playIndex(i: Int) {
        releasePlayer()
        index = i

        if (Usage.remainingMs(prefs) <= 0L) {
            notifyChanged()
            return
        }

        val track = queue[i]

        audioManager?.requestAudioFocus(
            focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN
        )

        val mp = MediaPlayer()
        player = mp

        try {
            mp.setAudioStreamType(AudioManager.STREAM_MUSIC)
            mp.setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
            mp.setDataSource(applicationContext, track.uri)

            mp.setOnPreparedListener {
                errorsInRow = 0
                it.start()
                showNotification()
                notifyChanged()
                handler.removeCallbacks(ticker)
                handler.postDelayed(ticker, 1000L)
            }

            mp.setOnCompletionListener {
                handler.post { next() }
            }

            mp.setOnErrorListener { _, _, _ ->
                handler.post { onPlayError() }
                true
            }

            mp.prepareAsync()
        } catch (e: Exception) {
            handler.post { onPlayError() }
        }

        showNotification()
        notifyChanged()
    }

    // A file that cannot be played is skipped. If every file fails, stop.
    private fun onPlayError() {
        errorsInRow++

        if (errorsInRow >= queue.size) {
            stopPlayback()
        } else {
            next()
        }
    }

    fun toggle() {
        val mp = player ?: return

        try {
            if (mp.isPlaying) {
                mp.pause()
                Usage.flush(prefs)
            } else {
                if (Usage.remainingMs(prefs) <= 0L) {
                    notifyChanged()
                    return
                }

                audioManager?.requestAudioFocus(
                    focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN
                )
                mp.start()
            }
        } catch (e: Exception) {
            // Player not ready yet
        }

        showNotification()
        notifyChanged()
    }

    fun pause() {
        try {
            val mp = player

            if (mp != null && mp.isPlaying) mp.pause()
        } catch (e: Exception) {
            // Player not ready
        }

        Usage.flush(prefs)
        showNotification()
        notifyChanged()
    }

    fun next() {
        if (queue.isEmpty()) return

        val target = if (shuffle && queue.size > 1) {
            var pick = index

            while (pick == index) pick = random.nextInt(queue.size)

            pick
        } else {
            (index + 1) % queue.size
        }

        playIndex(target)
    }

    fun previous() {
        if (queue.isEmpty()) return

        try {
            val position = player?.currentPosition ?: 0

            if (position > 3000) {
                player?.seekTo(0)
                return
            }
        } catch (e: Exception) {
            // Ignore and go to the previous song
        }

        playIndex((index - 1 + queue.size) % queue.size)
    }

    fun stopPlayback() {
        handler.removeCallbacks(ticker)
        releasePlayer()
        audioManager?.abandonAudioFocus(focusListener)
        Usage.flush(prefs)

        queue = emptyList()
        index = -1

        stopForeground(true)
        stopSelf()
        notifyChanged()
    }

    private fun releasePlayer() {
        val mp = player
        player = null

        try {
            mp?.release()
        } catch (e: Exception) {
            // Already released
        }
    }

    private fun notifyChanged() {
        listener?.invoke()
    }

    // ---------------------------------------------------------------
    // Notification
    // ---------------------------------------------------------------

    private fun showNotification() {
        if (current == null) return

        try {
            startForeground(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            // The phone refused a foreground start; music still plays
        }
    }

    private fun action(name: String): PendingIntent {
        return PendingIntent.getService(
            this,
            name.hashCode(),
            Intent(this, PlayerService::class.java).setAction(name),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildNotification(): Notification {
        val track = current

        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL)
        } else {
            Notification.Builder(this)
        }

        val playing = isPlaying
        val artist = track?.artist ?: ""

        builder
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(track?.title ?: "Linda Play")
            .setContentText(if (artist.isBlank()) "Linda Play" else artist)
            .setContentIntent(open)
            .setOngoing(playing)
            .addAction(
                android.R.drawable.ic_media_previous, "Previous", action(ACTION_PREVIOUS)
            )
            .addAction(
                if (playing) android.R.drawable.ic_media_pause
                else android.R.drawable.ic_media_play,
                if (playing) "Pause" else "Play",
                action(ACTION_TOGGLE)
            )
            .addAction(
                android.R.drawable.ic_media_next, "Next", action(ACTION_NEXT)
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel, "Stop", action(ACTION_STOP)
            )
            .setStyle(Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2))

        return builder.build()
    }
}
