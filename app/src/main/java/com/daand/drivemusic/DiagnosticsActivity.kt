package com.daand.drivemusic

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.daand.drivemusic.obd.CarSnapshot
import com.daand.drivemusic.obd.Diagnostics
import com.daand.drivemusic.obd.Diagnostics.Step
import com.daand.drivemusic.obd.ObdManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Health checks on demand through the OBD adapter: a quick check (one full reading with the fault codes)
 * or a guided full test (parked, engine running, four steps). Only reads - the judging is in
 * [Diagnostics]; the adapter is used through ObdManager's own poll loop, never directly from here.
 */
@SuppressLint("SetTextI18n")
class DiagnosticsActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var root: LinearLayout
    private var ticker: Runnable? = null
    /** A run is on (quick or full): back asks before stopping it. */
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        DriveLog.init(this)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(6), dp(14), dp(14)) }
        val content = ScrollView(this).apply { setBackgroundColor(Style.BG); addView(root) }
        setContentView(content)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(content) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        window.statusBarColor = Style.BG
        window.navigationBarColor = Style.BG
        showStart()
    }

    override fun onDestroy() {
        stopRun()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!running) { @Suppress("DEPRECATION") super.onBackPressed(); return }
        AlertDialog.Builder(this).setTitle("Stop the check?").setMessage("Nothing is saved from an unfinished run.")
            .setPositiveButton("Stop") { _, _ -> stopRun(); showStart() }
            .setNegativeButton("Keep going", null).show()
    }

    private fun stopRun() {
        running = false
        ObdManager.diagnosing = false
        ticker?.let { handler.removeCallbacks(it) }; ticker = null
    }

    // --- Screens -----------------------------------------------------------------------------------

    private fun showStart() {
        root.removeAllViews()
        title("‹  Diagnostics") { finish() }
        val connected = ObdManager.isConnected()
        text(if (connected) "Adapter connected - ready." else
            "The adapter isn't connected. Switch the ignition on (or start the engine) with the adapter in, " +
                "and wait until Car data says Connected.", if (connected) Style.TEXT2 else Style.AMBER)
        bigButton("⚡  Quick check", "About 30 seconds · ignition on or engine running", connected) { runQuick() }
        bigButton("🔬  Full test", "About 5 minutes · parked, engine running (best when warm) · you follow 4 steps", connected) { runFull() }
        text("Read-only: the app only asks the car questions. It never clears codes or changes anything.", Style.TEXT2, 13f)
        val history = Diagnostics.decode(Prefs(this).diagResults).reversed()
        if (history.isNotEmpty()) {
            label("Earlier results")
            history.forEach { r ->
                row("${if (r.full) "Full test" else "Quick check"} · ${date(r.at)}", r.summary()) { showResult(r, saved = true) }
            }
        }
    }

    private fun runQuick() {
        if (!ObdManager.isConnected()) { alert("Adapter not connected", "Switch the ignition on with the adapter in, then try again."); showStart(); return }
        running = true
        root.removeAllViews()
        title("Quick check") {}
        val status = text("Reading the car: every value and the fault codes…", Style.TEXT)
        val startedAt = System.currentTimeMillis()
        ObdManager.diagnosing = true
        ObdManager.requestCodes()
        // Misfire counters (mode 06), read on the poll thread between readings.
        val misfires = java.util.concurrent.atomic.AtomicReference<Map<Int, Int>?>(null)
        val misfiresDone = java.util.concurrent.atomic.AtomicBoolean(false)
        if (!ObdManager.onAdapter { s -> misfires.set(Diagnostics.misfires(s.onBoardTests(MISFIRE_MIDS))); misfiresDone.set(true) })
            misfiresDone.set(true)
        tick(500) {
            val snap = ObdManager.latest
            val codesIn = snap != null && (snap.storedReadAt ?: 0) >= startedAt && (snap.codesReadAt ?: 0) >= startedAt
            val waited = System.currentTimeMillis() - startedAt
            if ((codesIn && misfiresDone.get()) || waited > 25_000) {
                stopRun()
                if (snap == null || snap.at < startedAt) { status.text = "The car didn't answer. Is the ignition on?"; addBack(); return@tick false }
                finish(Diagnostics.Result(System.currentTimeMillis(), false,
                    Diagnostics.quick(snap, ObdManager.catalyst, misfires.get()?.takeIf { it.isNotEmpty() })))
                false
            } else { status.text = "Reading the car… ${waited / 1000} s"; true }
        }
    }

    private fun runFull() {
        val snap = ObdManager.latest?.takeIf { ObdManager.isConnected() && System.currentTimeMillis() - it.at < 30_000 }
        if (snap == null || !snap.engineRunning) { alert("Start the engine first", "The full test measures the running engine."); return }
        if ((snap.speedKmh ?: 0) > 3) { alert("Park first", "The full test only runs with the car standing still."); return }
        running = true
        ObdManager.diagnosing = true
        ObdManager.requestCodes()
        DriveLog.log("DIAGNOSTICS: full test started")
        val test = Diagnostics.FullTest()
        val misfires = java.util.concurrent.atomic.AtomicReference<Map<Int, Int>?>(null)
        ObdManager.onAdapter { s -> misfires.set(Diagnostics.misfires(s.onBoardTests(MISFIRE_MIDS))) }
        runStep(test, 0) {
            val last = ObdManager.latest ?: snap
            val quick = Diagnostics.quick(last, ObdManager.catalyst, misfires.get()?.takeIf { it.isNotEmpty() })
                .filter { it.id != "trim" }   // measured per step (idle and 2500 rpm)
            val steps = test.evaluate()
            // A cold engine: the test's own temperature line says why the mixture wasn't judged - not twice.
            val merged = if (steps.any { it.id == "warm" }) quick.filter { it.id != "coolant" } + steps else quick + steps
            finish(Diagnostics.Result(System.currentTimeMillis(), true, merged))
        }
    }

    private fun runStep(test: Diagnostics.FullTest, i: Int, done: () -> Unit) {
        val steps = Step.values()
        if (i >= steps.size) { stopRun(); done(); return }
        val step = steps[i]
        root.removeAllViews()
        title("Full test · step ${i + 1} of ${steps.size}") {}
        label(step.title)
        text(step.todo, Style.TEXT, 19f)
        val count = text("", Style.PRIMARY, 40f).apply { gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD }
        val live = text("", Style.TEXT2, 16f)
        button("Stop the test", danger = true) { stopRun(); showStart() }
        val startedAt = System.currentTimeMillis()
        var lastAt = 0L
        tick(500) {
            val s = ObdManager.latest
            if (s != null && s.at != lastAt && s.at >= startedAt) {
                lastAt = s.at
                if (!test.add(step, s)) {
                    stopRun()
                    DriveLog.log("DIAGNOSTICS: full test stopped - ${test.aborted}")
                    alert("Test stopped", (test.aborted ?: "").replaceFirstChar { it.uppercase() } + ".")
                    showStart()
                    return@tick false
                }
                live.text = liveLine(s)
            }
            val left = step.seconds - ((System.currentTimeMillis() - startedAt) / 1000).toInt()
            if (left <= 0) { runStep(test, i + 1, done); false } else { count.text = "$left s"; true }
        }
    }

    private fun finish(r: Diagnostics.Result) {
        val p = Prefs(this)
        p.diagResults = Diagnostics.encode(Diagnostics.decode(p.diagResults) + r)
        DriveLog.log("DIAGNOSTICS: ${if (r.full) "full test" else "quick check"} - ${r.summary()}")
        showResult(r, saved = false)
    }

    private fun showResult(r: Diagnostics.Result, saved: Boolean) {
        root.removeAllViews()
        title("‹  ${if (r.full) "Full test" else "Quick check"}") { showStart() }
        text("${date(r.at)} · ${r.summary()}", Style.TEXT2)
        val order = listOf(Diagnostics.Verdict.BAD, Diagnostics.Verdict.WARN, Diagnostics.Verdict.OK, Diagnostics.Verdict.INFO)
        r.checks.sortedBy { order.indexOf(it.verdict) }.forEach { c -> checkRow(c) }
        if (r.checks.isEmpty()) text("Nothing could be measured - did the car answer?", Style.AMBER)
        button("Share") {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, r.text(date(r.at))), "Share the result"))
        }
        button(if (saved) "Back" else "Done") { showStart() }
    }

    // --- Pieces --------------------------------------------------------------------------------------

    private fun liveLine(s: CarSnapshot): String = listOfNotNull(
        s.rpm?.let { "$it rpm" }, s.volts?.let { "%.1f V".format(Locale.US, it) }, s.coolantC?.let { "$it °C" },
        s.extra[0x06]?.let { st -> "trim %+.0f%%".format(Locale.US, st + (s.extra[0x07] ?: 0.0)) },
    ).joinToString("   ·   ")

    /** Repeats [body] every [ms] while it returns true and a run is on. */
    private fun tick(ms: Long, body: () -> Boolean) {
        ticker?.let { handler.removeCallbacks(it) }
        val r = object : Runnable { override fun run() { if (body() && running) handler.postDelayed(this, ms) } }
        ticker = r
        handler.post(r)
    }

    private fun checkRow(c: Diagnostics.Check) {
        val color = when (c.verdict) {
            Diagnostics.Verdict.OK -> Style.GREEN; Diagnostics.Verdict.WARN -> Style.AMBER
            Diagnostics.Verdict.BAD -> Style.DANGER; Diagnostics.Verdict.INFO -> Style.TEXT2
        }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Style.rounded(Style.SURFACE, dp(Style.R_CARD).toFloat())
            setPadding(dp(14), dp(12), dp(14), dp(12))
            addView(TextView(this@DiagnosticsActivity).apply {
                text = c.verdict.mark; textSize = 22f; setTextColor(color); typeface = Typeface.DEFAULT_BOLD
                setPadding(0, 0, dp(12), 0)
            })
            addView(LinearLayout(this@DiagnosticsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@DiagnosticsActivity).apply { text = c.title; textSize = 16f; setTextColor(Style.TEXT); typeface = Typeface.DEFAULT_BOLD })
                addView(TextView(this@DiagnosticsActivity).apply { text = c.detail; textSize = 14f; setTextColor(Style.TEXT2) })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }, lp(top = 8))
    }

    private fun title(t: String, onClick: () -> Unit) = root.addView(TextView(this).apply {
        text = t; textSize = 24f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Style.TEXT)
        setPadding(0, dp(10), 0, dp(8)); setOnClickListener { onClick() }
    })

    private fun label(t: String) = root.addView(TextView(this).apply {
        text = t.uppercase(); textSize = 13f; setTextColor(Style.PRIMARY); typeface = Typeface.DEFAULT_BOLD
        letterSpacing = 0.08f; setPadding(0, dp(18), 0, dp(4))
    })

    private fun text(t: String, color: Int, size: Float = 15f) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color); setPadding(0, dp(6), 0, dp(6))
    }.also { root.addView(it) }

    private fun bigButton(t: String, sub: String, enabled: Boolean, onClick: () -> Unit) = root.addView(LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = Style.button(Style.SURFACE, dp(Style.R_BIG).toFloat())
        setPadding(dp(18), dp(16), dp(18), dp(16))
        isEnabled = enabled; alpha = if (enabled) 1f else 0.5f
        addView(TextView(this@DiagnosticsActivity).apply { text = t; textSize = 21f; setTextColor(Style.TEXT); typeface = Typeface.DEFAULT_BOLD })
        addView(TextView(this@DiagnosticsActivity).apply { text = sub; textSize = 14f; setTextColor(Style.TEXT2) })
        if (enabled) { setOnClickListener { onClick() }; Feel.press(this, 0.98f) }
    }, lp(top = 10))

    private fun row(t: String, value: String, onClick: () -> Unit) = root.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        background = Style.button(Style.SURFACE, dp(Style.R_CARD).toFloat())
        setPadding(dp(14), dp(12), dp(14), dp(12))
        addView(TextView(this@DiagnosticsActivity).apply { text = t; textSize = 15f; setTextColor(Style.TEXT) },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(TextView(this@DiagnosticsActivity).apply { text = value; textSize = 14f; setTextColor(Style.TEXT2) })
        setOnClickListener { onClick() }
    }, lp(top = 6))

    private fun button(t: String, danger: Boolean = false, onClick: () -> Unit) = root.addView(Button(this).apply {
        text = t; isAllCaps = false; textSize = 16f
        setTextColor(if (danger) android.graphics.Color.WHITE else Style.TEXT)
        background = Style.button(if (danger) Style.DANGER else Style.SURFACE2, dp(Style.R_CARD).toFloat()); stateListAnimator = null
        minHeight = dp(52)
        setOnClickListener { onClick() }
    }, lp(top = 12))

    private fun addBack() = button("Back") { showStart() }

    private fun alert(t: String, m: String) = AlertDialog.Builder(this).setTitle(t).setMessage(m).setPositiveButton("OK", null).show()

    private fun lp(top: Int) = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        .apply { topMargin = dp(top) }

    private fun date(at: Long) = SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(at))

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        /** Mode 06 misfire monitors for cylinders 1-4. */
        val MISFIRE_MIDS = listOf(0xA2, 0xA3, 0xA4, 0xA5)
    }
}
