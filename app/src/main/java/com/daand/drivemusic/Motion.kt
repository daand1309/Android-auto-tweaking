package com.daand.drivemusic

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.TextView

/**
 * The app's animations, kept short and in one place. All of them follow Android's own animation
 * setting: with animations switched off in Developer options / Accessibility they simply jump to the end.
 */
object Motion {
    private val ease = DecelerateInterpolator(2f)

    /**
     * A page appears: its blocks glide up and fade in one after another. Only the first [MAX_STAGGER]
     * are staggered, so long pages are complete in about half a second.
     */
    fun enter(root: ViewGroup, fromDp: Float = 18f) {
        val d = root.resources.displayMetrics.density
        for (i in 0 until root.childCount) {
            val v = root.getChildAt(i)
            v.animate().cancel()
            v.alpha = 0f; v.translationY = fromDp * d
            v.animate().alpha(1f).translationY(0f)
                .setStartDelay(minOf(i, MAX_STAGGER) * STAGGER_MS).setDuration(320).setInterpolator(ease).start()
        }
    }

    /** Going back: a quick, plain fade (no stagger - you've seen this page already). */
    fun fadeIn(root: View) {
        root.alpha = 0f
        root.animate().alpha(1f).setDuration(180).start()
    }

    /** A gentle, endless pulse (the "Driving" dot). Stops by itself when the view leaves the screen. */
    fun pulse(v: View) {
        val anim = ObjectAnimator.ofFloat(v, View.ALPHA, 1f, 0.45f).apply {
            duration = 900; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE
        }
        v.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) { if (!anim.isStarted) anim.start() }
            override fun onViewDetachedFromWindow(view: View) { anim.cancel(); view.alpha = 1f }
        })
        if (v.isAttachedToWindow) anim.start()
    }

    /** A slow settle-in zoom for a photo (like a camera easing back). */
    fun settle(v: View) {
        // Its own animators (not v.animate()), so it runs alongside the page's glide-in.
        for (prop in listOf(View.SCALE_X, View.SCALE_Y)) ObjectAnimator.ofFloat(v, prop, 1.08f, 1f).apply {
            duration = 1100; interpolator = ease; start()
        }
    }

    /** New text slides in from below while the old fades (song title changing). */
    fun swapText(tv: TextView, text: CharSequence) {
        if (tv.text == text) return
        if (tv.text.isNullOrEmpty() || !tv.isAttachedToWindow) { tv.text = text; return }
        val d = tv.resources.displayMetrics.density
        tv.animate().cancel()
        tv.animate().alpha(0f).translationY(-8 * d).setDuration(120).withEndAction {
            tv.text = text
            tv.translationY = 10 * d
            tv.animate().alpha(1f).translationY(0f).setDuration(220).setInterpolator(ease).start()
        }.start()
    }

    /** A quick pop - the play/pause icon flipping. */
    fun pop(v: View) {
        v.animate().cancel()
        v.scaleX = 0.82f; v.scaleY = 0.82f; v.rotation = -12f
        v.animate().scaleX(1f).scaleY(1f).rotation(0f).setDuration(260).setInterpolator(OvershootInterpolator(2.5f)).start()
    }

    /**
     * Dashboard self-test feel: a number counts up from 0 to its value ("72", "2350", "88 °C", "14.1 V",
     * "≈ 385 km"). Text without a number is just set.
     */
    fun countUp(tv: TextView, text: String, durationMs: Long = 650) {
        val m = NUMBER.find(text)
        val target = m?.value?.toDoubleOrNull()
        if (m == null || target == null || target <= 0) { tv.text = text; return }
        val decimals = m.value.substringAfter('.', "").length
        val before = text.substring(0, m.range.first); val after = text.substring(m.range.last + 1)
        stop(tv)
        tv.setTag(R.id.count_target, text)
        tv.setTag(R.id.count_anim, ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs; interpolator = ease
            addUpdateListener {
                val v = target * (it.animatedValue as Float)
                tv.text = before + (if (decimals > 0) "%.${decimals}f".format(java.util.Locale.ROOT, v) else Math.round(v).toString()) + after
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    tv.setTag(R.id.count_anim, null); tv.setTag(R.id.count_target, null); tv.text = text
                }
            })
            start()
        })
    }

    /** What a TextView shows - or is counting up to. */
    fun target(tv: TextView): CharSequence = (tv.getTag(R.id.count_target) as? CharSequence) ?: tv.text

    /** Stops a count-up in progress (a newer value arrived): the caller sets the new text right after. */
    fun stop(tv: TextView) {
        (tv.getTag(R.id.count_anim) as? ValueAnimator)?.let { it.removeAllListeners(); it.cancel() }
        tv.setTag(R.id.count_anim, null); tv.setTag(R.id.count_target, null)
    }

    private val NUMBER = Regex("""\d+(\.\d+)?""")
    private const val MAX_STAGGER = 8
    private const val STAGGER_MS = 35L
}
