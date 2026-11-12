package com.daand.drivemusic

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Big-button, full-screen dashboard for the phone in its holder. Large touch targets, high
 * contrast, stays awake, hides the system bars. Controls the music via the running service.
 */
@SuppressLint("SetTextI18n")
class DriveModeActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var nowPlaying: TextView
    private lateinit var status: TextView
    private lateinit var playBtn: Button
    private lateinit var resumeBtn: Button

    private val tick = object : Runnable {
        override fun run() {
            if (closeIfLeftCar()) return
            updateDynamic()
            handler.postDelayed(this, 3000)
        }
    }

    /** True once the car has been connected while this screen was open. */
    private var sawCarConnected = false

    /**
     * Closes Drive Mode when you leave the car - otherwise it would keep the screen on (even over the
     * lock screen) in your pocket. Opened by hand while parked? Then it stays until you close it.
     */
    private fun closeIfLeftCar(): Boolean {
        val connected = Prefs(this).lastCarState.let { it == "Android Auto connected" || it == "Android Automotive" }
        if (connected) { sawCarConnected = true; return false }
        if (!sawCarConnected) return false
        DriveLog.log("Drive Mode: closed - you left the car")
        finish()
        return true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DriveLog.init(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Opened: the "tap to open" banner isn't needed any more.
        getSystemService(android.app.NotificationManager::class.java).cancel(DriveMonitorService.DRIVE_MODE_NOTIFICATION)
        val locked = getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked
        DriveLog.log("Drive Mode: opened" + if (locked) " over the lock screen" else "")
        val frame = android.widget.FrameLayout(this).apply { setBackgroundColor(Style.BG) }
        // MATCH_PARENT so the weighted button rows get real height (not collapsed to zero).
        frame.addView(buildUi(), android.widget.FrameLayout.LayoutParams(-1, -1))
        setContentView(frame)
        if (Prefs(this).showSplash) playIntro(frame)
    }

    /** Original animated intro: the car name scales in with an accent sweep, then fades away.
     *  Deliberately not a manufacturer emblem - just a clean custom motion piece. */
    private fun playIntro(frame: android.widget.FrameLayout) {
        val name = Prefs(this).carName.ifBlank { "Drive" }
        val overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Style.BG)
            isClickable = true
        }
        val bar = View(this).apply {
            background = Style.rounded(Style.PRIMARY, dp(3).toFloat())
            layoutParams = LinearLayout.LayoutParams(0, dp(5)).apply { topMargin = dp(14) }
        }
        val nameTv = TextView(this).apply {
            text = name.uppercase(); setTextColor(Color.WHITE); textSize = 56f
            setTypeface(typeface, Typeface.BOLD); letterSpacing = 0.2f
            alpha = 0f; scaleX = 0.8f; scaleY = 0.8f
        }
        val sub = TextView(this).apply {
            text = "DRIVE MODE"; setTextColor(Style.TEXT2); textSize = 14f
            letterSpacing = 0.4f; alpha = 0f; setPadding(0, dp(10), 0, 0)
        }
        // Your own intro image (e.g. the car badge) replaces the text when one is set.
        val logo = IntroImage.load(this)
        val hero: View = if (logo != null) android.widget.ImageView(this).apply {
            setImageBitmap(logo)
            adjustViewBounds = true
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            alpha = 0f; scaleX = 0.8f; scaleY = 0.8f
            val side = (minOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels) * 0.75).toInt()
            layoutParams = LinearLayout.LayoutParams(side, LinearLayout.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER }
            maxHeight = side
        } else nameTv
        overlay.addView(hero)
        overlay.addView(bar)
        overlay.addView(sub)
        frame.addView(overlay, android.widget.FrameLayout.LayoutParams(-1, -1))

        val remove = Runnable {
            overlay.animate().alpha(0f).setDuration(400).withEndAction {
                (overlay.parent as? android.widget.FrameLayout)?.removeView(overlay)
            }.start()
        }
        // Skip on tap - only cancel the intro's own timer, not the screen's refresh.
        overlay.setOnClickListener { handler.removeCallbacks(remove); remove.run() }

        hero.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(if (logo != null) 650 else 420).start()
        sub.animate().alpha(1f).setStartDelay(250).setDuration(400).start()
        val fullW = resources.displayMetrics.widthPixels / 3
        android.animation.ValueAnimator.ofInt(0, fullW).apply {
            duration = 520; startDelay = 150
            addUpdateListener { bar.layoutParams = (bar.layoutParams as LinearLayout.LayoutParams).apply { width = it.animatedValue as Int }; bar.requestLayout() }
            start()
        }
        handler.postDelayed(remove, if (logo != null) 2300 else 1700)
    }

    override fun onResume() {
        super.onResume()
        goImmersive()
        handler.post(tick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goImmersive()
    }

    private fun goImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun svc() = DriveMonitorService.instance

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Style.BG)
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }

        nowPlaying = TextView(this).apply {
            setTextColor(Style.TEXT); textSize = 30f; setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER; maxLines = 3
        }
        status = TextView(this).apply {
            setTextColor(Style.TEXT2); textSize = 16f; gravity = Gravity.CENTER
        }
        root.addView(nowPlaying, vLp(weight = 2f))
        root.addView(status, vLp())

        // Big transport row (horizontal: weight drives WIDTH, height fills the row).
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        controls.addView(bigButton("⏮") { svc()?.skipPrevious(); flash() }, hLp(1f))
        playBtn = bigButton("▶") { svc()?.playPause(); handler.postDelayed({ updateDynamic() }, 300) }
        controls.addView(playBtn, hLp(1.3f))
        controls.addView(bigButton("⏭") { svc()?.skipNext(); flash() }, hLp(1f))
        root.addView(controls, vLp(weight = 3f))

        // Bottom row
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        bottom.addView(smallButton("🔊 Welcome") {
            WelcomeAnnouncer.run(this, svc()?.currentSong(), recordAsGreeted = false)
        }, hLp(1f))
        resumeBtn = smallButton("") {
            val p = Prefs(this)
            p.autoResume = !p.autoResume
            if (p.autoResume) svc()?.resumeOffThisDrive = false
            updateDynamic()
        }
        bottom.addView(resumeBtn, hLp(1f))
        bottom.addView(smallButton("✕ Close") { finish() }, hLp(0.7f))
        root.addView(bottom, vLp(weight = 1.6f))

        return root
    }

    private var lastShown = ""

    /** Refreshes song / status / buttons - only touching the views when something changed (saves redraws). */
    private fun updateDynamic() {
        val s = svc()
        val prefs = Prefs(this)
        val song = s?.currentSong()
        val songText = song?.let { "${it.title}${if (it.artist.isNotBlank()) "\n${it.artist}" else ""}" }
            ?: if (s == null) "Monitor not running" else "Nothing playing"
        val statusText = if (s == null) "Open the app and grant Notification access" else prefs.lastCarState
        val playing = s?.isPlaying() == true
        val resumeOn = prefs.autoResume
        val key = "$songText|$statusText|$playing|$resumeOn"
        if (key == lastShown) return
        lastShown = key
        nowPlaying.text = songText
        status.text = statusText
        playBtn.text = if (playing) "⏸" else "▶"
        resumeBtn.text = if (resumeOn) "↻ Resume: On" else "↻ Resume: Off"
        resumeBtn.background = Style.button(if (resumeOn) Style.GREEN else Style.SURFACE2, dp(16).toFloat())
    }

    private fun flash() = handler.postDelayed({ updateDynamic() }, 400)

    private fun bigButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; textSize = 40f; isAllCaps = false
        setTextColor(Color.WHITE); background = Style.button(Style.PRIMARY, dp(20).toFloat()); stateListAnimator = null
        setOnClickListener { onClick() }
    }

    private fun smallButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; textSize = 18f; isAllCaps = false
        setTextColor(Style.TEXT); background = Style.button(Style.SURFACE2, dp(16).toFloat()); stateListAnimator = null
        setOnClickListener { onClick() }
    }

    /** Vertical child: full width, weighted height (0 so the weight controls it). */
    private fun vLp(weight: Float = 0f, height: Int = LinearLayout.LayoutParams.WRAP_CONTENT) =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, if (weight > 0) 0 else height, weight).apply {
            val m = dp(6); setMargins(m, m, m, m)
        }

    /** Horizontal child: weighted width (0 so the weight controls it), full row height. */
    private fun hLp(weight: Float) =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight).apply {
            val m = dp(6); setMargins(m, m, m, m)
        }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
