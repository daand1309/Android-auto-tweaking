package com.daand.drivemusic

import android.content.Context
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * Tabs you swipe between (left/right), like a phone's home screens. Works even when the swipe starts
 * on a big button: a clearly sideways drag is taken over from the button, a tap still presses it.
 */
class SwipePager(context: Context) : FrameLayout(context) {
    private val pages = mutableListOf<View>()
    var current = 0; private set
    var onPageChanged: (Int) -> Unit = {}

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var tracker: VelocityTracker? = null

    val pageCount get() = pages.size

    fun addPage(v: View) {
        pages += v
        addView(v, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        place(0f)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        place(0f)
    }

    private fun place(dragDx: Float) {
        pages.forEachIndexed { i, v ->
            v.animate().cancel()
            v.translationX = (i - current) * width + dragDx
            v.visibility = if (i == current || dragDx != 0f) View.VISIBLE else View.INVISIBLE
        }
    }

    /** Is this movement a sideways swipe (not a tap, not a vertical scroll)? */
    private fun isSideways(ev: MotionEvent): Boolean {
        val dx = ev.x - downX
        val dy = ev.y - downY
        return abs(dx) > slop * 2 && abs(dx) > abs(dy) * 1.5f
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y; dragging = false
                tracker?.recycle(); tracker = VelocityTracker.obtain().also { it.addMovement(ev) }
            }
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(ev)
                if (isSideways(ev)) { dragging = true; parent?.requestDisallowInterceptTouchEvent(true); return true }
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            downX = ev.x; downY = ev.y; dragging = false
            tracker?.recycle(); tracker = VelocityTracker.obtain()
        }
        tracker?.addMovement(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && isSideways(ev)) dragging = true
                if (dragging) {
                    var dx = ev.x - downX
                    // Resist at the first/last tab so it's clear there's nothing further.
                    if ((current == 0 && dx > 0) || (current == pages.size - 1 && dx < 0)) dx /= 3
                    place(dx)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    val dx = ev.x - downX
                    tracker?.computeCurrentVelocity(1000)
                    val vx = tracker?.xVelocity ?: 0f
                    val target = when {
                        (dx < -width * 0.25f || vx < -800) && current < pages.size - 1 -> current + 1
                        (dx > width * 0.25f || vx > 800) && current > 0 -> current - 1
                        else -> current
                    }
                    goTo(target)
                }
                dragging = false
                tracker?.recycle(); tracker = null
            }
        }
        return true
    }

    /** Go to a tab (animated). */
    fun goTo(index: Int, animate: Boolean = true) {
        val old = current
        current = index.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
        pages.forEachIndexed { i, v ->
            val target = ((i - current) * width).toFloat()
            v.visibility = View.VISIBLE
            if (animate && width > 0) v.animate().translationX(target).setDuration(220)
                .withEndAction { if (i != current) v.visibility = View.INVISIBLE }.start()
            else { v.translationX = target; v.visibility = if (i == current) View.VISIBLE else View.INVISIBLE }
        }
        if (old != current) onPageChanged(current)
    }
}
