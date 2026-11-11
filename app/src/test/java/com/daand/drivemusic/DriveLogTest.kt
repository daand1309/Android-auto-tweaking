package com.daand.drivemusic

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class DriveLogTest {
    private lateinit var ctx: Context

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        DriveLog.init(ctx)
        DriveLog.clear()
    }

    @Test fun writesTimestampedLines() {
        DriveLog.log("hello car")
        val text = DriveLog.readAll()
        assertTrue(text, Regex("""\d{4}-\d\d-\d\d \d\d:\d\d:\d\d\.\d{3}  hello car""").containsMatchIn(text))
    }

    @Test fun rotatesSoLogsNeverGrowUnbounded() {
        val line = "x".repeat(200)
        repeat(8000) { DriveLog.log("$it $line") }  // ~1.8 MB written
        val dir = File(ctx.filesDir, "logs")
        val total = dir.listFiles()!!.sumOf { it.length() }
        assertTrue("logs use $total bytes", total < 1_200_000)
        assertTrue(DriveLog.readAll().contains("7999 "))  // newest line kept
    }

    @Test fun clearRemovesEverything() {
        DriveLog.log("something")
        DriveLog.clear()
        assertEquals("", DriveLog.readAll())
    }
}
