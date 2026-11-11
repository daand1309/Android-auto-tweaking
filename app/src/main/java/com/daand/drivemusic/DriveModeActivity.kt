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
            updateDynamic()
            handler.postDelayed(this, 3000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DriveLog.init(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(buildUi())
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
            setTextColor(Color.parseColor("#9E9E9E")); textSize = 16f; gravity = Gravity.CENTER
        }
        root.addView(nowPlaying, lp(weight = 2f))
        root.addView(status, lp())

        // Big transport row
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        controls.addView(bigButton("⏮") { svc()?.skipPrevious(); flash() }, lp(weight = 1f, height = dp(130)))
        playBtn = bigButton("▶") { svc()?.playPause(); handler.postDelayed({ updateDynamic() }, 300) }
        controls.addView(playBtn, lp(weight = 1.3f, height = dp(130)))
        controls.addView(bigButton("⏭") { svc()?.skipNext(); flash() }, lp(weight = 1f, height = dp(130)))
        root.addView(controls, lp(weight = 3f))

        // Bottom row
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        bottom.addView(smallButton("🔊 Welcome") {
            WelcomeAnnouncer.run(this, svc()?.currentSong())
        }, lp(weight = 1f, height = dp(80)))
        resumeBtn = smallButton("") {
            val p = Prefs(this)
            p.autoResume = !p.autoResume
            if (p.autoResume) svc()?.resumeOffThisDrive = false
            updateDynamic()
        }
        bottom.addView(resumeBtn, lp(weight = 1f, height = dp(80)))
        bottom.addView(smallButton("✕ Close") { finish() }, lp(weight = 0.7f, height = dp(80)))
        root.addView(bottom, lp(weight = 2f))

        return root
    }

    private fun updateDynamic() {
        val s = svc()
        val song = s?.currentSong()
        nowPlaying.text = song?.let { "${it.title}${if (it.artist.isNotBlank()) "\n${it.artist}" else ""}" }
            ?: if (s == null) "Monitor not running" else "Nothing playing"
        status.text = if (s == null) "Open the app and grant Notification access" else Prefs(this).lastCarState
        val playing = s?.isPlaying() == true
        playBtn.text = if (playing) "⏸" else "▶"
        val resumeOn = Prefs(this).autoResume
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

    private fun lp(weight: Float = 0f, height: Int = LinearLayout.LayoutParams.WRAP_CONTENT) =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, if (weight > 0) 0 else height, weight).apply {
            val m = dp(6); setMargins(m, m, m, m)
        }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
