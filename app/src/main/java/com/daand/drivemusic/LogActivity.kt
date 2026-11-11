package com.daand.drivemusic

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.FileProvider
import java.io.File

/** Shows the drive log and lets you share it (e.g. paste it into a chat to get help fixing a problem). */
@SuppressLint("SetTextI18n")
class LogActivity : Activity() {

    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var filterBtn: Button
    private var problemsOnly = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DriveLog.init(this)

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(10), dp(10), dp(10), dp(6))
            addView(btn("Refresh") { load() })
            filterBtn = btn("Problems only") {
                problemsOnly = !problemsOnly
                filterBtn.text = if (problemsOnly) "Show all" else "Problems only"
                load()
            }
            addView(filterBtn)
            addView(btn("Share") { share() })
            addView(btn("Clear") {
                AlertDialog.Builder(this@LogActivity)
                    .setMessage("Delete all logs?")
                    .setPositiveButton("Delete") { _, _ -> DriveLog.clear(); DriveLog.log("Log cleared"); load() }
                    .setNegativeButton("Cancel", null)
                    .show()
            })
        }
        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(Style.TEXT)
            setTextIsSelectable(true)
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        scroll = ScrollView(this).apply {
            addView(logView)
            background = Style.rounded(Style.SURFACE, dp(14).toFloat())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                setMargins(dp(10), 0, dp(10), dp(10))
            }
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Style.BG)
            addView(buttons)
            addView(scroll)
        }
        setContentView(content)
        // Keep clear of the status/navigation bars (Android 15+ draws edge-to-edge).
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(content) { v, insets ->
            val bars = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        window.statusBarColor = Style.BG
        window.navigationBarColor = Style.BG
        load()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun btn(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 14f; setTextColor(Style.TEXT)
        background = Style.button(Style.SURFACE2, dp(12).toFloat()); stateListAnimator = null
        setPadding(dp(14), dp(10), dp(14), dp(10))
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) }
        setOnClickListener { onClick() }
    }

    private fun load() {
        var lines = DriveLog.readAll().lines().filter { it.isNotBlank() }
        if (problemsOnly) lines = lines.filter { l -> PROBLEM_MARKERS.any { l.contains(it) } || l.contains("=== CAR") }
        lines = lines.takeLast(600)
        if (lines.isEmpty()) {
            logView.text = if (problemsOnly) "No problems logged 🎉" else "No logs yet. Go for a drive!"
            return
        }
        // Colour-code: red = problems, green = success, blue = start/end of a drive.
        val sb = SpannableStringBuilder()
        for (line in lines) {
            val start = sb.length
            sb.append(line).append('\n')
            val color = when {
                PROBLEM_MARKERS.any { line.contains(it) } -> Color.rgb(0xE5, 0x39, 0x35)
                line.contains("success") || line.contains("RESUME: auto-resuming") -> Color.rgb(0x2E, 0x9D, 0x4F)
                line.contains("=== CAR") -> Color.rgb(0x1E, 0x88, 0xE5)
                else -> null
            }
            if (color != null) {
                sb.setSpan(ForegroundColorSpan(color), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        logView.text = sb
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    companion object {
        private val PROBLEM_MARKERS = listOf("!!!", "FAILED", "ERROR", "LOST", "destroyed", "stopped by the system",
            "not installed", "gave up", "disconnected from", "looks deliberate")
    }

    private fun share() {
        val pm = getSystemService(PowerManager::class.java)
        fun ver(pkg: String) = try { packageManager.getPackageInfo(pkg, 0).versionName } catch (e: Exception) { "not installed" }
        val header = buildString {
            appendLine("Drive Music log")
            appendLine("Phone: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            val music = Prefs(this@LogActivity).musicPackage
            val microG = MusicApps.findMicroG(this@LogActivity)
            appendLine("Music app: $music ${ver(music)}, MicroG: ${microG?.let { "$it ${ver(it)}" } ?: "not found"}")
            appendLine("Android Auto: ${ver(DriveMonitorService.ANDROID_AUTO)}")
            appendLine("Battery unrestricted: music=${pm.isIgnoringBatteryOptimizations(music)}, MicroG=${microG?.let { pm.isIgnoringBatteryOptimizations(it) }}, " +
                "AA=${pm.isIgnoringBatteryOptimizations(DriveMonitorService.ANDROID_AUTO)}, app=${pm.isIgnoringBatteryOptimizations(packageName)}")
            appendLine("Monitor running: ${DriveMonitorService.instance != null}")
            appendLine("----")
        }
        val dir = File(cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, "drive-music-log.txt")
        file.writeText(header + DriveLog.readAll())
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Drive Music log")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Share log"))
    }
}
