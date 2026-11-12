package com.daand.drivemusic

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class UpdatePromptTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Before fun setUp() { DriveLog.init(ctx) }

    @Test fun redTappedWhileStillAskingCountsOnceTheQuestionEnds() {
        UpdatePrompt.open(ctx, "News", "I do apologise, sir, but there is an update. Shall I go ahead?")
        UpdatePrompt.answer(ctx, false)
        UpdatePrompt.answer(ctx, true)  // second tap ignored
        var got: Boolean? = null
        UpdatePrompt.awaitAnswer(ctx) { got = it }
        assertEquals(false, got)
        assertNull(UpdatePrompt.question)
    }

    @Test fun greenAfterTheQuestion() {
        UpdatePrompt.open(ctx, "News", "q")
        var got: Boolean? = null
        UpdatePrompt.awaitAnswer(ctx) { got = it }
        assertNull(got)
        UpdatePrompt.answer(ctx, true)
        assertEquals(true, got)
    }

    @Test fun noAnswerMeansGoAheadAfterTenSeconds() {
        UpdatePrompt.open(ctx, "News", "q")
        var got: Boolean? = null
        UpdatePrompt.awaitAnswer(ctx) { got = it }
        shadowOf(Looper.getMainLooper()).idleFor(9, TimeUnit.SECONDS)
        assertNull(got)
        shadowOf(Looper.getMainLooper()).idleFor(2, TimeUnit.SECONDS)
        assertEquals(true, got)
        assertNull(UpdatePrompt.question)
    }

    @Test fun anOldTimeoutCannotAnswerANewQuestion() {
        val looper = shadowOf(Looper.getMainLooper())
        UpdatePrompt.open(ctx, "News", "first")
        UpdatePrompt.awaitAnswer(ctx) {}             // its timer fires at 10 s
        UpdatePrompt.answer(ctx, false)
        looper.idleFor(6, TimeUnit.SECONDS)
        UpdatePrompt.open(ctx, "News", "second")
        var got: Boolean? = null
        UpdatePrompt.awaitAnswer(ctx) { got = it }   // its own timer fires at 16 s
        looper.idleFor(5, TimeUnit.SECONDS)          // now at 11 s: the FIRST timer has fired
        assertNull("the first question's timer must not answer the second", got)
        UpdatePrompt.answer(ctx, false)
        assertEquals(false, got)
    }

    @Test fun politeWording() {
        assertEquals("I do apologise, sir, but there is an update. Shall I go ahead?", DriveUpdates.askLine(false, true))
        assertTrue(DriveUpdates.declinedLine(false, true).startsWith("Very good, sir."))
        val body = DriveUpdates.compose(listOf("It has started to rain."), null, false, true, "Mr. Smith", withIntro = false)!!
        assertTrue(body, body.startsWith("It has started to rain."))
    }
}
