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
                MotionEvent.ACTION_DOWN -> Motion.press(view, scale, down = true)
                MotionEvent.ACTION_UP -> {
                    Motion.press(view, scale, down = false)
                    if (e.x in 0f..view.width.toFloat() && e.y in 0f..view.height.toFloat())
                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                }
                MotionEvent.ACTION_CANCEL -> Motion.press(view, scale, down = false)
            }
            false
        }
        return v
    }
}
