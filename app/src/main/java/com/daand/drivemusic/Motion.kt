package com.daand.drivemusic

import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.Interpolator
import android.view.animation.OvershootInterpolator
import android.widget.TextView

/**
 * The app's animations, kept short and in one place.
 *
 * They run on their own frame clock (Choreographer) instead of Android's animators, because those
 * are switched off entirely when the phone's animations are off (Samsung "Remove animations",
 * developer animation scale 0) - then nothing in the app would ever move. The app's own switch
 * ([enabled], Drive Mode settings) turns them off instead.
 */
object Motion {
    private val ease = DecelerateInterpolator(2f)

    /** The app's own setting (on by default); off = everything jumps straight to its end. */
    @Volatile var enabled = true

    /** A running animation; [cancel] stops it where it is (without calling its end). */
    class Handle internal constructor(
        private val durationMs: Long, private val delayMs: Long, private val interp: Interpolator?,
        private val repeat: Boolean, private val update: (Float) -> Unit, private val end: () -> Unit,
    ) : Choreographer.FrameCallback {
        private var startNs = 0L
        @Volatile var cancelled = false; private set
        internal fun start() {
            if (!enabled) { update(1f); end(); return }
            startNs = System.nanoTime() + delayMs * 1_000_000
            if (delayMs > 0) update(0f)
            Choreographer.getInstance().postFrameCallback(this)
        }
        override fun doFrame(frameTimeNanos: Long) {
            if (cancelled) return
            val now = System.nanoTime()
            if (now < startNs) { Choreographer.getInstance().postFrameCallback(this); return }
            val raw = ((now - startNs) / 1_000_000.0 / durationMs).toFloat()
            if (repeat) {
                update(raw % 1f)
                Choreographer.getInstance().postFrameCallback(this)
                return
            }
            val t = raw.coerceIn(0f, 1f)
            update(interp?.getInterpolation(t) ?: t)
            if (t < 1f) Choreographer.getInstance().postFrameCallback(this) else end()
        }
        fun cancel() { cancelled = true; Choreographer.getInstance().removeFrameCallback(this) }
    }

    /** Runs [update] with 0..1 (eased) every frame for [durationMs], then [end]. Main thread. */
    fun play(durationMs: Long, delayMs: Long = 0, interp: Interpolator? = ease, update: (Float) -> Unit, end: () -> Unit = {}): Handle =
        Handle(durationMs, delayMs, interp, false, update, end).also { it.start() }

    /** One animation per view and [slot]: a new one replaces (cancels) the old one in that slot. */
    private fun slot(v: View, slot: Int, h: Handle?) {
        (v.getTag(slot) as? Handle)?.cancel()
        v.setTag(slot, h)
    }

    /**
     * A page appears: its blocks glide up and fade in one after another. Only the first [MAX_STAGGER]
     * are staggered, so long pages are complete in about half a second.
     */
    fun enter(root: ViewGroup, fromDp: Float = 18f) {
        val d = root.resources.displayMetrics.density
        for (i in 0 until root.childCount) {
            val v = root.getChildAt(i)
            v.alpha = 0f; v.translationY = fromDp * d
            slot(v, R.id.motion_enter, play(320, minOf(i, MAX_STAGGER) * STAGGER_MS, update = { f ->
                v.alpha = f; v.translationY = (1 - f) * fromDp * d
            }))
        }
    }

    /** Going back: a quick, plain fade (no stagger - you've seen this page already). */
    fun fadeIn(root: View) {
        root.alpha = 0f
        slot(root, R.id.motion_enter, play(180, update = { root.alpha = it }))
    }

    /** A gentle, endless pulse (the "Driving" dot). Stops by itself when the view leaves the screen. */
    fun pulse(v: View) {
        fun start() {
            if (!enabled) return
            slot(v, R.id.motion_loop, Handle(1800, 0, null, true, { f ->
                v.alpha = 0.72f + 0.28f * Math.cos(f * 2 * Math.PI).toFloat()
            }, {}).also { it.start() })
        }
        v.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = start()
            override fun onViewDetachedFromWindow(view: View) { slot(view, R.id.motion_loop, null); view.alpha = 1f }
        })
        if (v.isAttachedToWindow) start()
    }

    /** A slow settle-in zoom for a photo (like a camera easing back). Runs alongside the page's glide-in. */
    fun settle(v: View) {
        slot(v, R.id.motion_scale, play(1100, update = { f -> val s = 1.08f - 0.08f * f; v.scaleX = s; v.scaleY = s }))
    }

    /** New text slides in from below while the old fades (song title changing). */
    fun swapText(tv: TextView, text: CharSequence) {
        if (tv.text == text) return
        if (tv.text.isNullOrEmpty() || !tv.isAttachedToWindow || !enabled) { tv.text = text; return }
        val d = tv.resources.displayMetrics.density
        slot(tv, R.id.motion_enter, play(120, update = { f -> tv.alpha = 1 - f; tv.translationY = -8 * d * f }, end = {
            tv.text = text
            slot(tv, R.id.motion_enter, play(220, update = { f -> tv.alpha = f; tv.translationY = 10 * d * (1 - f) }))
        }))
    }

    /** A quick pop - the play/pause icon flipping. */
    fun pop(v: View) {
        slot(v, R.id.motion_scale, play(280, interp = OvershootInterpolator(2.5f), update = { f ->
            val s = 0.82f + 0.18f * f; v.scaleX = s; v.scaleY = s; v.rotation = -12f * (1 - f)
        }))
    }

    /** Press feedback: shrink a little while held, spring back on release. */
    fun press(v: View, scale: Float, down: Boolean) {
        val from = v.scaleX
        val to = if (down) scale else 1f
        slot(v, R.id.motion_scale, play(if (down) 80 else 140, update = { f -> val s = from + (to - from) * f; v.scaleX = s; v.scaleY = s }))
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
        tv.setTag(R.id.count_anim, play(durationMs, update = { f ->
            val v = target * f
            tv.text = before + (if (decimals > 0) "%.${decimals}f".format(java.util.Locale.ROOT, v) else Math.round(v).toString()) + after
        }, end = { tv.setTag(R.id.count_anim, null); tv.setTag(R.id.count_target, null); tv.text = text }))
    }

    /** What a TextView shows - or is counting up to. */
    fun target(tv: TextView): CharSequence = (tv.getTag(R.id.count_target) as? CharSequence) ?: tv.text

    /** Stops a count-up in progress (a newer value arrived): the caller sets the new text right after. */
    fun stop(tv: TextView) {
        (tv.getTag(R.id.count_anim) as? Handle)?.cancel()
        tv.setTag(R.id.count_anim, null); tv.setTag(R.id.count_target, null)
    }

    private val NUMBER = Regex("""\d+(\.\d+)?""")
    private const val MAX_STAGGER = 8
    private const val STAGGER_MS = 35L
}
