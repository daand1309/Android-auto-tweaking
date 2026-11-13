package com.daand.drivemusic

import android.annotation.SuppressLint
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View

/**
 * Touch feedback you can feel without looking: the control dips slightly while pressed and the phone
 * gives a short tick when the press counts. Matters most in the car, where you glance, not stare.
 */
object Feel {
    private const val PRESSED = 0.95f

    @SuppressLint("ClickableViewAccessibility")  // returns false: the normal click (and accessibility) still happens
    fun press(v: View, scale: Float = PRESSED): View {
        v.isHapticFeedbackEnabled = true
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                // Start delay and interpolator stay set on a view's animator from earlier animations
                // (e.g. a page's staggered glide-in): reset them, or the press would answer late.
                MotionEvent.ACTION_DOWN -> view.animate().scaleX(scale).scaleY(scale).setStartDelay(0).setInterpolator(null).setDuration(80).start()
                MotionEvent.ACTION_UP -> {
                    view.animate().scaleX(1f).scaleY(1f).setStartDelay(0).setInterpolator(null).setDuration(140).start()
                    if (e.x in 0f..view.width.toFloat() && e.y in 0f..view.height.toFloat())
                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                }
                MotionEvent.ACTION_CANCEL -> view.animate().scaleX(1f).scaleY(1f).setStartDelay(0).setInterpolator(null).setDuration(140).start()
            }
            false
        }
        return v
    }
}
