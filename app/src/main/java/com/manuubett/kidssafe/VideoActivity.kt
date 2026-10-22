package com.manuubett.lindaplay

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.MediaController
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView

/**
 * Plays one approved video. It counts the watching time against the daily
 * limit and closes by itself when the time is up.
 */
class VideoActivity : Activity() {

    companion object {
        const val EXTRA_URI = "uri"
        const val EXTRA_TITLE = "title"
    }

    private val handler = Handler(Looper.getMainLooper())

    private val prefs by lazy {
        getSharedPreferences("lindaplay_settings", Context.MODE_PRIVATE)
    }

    private var video: VideoView? = null

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private val ticker = object : Runnable {
        override fun run() {
            val v = video

            if (v != null && v.isPlaying) {
                Usage.tick(prefs, 1000L)

                if (Usage.remainingMs(prefs) <= 0L) {
                    Usage.flush(prefs)

                    Toast.makeText(
                        this@VideoActivity,
                        "Break time! Your watching time is done for today.",
                        Toast.LENGTH_LONG
                    ).show()

                    finish()
                    return
                }
            }

            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val uri = intent.getStringExtra(EXTRA_URI)?.let { Uri.parse(it) }

        if (uri == null) {
            finish()
            return
        }

        if (Usage.remainingMs(prefs) <= 0L) {
            Toast.makeText(
                this, "Break time! Ask a parent for more time.", Toast.LENGTH_LONG
            ).show()
            finish()
            return
        }

        // Only one thing plays at a time
        PlayerService.instance?.pause()

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        val player = VideoView(this)

        root.addView(
            player,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        )

        val close = TextView(this).apply {
            text = "✕"
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.argb(140, 0, 0, 0))
                cornerRadius = dp(22).toFloat()
            }
            setOnClickListener { finish() }
        }

        root.addView(
            close,
            FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP or Gravity.START).apply {
                setMargins(dp(12), dp(12), 0, 0)
            }
        )

        setContentView(root)

        val controller = MediaController(this)
        controller.setAnchorView(player)
        player.setMediaController(controller)

        player.setOnPreparedListener { it.start() }

        player.setOnErrorListener { _, _, _ ->
            Toast.makeText(
                this@VideoActivity, "This video cannot be played", Toast.LENGTH_LONG
            ).show()
            finish()
            true
        }

        player.setOnCompletionListener { finish() }

        player.setVideoURI(uri)
        video = player
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(ticker)
        handler.postDelayed(ticker, 1000L)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
        video?.pause()
        Usage.flush(prefs)
    }
}
