package com.daand.drivemusic

import android.annotation.SuppressLint
import android.app.Activity
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DriveLog.init(this)

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(btn("Refresh") { load() })
            addView(btn("Share") { share() })
            addView(btn("Clear") { DriveLog.clear(); DriveLog.log("Log cleared"); load() })
        }
        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
            setPadding(16, 16, 16, 16)
        }
        scroll = ScrollView(this).apply {
            addView(logView)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(buttons)
            addView(scroll)
        })
        load()
    }

    private fun btn(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; setOnClickListener { onClick() }
    }

    private fun load() {
        val lines = DriveLog.readAll().lines()
        logView.text = lines.takeLast(600).joinToString("\n").ifBlank { "No logs yet. Go for a drive!" }
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
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
