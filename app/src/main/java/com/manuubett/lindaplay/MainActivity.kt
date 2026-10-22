package com.manuubett.lindaplay

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class MainActivity : Activity() {

    private val prefs by lazy {
        getSharedPreferences("lindaplay_settings", MODE_PRIVATE)
    }

    private val blue = Color.rgb(30, 64, 120)
    private val blueSoft = Color.rgb(227, 236, 250)
    private val paleBlue = Color.rgb(244, 247, 252)
    private val green = Color.rgb(21, 128, 61)
    private val red = Color.rgb(200, 45, 45)
    private val amber = Color.rgb(180, 105, 0)
    private val textDark = Color.rgb(35, 45, 62)
    private val textMuted = Color.rgb(100, 112, 132)

    private val matchParent = LinearLayout.LayoutParams.MATCH_PARENT
    private val wrapContent = LinearLayout.LayoutParams.WRAP_CONTENT

    private val medium: Typeface
        get() = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    private val appName: String
        get() = getString(R.string.app_name)

    private val handler = Handler(Looper.getMainLooper())

    private lateinit var gate: PayGate

    // Everything found on the phone (loaded in the background)
    @Volatile
    private var allAudio: List<Track> = emptyList()

    @Volatile
    private var allVideo: List<Track> = emptyList()

    // Which screen is showing: loading, locked, home, dashboard, picker, other
    private var screen = "loading"
    private var tab = 0
    private var homeBreak = false
    private var tickRunnable: Runnable? = null

    // Music player (runs in PlayerService)
    private var service: PlayerService? = null
    private var homeAdapter: TrackAdapter? = null
    private var miniBox: View? = null
    private var miniTitle: TextView? = null
    private var miniArtist: TextView? = null
    private var miniPlay: TextView? = null
    private var miniShuffle: TextView? = null

    // App updates (from the DEH EMANUELS SOLUTIONS app store)
    private var availableUpdate: UpdateChecker.Update? = null
    private var updateNote = "Tap to check for updates"
    private var updateChecked = false
    private var updateChecking = false
    private var updateDownloadId = -1L

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = (binder as? PlayerService.LocalBinder)?.service
            service = svc
            svc?.listener = { onPlayerChanged() }
            onPlayerChanged()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)

            if (id != -1L && id == updateDownloadId) {
                installDownloaded(id)
            }
        }
    }

    // A list adapter that builds only the rows on screen, so a phone with
    // thousands of songs never freezes the app.
    private class TrackAdapter(
        var items: List<Track>,
        private val rowFor: (Track) -> View
    ) : BaseAdapter() {

        override fun getCount(): Int = items.size

        override fun getItem(position: Int): Any = items[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(
            position: Int, convertView: View?, parent: ViewGroup?
        ): View = rowFor(items[position])
    }

    // ---------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyWindowStyle()

        gate = PayGate(this, appId = "lindaplay")

        registerReceiver(
            downloadReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        )

        showLoading("Getting things ready…")
        begin()
    }

    override fun onStart() {
        super.onStart()

        bindService(
            Intent(this, PlayerService::class.java),
            connection,
            Context.BIND_AUTO_CREATE
        )
    }

    override fun onStop() {
        service?.listener = null

        try {
            unbindService(connection)
        } catch (e: Exception) {
            // Not bound
        }

        service = null
        super.onStop()
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(downloadReceiver)
        } catch (e: Exception) {
            // Not registered
        }

        stopHomeTick()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == 1) {
            if (hasMediaPermission()) {
                showLoading("Getting things ready…")
                begin()
            } else {
                Toast.makeText(
                    this,
                    "Linda Play needs access to your music and videos.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // ---------------------------------------------------------------
    // Start-up
    // ---------------------------------------------------------------

    private fun hasMediaPermission(): Boolean {
        return Build.VERSION.SDK_INT < 23 ||
            checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun begin() {
        if (!hasMediaPermission()) {
            showPermissionScreen()
            return
        }

        loadLibrary { afterLibrary() }
    }

    private fun loadLibrary(done: () -> Unit) {
        Thread {
            val audio = MediaLibrary.loadAudio(this)
            val video = MediaLibrary.loadVideo(this)

            allAudio = audio
            allVideo = video

            runOnUiThread {
                if (!isFinishing && !isDestroyed) done()
            }
        }.start()
    }

    // A recent successful check lets the app open at once; the subscription
    // is still re-checked quietly in the background.
    private fun afterLibrary() {
        val sinceOk = System.currentTimeMillis() - prefs.getLong("sub_ok_at", 0L)
        val fresh = sinceOk in 0L..86_400_000L

        if (fresh) {
            showHome()
        } else {
            showLoading("Checking your subscription…")
        }

        gate.check(onDenied = {
            prefs.edit().putLong("sub_ok_at", 0L).apply()
            showLocked()
        }) {
            onSubscriptionOk()
        }
    }

    private fun onSubscriptionOk() {
        prefs.edit().putLong("sub_ok_at", System.currentTimeMillis()).apply()

        if (screen == "loading" || screen == "locked") showHome()
    }

    private fun beginScreen(name: String) {
        stopHomeTick()
        screen = name
        miniBox = null
        homeAdapter = null

        // The child's home screen is pinned. Every other screen (loading,
        // parent dashboard, picker, subscription) is released so a parent
        // can install updates, open links and subscribe.
        if (name == "home") {
            handler.postDelayed({ if (screen == "home") lockKids() }, 400L)
        } else if (name != "loading") {
            unlockKids()
        }
    }

    // ---------------------------------------------------------------
    // Kids lock (screen pinning)
    // ---------------------------------------------------------------

    private fun kidsLockOn(): Boolean = prefs.getBoolean("kids_lock", true)

    private fun isPinned(): Boolean {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        return am.lockTaskModeState != android.app.ActivityManager.LOCK_TASK_MODE_NONE
    }

    private fun lockKids() {
        if (!kidsLockOn() || isPinned() || isFinishing || isDestroyed) return

        try {
            startLockTask()
        } catch (e: Exception) {
            // Pinning not available right now
        }
    }

    private fun unlockKids() {
        if (!isPinned()) return

        try {
            stopLockTask()
        } catch (e: Exception) {
            // Not pinned
        }
    }

    // Back never leaves the app. Parent screens step back one level.
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when (screen) {
            "picker" -> showDashboard()
            "dashboard" -> showHome()
            else -> {
                // Do nothing: the child stays inside Linda Play
            }
        }
    }

    // ---------------------------------------------------------------
    // UI helpers
    // ---------------------------------------------------------------

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun roundedBackground(color: Int, radiusDp: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }
    }

    private fun withRipple(content: Drawable, radiusDp: Int): Drawable {
        return RippleDrawable(
            ColorStateList.valueOf(Color.argb(45, 30, 64, 120)),
            content,
            roundedBackground(Color.WHITE, radiusDp)
        )
    }

    private fun applyWindowStyle() {
        window.statusBarColor = paleBlue
        window.navigationBarColor = Color.WHITE

        if (Build.VERSION.SDK_INT >= 23) {
            var flags = window.decorView.systemUiVisibility
            flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR

            if (Build.VERSION.SDK_INT >= 26) {
                flags = flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            }

            window.decorView.systemUiVisibility = flags
        }
    }

    private fun makeLayout(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(28), dp(16), dp(12))
            setBackgroundColor(paleBlue)
        }
    }

    private fun makeFooter(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(14))
            setBackgroundColor(Color.WHITE)
            elevation = dp(10).toFloat()
        }
    }

    private fun addFooterLabels(footer: LinearLayout, linkEnabled: Boolean) {
        footer.addView(TextView(this).apply {
            text = "$appName v${UpdateChecker.installedVersion(this@MainActivity)}"
            textSize = 11f
            setTextColor(Color.rgb(140, 150, 170))
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
        })

        footer.addView(TextView(this).apply {
            text = "Powered by dehemanuelssolutions.co.ke"
            textSize = 11f
            typeface = medium
            setTextColor(blue)
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, dp(2))

            // The link opens only on parent screens, never on the child's home
            if (linkEnabled) {
                isClickable = true
                setOnClickListener {
                    try {
                        startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://dehemanuelssolutions.co.ke/")
                            )
                        )
                    } catch (e: Exception) {
                        Toast.makeText(
                            this@MainActivity,
                            "Could not open the website",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        })
    }

    private fun showScrollable(
        layout: LinearLayout,
        footer: LinearLayout? = null,
        linkEnabled: Boolean = true
    ) {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(paleBlue)
            isFillViewport = true
        }
        scroll.addView(layout)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(paleBlue)
        }

        root.addView(scroll, LinearLayout.LayoutParams(matchParent, 0, 1f))

        if (footer != null) {
            addFooterLabels(footer, linkEnabled)
            root.addView(footer, LinearLayout.LayoutParams(matchParent, wrapContent))
        }

        setContentView(root)
    }

    private fun showLoading(message: String) {
        beginScreen("loading")

        val layout = makeLayout()
        layout.gravity = Gravity.CENTER

        layout.addView(ProgressBar(this).apply {
            indeterminateTintList = ColorStateList.valueOf(blue)
        })

        addSubtitle(layout, message)
        showScrollable(layout)
    }

    private fun addTitle(layout: LinearLayout, text: String) {
        layout.addView(TextView(this).apply {
            this.text = text
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(blue)
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(2))
        })
    }

    private fun addSubtitle(layout: LinearLayout, text: String) {
        layout.addView(TextView(this).apply {
            this.text = text
            textSize = 15f
            setTextColor(textMuted)
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.15f)
            setPadding(dp(8), dp(2), dp(8), dp(14))
        })
    }

    private fun addButton(
        layout: LinearLayout,
        label: String,
        filled: Boolean = true,
        action: () -> Unit
    ) {
        val button = TextView(this).apply {
            text = label
            textSize = 16f
            typeface = medium
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            minHeight = dp(52)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setTextColor(if (filled) Color.WHITE else blue)
            background = withRipple(
                if (filled) {
                    roundedBackground(blue, 16)
                } else {
                    roundedBackground(Color.WHITE, 16).apply { setStroke(dp(2), blue) }
                },
                16
            )
            setOnClickListener { action() }
        }

        layout.addView(
            button,
            LinearLayout.LayoutParams(matchParent, wrapContent).apply {
                topMargin = dp(10)
            }
        )
    }

    private fun addBrandHeader(layout: LinearLayout, title: String, subtitle: String) {
        layout.addView(
            TextView(this).apply {
                text = "▶"
                textSize = 26f
                setTextColor(Color.rgb(245, 158, 11))
                gravity = Gravity.CENTER
                background = roundedBackground(blueSoft, 32)
            },
            LinearLayout.LayoutParams(dp(64), dp(64)).apply {
                bottomMargin = dp(10)
            }
        )
        addTitle(layout, title)
        addSubtitle(layout, subtitle)
    }

    private fun addNoticeCard(
        layout: LinearLayout,
        emoji: String,
        title: String,
        body: String,
        accent: Int = blue
    ) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
            background = roundedBackground(Color.WHITE, 22)
            elevation = dp(2).toFloat()
        }

        box.addView(TextView(this).apply {
            text = emoji
            textSize = 38f
            gravity = Gravity.CENTER
        })

        box.addView(TextView(this).apply {
            text = title
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(accent)
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(4))
        })

        box.addView(TextView(this).apply {
            text = body
            textSize = 15f
            setTextColor(textMuted)
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.15f)
        })

        layout.addView(
            box,
            LinearLayout.LayoutParams(matchParent, wrapContent).apply {
                topMargin = dp(8)
                bottomMargin = dp(8)
            }
        )
    }

    private fun makeLinkButton(label: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            textSize = 14f
            typeface = medium
            setTextColor(blue)
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setOnClickListener { onClick() }
        }
    }

    private fun makeCard(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBackground(Color.WHITE, 20)
            elevation = dp(2).toFloat()
            setPadding(dp(16), dp(14), dp(16), dp(10))
        }
    }

    private fun addCard(layout: LinearLayout, card: View) {
        layout.addView(
            card,
            LinearLayout.LayoutParams(matchParent, wrapContent).apply {
                topMargin = dp(12)
            }
        )
    }

    private fun addMenuRow(
        layout: LinearLayout,
        emoji: String,
        title: String,
        subtitle: String,
        onClick: () -> Unit
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            background = withRipple(roundedBackground(Color.WHITE, 20), 20)
            elevation = dp(2).toFloat()
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setOnClickListener { onClick() }
        }

        row.addView(
            TextView(this).apply {
                text = emoji
                textSize = 24f
                gravity = Gravity.CENTER
            },
            LinearLayout.LayoutParams(dp(40), wrapContent)
        )

        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
        }

        texts.addView(TextView(this).apply {
            text = title
            textSize = 16f
            typeface = medium
            setTextColor(textDark)
        })

        texts.addView(TextView(this).apply {
            text = subtitle
            textSize = 13f
            setTextColor(textMuted)
        })

        row.addView(texts, LinearLayout.LayoutParams(0, wrapContent, 1f))

        row.addView(TextView(this).apply {
            text = "›"
            textSize = 26f
            setTextColor(textMuted)
        })

        layout.addView(
            row,
            LinearLayout.LayoutParams(matchParent, wrapContent).apply {
                topMargin = dp(10)
            }
        )
    }

    private fun styleDialog(dialog: AlertDialog) {
        dialog.window?.setBackgroundDrawable(roundedBackground(Color.WHITE, 24))

        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
            isAllCaps = false
            setTextColor(blue)
            typeface = medium
        }

        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.apply {
            isAllCaps = false
            setTextColor(textMuted)
        }
    }

    private fun makeList(header: View): ListView {
        return ListView(this).apply {
            divider = null
            dividerHeight = 0
            selector = ColorDrawable(Color.TRANSPARENT)
            isVerticalScrollBarEnabled = false
            setBackgroundColor(paleBlue)
            addHeaderView(header, null, false)
        }
    }

    private fun makeTimerCard(remainingMs: Long): Triple<View, TextView, ProgressBar> {
        val limit = Usage.limitMs(prefs).coerceAtLeast(1L)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(18), dp(24), dp(20))
            background = roundedBackground(blue, 24)
            elevation = dp(4).toFloat()
        }

        card.addView(TextView(this).apply {
            text = "TIME LEFT TODAY"
            textSize = 11f
            typeface = medium
            letterSpacing = 0.12f
            setTextColor(Color.argb(180, 255, 255, 255))
        })

        val timeView = TextView(this).apply {
            text = formatRemaining(remainingMs)
            textSize = 40f
            typeface = Typeface.DEFAULT_BOLD
            fontFeatureSettings = "tnum"
            setTextColor(Color.WHITE)
            setPadding(0, dp(2), 0, dp(10))
        }
        card.addView(timeView)

        val bar = ProgressBar(
            this, null, android.R.attr.progressBarStyleHorizontal
        ).apply {
            max = 1000
            progress = (remainingMs * 1000L / limit).toInt().coerceIn(0, 1000)
            progressTintList = ColorStateList.valueOf(Color.WHITE)
            progressBackgroundTintList = ColorStateList.valueOf(Color.argb(70, 255, 255, 255))
        }

        card.addView(bar, LinearLayout.LayoutParams(matchParent, dp(8)))

        return Triple(card, timeView, bar)
    }

    private fun formatRemaining(ms: Long): String {
        val totalSeconds = (ms + 999L) / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L

        return if (hours > 0L) {
            String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
        }
    }

    private fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000L

        return String.format(
            Locale.getDefault(), "%d:%02d", totalSeconds / 60L, totalSeconds % 60L
        )
    }

    // ---------------------------------------------------------------
    // Approved content
    // ---------------------------------------------------------------

    private fun approvedKeys(): Set<String> {
        return prefs.getStringSet("approved", emptySet())?.toSet() ?: emptySet()
    }

    private fun approvedTracks(video: Boolean): List<Track> {
        val keys = approvedKeys()
        val source = if (video) allVideo else allAudio

        return source.filter { keys.contains(it.key) }
    }

    // ---------------------------------------------------------------
    // Child home
    // ---------------------------------------------------------------

    private fun makeTab(label: String, selected: Boolean, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            textSize = 14f
            typeface = medium
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setPadding(dp(8), dp(12), dp(8), dp(12))
            setTextColor(if (selected) Color.WHITE else blue)
            background = withRipple(
                roundedBackground(if (selected) blue else blueSoft, 14), 14
            )
            setOnClickListener { onClick() }
        }
    }

    private fun makeTrackRow(track: Track, playing: Boolean, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            isClickable = true
            isFocusable = true
            background = withRipple(
                roundedBackground(if (playing) blueSoft else Color.WHITE, 16), 16
            )
            setOnClickListener { onClick() }
        }

        row.addView(
            TextView(this).apply {
                text = if (track.isVideo) "🎬" else "🎵"
                textSize = 22f
                gravity = Gravity.CENTER
                background = roundedBackground(paleBlue, 12)
            },
            LinearLayout.LayoutParams(dp(44), dp(44))
        )

        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, dp(8), 0)
        }

        texts.addView(TextView(this).apply {
            text = track.title
            textSize = 16f
            typeface = medium
            setTextColor(textDark)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })

        val duration = formatDuration(track.durationMs)

        texts.addView(TextView(this).apply {
            text = if (track.artist.isBlank()) duration else "${track.artist} · $duration"
            textSize = 12f
            setTextColor(textMuted)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })

        row.addView(texts, LinearLayout.LayoutParams(0, wrapContent, 1f))

        if (playing) {
            row.addView(TextView(this).apply {
                text = "♪"
                textSize = 20f
                setTextColor(blue)
            })
        }

        val wrap = LinearLayout(this).apply {
            setPadding(dp(16), dp(4), dp(16), dp(4))
        }

        wrap.addView(row, LinearLayout.LayoutParams(matchParent, wrapContent))

        return wrap
    }

    private fun buildMiniPlayer(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(8), dp(8))
            setBackgroundColor(Color.WHITE)
            elevation = dp(8).toFloat()
            visibility = View.GONE
        }

        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val title = TextView(this).apply {
            textSize = 15f
            typeface = medium
            setTextColor(textDark)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

        val artist = TextView(this).apply {
            textSize = 12f
            setTextColor(textMuted)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

        texts.addView(title)
        texts.addView(artist)
        box.addView(texts, LinearLayout.LayoutParams(0, wrapContent, 1f))

        fun control(label: String, size: Float, onClick: () -> Unit): TextView {
            val view = TextView(this).apply {
                text = label
                textSize = size
                gravity = Gravity.CENTER
                setTextColor(blue)
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
            }

            box.addView(view, LinearLayout.LayoutParams(dp(44), dp(44)))
            return view
        }

        miniShuffle = control("🔀", 18f) {
            val svc = service

            if (svc != null) {
                svc.shuffle = !svc.shuffle
                onPlayerChanged()
            }
        }

        control("⏮", 20f) { service?.previous() }
        miniPlay = control("▶", 22f) { service?.toggle() }
        control("⏭", 20f) { service?.next() }

        miniBox = box
        miniTitle = title
        miniArtist = artist

        return box
    }

    private fun onPlayerChanged() {
        if (screen != "home") return

        val svc = service ?: return
        val box = miniBox ?: return
        val now = svc.current

        if (now == null || Usage.remainingMs(prefs) <= 0L) {
            box.visibility = View.GONE
        } else {
            box.visibility = View.VISIBLE
            miniTitle?.text = now.title
            miniArtist?.text = if (now.artist.isBlank()) "Linda Play" else now.artist
            miniPlay?.text = if (svc.isPlaying) "⏸" else "▶"
            miniShuffle?.alpha = if (svc.shuffle) 1f else 0.4f
        }

        homeAdapter?.notifyDataSetChanged()

        // Time ran out while a song was playing: show the break screen
        if (Usage.remainingMs(prefs) <= 0L && !homeBreak) showHome()
    }

    private fun playFrom(items: List<Track>, position: Int) {
        val svc = service

        if (svc == null) {
            Toast.makeText(
                this, "The player is starting. Try again.", Toast.LENGTH_SHORT
            ).show()
        } else {
            svc.playQueue(items, position)
        }
    }

    private fun openVideo(track: Track) {
        startActivity(
            Intent(this, VideoActivity::class.java)
                .putExtra(VideoActivity.EXTRA_URI, track.uri.toString())
                .putExtra(VideoActivity.EXTRA_TITLE, track.title)
        )
    }

    private fun showHome() {
        beginScreen("home")

        val remaining = Usage.remainingMs(prefs)
        homeBreak = remaining <= 0L

        val header = makeLayout()
        addBrandHeader(header, appName, "Music and videos chosen by your parent")

        val (timerCard, timeView, bar) = makeTimerCard(remaining)

        header.addView(
            timerCard,
            LinearLayout.LayoutParams(matchParent, wrapContent).apply {
                bottomMargin = dp(12)
            }
        )

        val audio = approvedTracks(false)
        val video = approvedTracks(true)

        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        tabs.addView(
            makeTab("🎵 Music (${audio.size})", tab == 0) {
                tab = 0
                showHome()
            },
            LinearLayout.LayoutParams(0, wrapContent, 1f).apply { rightMargin = dp(6) }
        )

        tabs.addView(
            makeTab("🎬 Videos (${video.size})", tab == 1) {
                tab = 1
                showHome()
            },
            LinearLayout.LayoutParams(0, wrapContent, 1f).apply { leftMargin = dp(6) }
        )

        header.addView(tabs)

        val items = if (tab == 0) audio else video

        if (homeBreak) {
            addNoticeCard(
                header, "🌙", "Break time!",
                "Your time is done for today. Ask a parent if you need more.",
                green
            )
        } else if (items.isEmpty()) {
            addNoticeCard(
                header,
                if (tab == 0) "🎵" else "🎬",
                "Nothing here yet",
                "Ask your parent to choose some ${if (tab == 0) "songs" else "videos"} for you."
            )
        }

        val shown = if (homeBreak) emptyList() else items

        val list = makeList(header)

        val adapter = TrackAdapter(shown) { track ->
            val position = shown.indexOf(track)
            val playing = !track.isVideo && service?.current?.key == track.key

            makeTrackRow(track, playing) {
                if (track.isVideo) openVideo(track) else playFrom(shown, position)
            }
        }

        list.adapter = adapter
        homeAdapter = adapter

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(paleBlue)
        }

        root.addView(list, LinearLayout.LayoutParams(matchParent, 0, 1f))
        root.addView(buildMiniPlayer(), LinearLayout.LayoutParams(matchParent, wrapContent))

        val footer = makeFooter()

        addButton(footer, "Parent area 🔒", filled = false) {
            openParent()
        }

        addFooterLabels(footer, false)
        root.addView(footer, LinearLayout.LayoutParams(matchParent, wrapContent))

        setContentView(root)

        onPlayerChanged()
        startHomeTick(timeView, bar)
    }

    private fun startHomeTick(timeView: TextView, bar: ProgressBar) {
        stopHomeTick()

        val limit = Usage.limitMs(prefs).coerceAtLeast(1L)

        val runnable = object : Runnable {
            override fun run() {
                val remaining = Usage.remainingMs(prefs)

                if (remaining <= 0L && !homeBreak) {
                    tickRunnable = null
                    showHome()
                    return
                }

                timeView.text = formatRemaining(remaining)
                bar.progress = (remaining * 1000L / limit).toInt().coerceIn(0, 1000)
                handler.postDelayed(this, 1000L)
            }
        }

        tickRunnable = runnable
        handler.postDelayed(runnable, 1000L)
    }

    private fun stopHomeTick() {
        tickRunnable?.let { handler.removeCallbacks(it) }
        tickRunnable = null
    }

    private fun showPermissionScreen() {
        beginScreen("other")

        val layout = makeLayout()
        addBrandHeader(layout, appName, "Music and videos chosen by your parent")

        addNoticeCard(
            layout, "🎧", "Allow access",
            "Linda Play needs to see the music and videos on this phone, so a parent can choose what the child may play."
        )

        val footer = makeFooter()

        addButton(footer, "Allow access") {
            if (Build.VERSION.SDK_INT >= 23) {
                requestPermissions(
                    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 1
                )
            }
        }

        showScrollable(layout, footer, false)
    }

    private fun showLocked() {
        beginScreen("locked")

        val layout = makeLayout()
        addBrandHeader(layout, appName, "Music and videos chosen by your parent")

        addNoticeCard(
            layout, "🔒", "Subscription needed",
            "Ask a parent to subscribe, then you can listen and watch again.",
            amber
        )

        val footer = makeFooter()

        addButton(footer, "Subscribe or check again") {
            gate.check(onDenied = { showLocked() }) {
                onSubscriptionOk()
            }
        }

        showScrollable(layout, footer, false)
    }

    // ---------------------------------------------------------------
    // Parent dashboard
    // ---------------------------------------------------------------

    private fun openParent() {
        if (prefs.getString("pin_hash", null) == null) {
            createPin()
        } else {
            withPin("Enter Parent PIN") {
                showDashboard()
            }
        }
    }

    private fun showDashboard() {
        // Look for an update once, quietly, the first time the dashboard opens
        if (!updateChecked) {
            updateChecked = true
            runUpdateCheck(false)
        }

        beginScreen("dashboard")

        val layout = makeLayout()
        addTitle(layout, "Parent Dashboard")
        addSubtitle(layout, "Choose what your child can play, and for how long.")

        val limitMinutes = prefs.getInt("limit_minutes", 60)
        val usedMinutes = (Usage.usedMs(prefs) / 60_000L).toInt()
        val keys = approvedKeys()
        val songCount = keys.count { it.startsWith("a:") }
        val videoCount = keys.count { it.startsWith("v:") }

        val summary = makeCard()

        summary.addView(TextView(this).apply {
            text = "Today"
            textSize = 13f
            setTextColor(textMuted)
        })

        summary.addView(TextView(this).apply {
            text = "$usedMinutes of $limitMinutes minutes used"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(blue)
            setPadding(0, dp(2), 0, dp(8))
        })

        summary.addView(
            ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = limitMinutes.coerceAtLeast(1)
                progress = usedMinutes.coerceAtMost(max)
                progressTintList = ColorStateList.valueOf(blue)
                progressBackgroundTintList = ColorStateList.valueOf(blueSoft)
            },
            LinearLayout.LayoutParams(matchParent, dp(8)).apply {
                bottomMargin = dp(8)
            }
        )

        addCard(layout, summary)

        addMenuRow(
            layout, "🎵", "Songs",
            "$songCount chosen · ${allAudio.size} on this phone"
        ) { showPicker(false) }

        addMenuRow(
            layout, "🎬", "Videos",
            "$videoCount chosen · ${allVideo.size} on this phone"
        ) { showPicker(true) }

        addMenuRow(
            layout, "⏱", "Daily time",
            "$limitMinutes minutes per day"
        ) { showLimitDialog() }

        addMenuRow(
            layout, "🔐", "Kids lock",
            if (kidsLockOn()) "On · child cannot leave Linda Play" else "Off · child can use other apps"
        ) {
            prefs.edit().putBoolean("kids_lock", !kidsLockOn()).apply()
            showDashboard()
        }

        addMenuRow(
            layout, "🔒", "Change parent PIN", "Choose a new 4–8 digit PIN"
        ) {
            withPin("Enter Current PIN") { createPin() }
        }

        val sub = gate.lastStatus
        val subText = if (sub == null) {
            "Tap to check your plan"
        } else if (sub.active) {
            val days = ((sub.expiresAt - System.currentTimeMillis()) / 86_400_000L)
                .coerceAtLeast(0L)

            (if (sub.plan == "trial") "Free trial" else "Subscribed") + " · $days days left"
        } else {
            "Not active · tap to subscribe"
        }

        addMenuRow(layout, "💳", "Subscription", subText) {
            gate.check {
                Toast.makeText(
                    this, "Subscription is active", Toast.LENGTH_SHORT
                ).show()
                showDashboard()
            }
        }

        addMenuRow(layout, "⬆", "App updates", updateNote) { onUpdateRowClicked() }

        val footer = makeFooter()

        addButton(footer, "Back to $appName") {
            showHome()
        }

        showScrollable(layout, footer)
    }

    private fun showPicker(video: Boolean) {
        showLoading(if (video) "Looking for videos…" else "Looking for songs…")
        loadLibrary { renderPicker(video) }
    }

    private fun makePickRow(
        track: Track,
        checkedNow: Boolean,
        onChange: (Boolean) -> Unit
    ): View {
        val toggle = Switch(this).apply {
            isChecked = checkedNow

            if (Build.VERSION.SDK_INT >= 23) {
                thumbTintList = ColorStateList.valueOf(Color.WHITE)
                trackTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(green, Color.rgb(190, 198, 212))
                )
            }

            setOnCheckedChangeListener { _, on -> onChange(on) }
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            isClickable = true
            isFocusable = true
            background = withRipple(roundedBackground(Color.WHITE, 16), 16)
            setOnClickListener { toggle.toggle() }
        }

        row.addView(TextView(this).apply {
            text = if (track.isVideo) "🎬" else "🎵"
            textSize = 20f
        })

        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
        }

        texts.addView(TextView(this).apply {
            text = track.title
            textSize = 15f
            typeface = medium
            setTextColor(textDark)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })

        val duration = formatDuration(track.durationMs)

        texts.addView(TextView(this).apply {
            text = if (track.artist.isBlank()) duration else "${track.artist} · $duration"
            textSize = 12f
            setTextColor(textMuted)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })

        row.addView(texts, LinearLayout.LayoutParams(0, wrapContent, 1f))
        row.addView(toggle)

        val wrap = LinearLayout(this).apply {
            setPadding(dp(16), dp(3), dp(16), dp(3))
        }

        wrap.addView(row, LinearLayout.LayoutParams(matchParent, wrapContent))

        return wrap
    }

    private fun renderPicker(video: Boolean) {
        beginScreen("picker")

        val all = if (video) allVideo else allAudio
        val prefix = if (video) "v:" else "a:"
        val checked = approvedKeys().filter { it.startsWith(prefix) }.toMutableSet()

        val header = makeLayout()
        addTitle(header, if (video) "Choose videos" else "Choose songs")
        addSubtitle(
            header,
            "Switch on what your child may ${if (video) "watch" else "listen to"}."
        )

        if (all.isEmpty()) {
            addNoticeCard(
                header, "🔎", "Nothing found",
                "No ${if (video) "videos" else "songs"} were found on this phone."
            )
        }

        val search = EditText(this).apply {
            hint = "Search"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
            textSize = 15f
            setTextColor(textDark)
            setHintTextColor(Color.rgb(160, 170, 190))
            background = roundedBackground(Color.WHITE, 14)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }

        header.addView(search, LinearLayout.LayoutParams(matchParent, wrapContent))

        val counter = TextView(this).apply {
            textSize = 13f
            setTextColor(textMuted)
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(2))
        }

        val updateCounter = { counter.text = "${checked.size} chosen" }
        updateCounter()
        header.addView(counter)

        val list = makeList(header)

        val adapter = TrackAdapter(all) { track ->
            makePickRow(track, checked.contains(track.key)) { on ->
                if (on) checked.add(track.key) else checked.remove(track.key)
                updateCounter()
            }
        }

        val quick = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        quick.addView(makeLinkButton("Select all shown") {
            adapter.items.forEach { checked.add(it.key) }
            updateCounter()
            adapter.notifyDataSetChanged()
        })

        quick.addView(makeLinkButton("Clear all shown") {
            adapter.items.forEach { checked.remove(it.key) }
            updateCounter()
            adapter.notifyDataSetChanged()
        })

        header.addView(quick)

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}

            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}

            override fun afterTextChanged(s: Editable?) {
                val q = s.toString().trim().lowercase()

                adapter.items = if (q.isEmpty()) {
                    all
                } else {
                    all.filter { "${it.title} ${it.artist}".lowercase().contains(q) }
                }

                adapter.notifyDataSetChanged()
            }
        })

        list.adapter = adapter

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(paleBlue)
        }

        root.addView(list, LinearLayout.LayoutParams(matchParent, 0, 1f))

        val footer = makeFooter()

        addButton(footer, "Save") {
            val others = approvedKeys().filter { !it.startsWith(prefix) }

            prefs.edit()
                .putStringSet("approved", (others + checked).toSet())
                .apply()

            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
            showDashboard()
        }

        addButton(footer, "Cancel", filled = false) {
            showDashboard()
        }

        addFooterLabels(footer, true)
        root.addView(footer, LinearLayout.LayoutParams(matchParent, wrapContent))

        setContentView(root)
    }

    private fun showLimitDialog() {
        val labels = arrayOf(
            "30 minutes", "1 hour", "1 hour 30 minutes", "2 hours", "3 hours", "Custom…"
        )
        val values = intArrayOf(30, 60, 90, 120, 180, -1)

        AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
            .setTitle("Daily time")
            .setItems(labels) { _, which ->
                val chosen = values[which]

                if (chosen == -1) showCustomLimitDialog() else saveLimit(chosen)
            }
            .setNegativeButton("Cancel", null)
            .create().also { dialog ->
                dialog.setOnShowListener { styleDialog(dialog) }
            }.show()
    }

    private fun saveLimit(minutes: Int) {
        prefs.edit().putInt("limit_minutes", minutes).apply()

        Toast.makeText(
            this, "Daily time saved: $minutes minutes", Toast.LENGTH_SHORT
        ).show()

        showDashboard()
    }

    private fun showCustomLimitDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "Minutes per day"
            setText(prefs.getInt("limit_minutes", 60).toString())
        }

        AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
            .setTitle("Custom daily time")
            .setMessage("Choose a daily allowance in minutes.")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val minutes = input.text.toString().toIntOrNull()

                if (minutes == null || minutes !in 1..1440) {
                    Toast.makeText(
                        this, "Enter a number from 1 to 1440", Toast.LENGTH_LONG
                    ).show()
                } else {
                    saveLimit(minutes)
                }
            }
            .create().also { dialog ->
                dialog.setOnShowListener { styleDialog(dialog) }
            }.show()
    }

    // ---------------------------------------------------------------
    // App updates
    // ---------------------------------------------------------------

    private fun runUpdateCheck(manual: Boolean) {
        if (updateChecking) return
        updateChecking = true

        if (manual) {
            Toast.makeText(this, "Checking for updates…", Toast.LENGTH_SHORT).show()
        }

        Thread {
            var found: UpdateChecker.Update? = null
            var failed = false

            try {
                found = UpdateChecker.fetchLatest()
            } catch (e: Exception) {
                failed = true
            }

            val latest = found
            val installed = UpdateChecker.installedVersion(this)

            runOnUiThread {
                updateChecking = false

                if (!isFinishing && !isDestroyed) {
                    if (failed) {
                        updateNote = "Could not check · tap to retry"
                    } else if (
                        latest != null &&
                        UpdateChecker.isNewer(latest.version, installed)
                    ) {
                        availableUpdate = latest
                        updateNote = "Update available: v${latest.version}"
                    } else {
                        availableUpdate = null
                        updateNote = "Up to date (v$installed)"
                    }

                    if (screen == "dashboard") showDashboard()
                }
            }
        }.start()
    }

    private fun onUpdateRowClicked() {
        val update = availableUpdate

        if (update == null) {
            runUpdateCheck(true)
            return
        }

        val notes = if (update.notes.isBlank()) "No notes." else update.notes

        AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
            .setTitle("Update to v${update.version}?")
            .setMessage(
                "$notes\n\nThe download starts now. When it finishes, " +
                    "the installer opens. Tap Install."
            )
            .setNegativeButton("Later", null)
            .setPositiveButton("Download") { _, _ ->
                startUpdateDownload(update)
            }
            .create().also { dialog ->
                dialog.setOnShowListener { styleDialog(dialog) }
            }.show()
    }

    private fun startUpdateDownload(update: UpdateChecker.Update) {
        Toast.makeText(this, "Getting the download link…", Toast.LENGTH_SHORT).show()

        Thread {
            var link: String? = null

            try {
                link = UpdateChecker.requestDownloadUrl(update.docId)
            } catch (e: Exception) {
                link = null
            }

            val url = link

            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread

                if (url == null) {
                    Toast.makeText(
                        this,
                        "Could not get the download link. Try again.",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    enqueueUpdateDownload(url, update.version)
                }
            }
        }.start()
    }

    private fun enqueueUpdateDownload(url: String, version: String) {
        try {
            val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle("$appName update")
                .setDescription("Version $version")
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                .setDestinationInExternalFilesDir(
                    this, Environment.DIRECTORY_DOWNLOADS, "LindaPlay-$version.apk"
                )

            updateDownloadId = manager.enqueue(request)

            Toast.makeText(this, "Downloading the update…", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Could not start the download.", Toast.LENGTH_LONG).show()
        }
    }

    private fun installDownloaded(id: Long) {
        try {
            val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

            val cursor = manager.query(DownloadManager.Query().setFilterById(id))
            var ok = false

            cursor.use { c ->
                if (c.moveToFirst()) {
                    val column = c.getColumnIndex(DownloadManager.COLUMN_STATUS)

                    ok = column >= 0 &&
                        c.getInt(column) == DownloadManager.STATUS_SUCCESSFUL
                }
            }

            if (!ok) {
                Toast.makeText(
                    this, "The update did not finish downloading.", Toast.LENGTH_LONG
                ).show()
                return
            }

            val uri = manager.getUriForDownloadedFile(id) ?: return

            startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_ACTIVITY_NEW_TASK
                    )
            )
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Open the downloaded file from your notifications to install it.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // ---------------------------------------------------------------
    // PIN
    // ---------------------------------------------------------------

    private val pinIterations = 10000

    // Asks for the PIN, checks it in the background, then runs onVerified
    private fun withPin(title: String, onVerified: () -> Unit) {
        askForPin(title) { pin ->
            Thread {
                val ok = verifyPin(pin)

                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        if (ok) {
                            onVerified()
                        } else {
                            Toast.makeText(
                                this, "Incorrect PIN", Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }.start()
        }
    }

    private fun askForPin(title: String, onSubmit: (String) -> Unit) {
        val input = EditText(this).apply {
            hint = "••••"
            inputType = InputType.TYPE_CLASS_NUMBER or
                InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(8))
            gravity = Gravity.CENTER
            textSize = 26f
            letterSpacing = 0.3f
            setTextColor(textDark)
            setHintTextColor(Color.rgb(190, 198, 212))
            background = roundedBackground(paleBlue, 14)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }

        val container = LinearLayout(this).apply {
            setPadding(dp(24), dp(8), dp(24), dp(4))
            addView(input, LinearLayout.LayoutParams(matchParent, wrapContent))
        }

        val dialog = AlertDialog.Builder(
            this, android.R.style.Theme_Material_Light_Dialog_Alert
        )
            .setTitle(title)
            .setMessage("Enter your 4–8 digit PIN")
            .setView(container)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Continue", null)
            .create()

        dialog.setOnShowListener {
            styleDialog(dialog)

            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pin = input.text.toString()

                if (!pin.matches(Regex("[0-9]{4,8}"))) {
                    input.error = "Use 4–8 digits"
                    return@setOnClickListener
                }

                dialog.dismiss()
                onSubmit(pin)
            }
        }

        dialog.window?.setSoftInputMode(
            android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        )
        dialog.show()
    }

    private fun createPin() {
        askForPin("Create Parent PIN") { first ->
            askForPin("Confirm Parent PIN") { second ->
                if (first != second) {
                    Toast.makeText(
                        this, "PINs do not match. Try again.", Toast.LENGTH_LONG
                    ).show()
                    createPin()
                } else {
                    Thread {
                        savePin(first)

                        runOnUiThread {
                            if (!isFinishing && !isDestroyed) {
                                Toast.makeText(
                                    this, "Parent PIN saved", Toast.LENGTH_SHORT
                                ).show()
                                showDashboard()
                            }
                        }
                    }.start()
                }
            }
        }
    }

    private fun savePin(pin: String) {
        val salt = ByteArray(16)
        SecureRandom().nextBytes(salt)

        val hash = hashPin(pin, salt)

        prefs.edit()
            .putString("pin_salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString("pin_hash", Base64.encodeToString(hash, Base64.NO_WRAP))
            .apply()
    }

    private fun hashPin(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, pinIterations, 256)

        return try {
            SecretKeyFactory
                .getInstance("PBKDF2WithHmacSHA1")
                .generateSecret(spec)
                .encoded
        } finally {
            spec.clearPassword()
        }
    }

    // Runs on a background thread. Never call it from the main thread.
    private fun verifyPin(pin: String): Boolean {
        return try {
            val saltText = prefs.getString("pin_salt", null) ?: return false
            val hashText = prefs.getString("pin_hash", null) ?: return false

            val salt = Base64.decode(saltText, Base64.NO_WRAP)
            val savedHash = Base64.decode(hashText, Base64.NO_WRAP)

            MessageDigest.isEqual(savedHash, hashPin(pin, salt))
        } catch (e: Exception) {
            false
        }
    }
}
