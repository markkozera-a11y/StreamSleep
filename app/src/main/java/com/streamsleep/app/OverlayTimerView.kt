package com.streamsleep.app

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

class OverlayTimerView(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: TextView? = null
    private val handler = Handler(Looper.getMainLooper())

    private val layoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.BOTTOM or Gravity.END
        x = 32
        y = 80
    }

    fun show(secondsLeft: Long) {
        handler.post {
            if (overlayView != null) hide()
            overlayView = TextView(context).apply {
                text = formatTime(secondsLeft)
                textSize = 11f
                setTextColor(Color.WHITE)
                setShadowLayer(4f, 1f, 1f, Color.BLACK)
                alpha = 0.75f
                setPadding(10, 6, 10, 6)
                setBackgroundColor(Color.argb(100, 0, 0, 0))
            }
            try {
                windowManager.addView(overlayView, layoutParams)
            } catch (e: Exception) {
                // Overlay permission not granted
            }
        }
    }

    fun updateTime(secondsLeft: Long) {
        handler.post {
            overlayView?.text = formatTime(secondsLeft)
        }
    }

    fun hide() {
        handler.post {
            overlayView?.let {
                try {
                    windowManager.removeView(it)
                } catch (e: Exception) {}
                overlayView = null
            }
        }
    }

    private fun formatTime(seconds: Long): String {
        val m = seconds / 60
        val s = seconds % 60
        return "%02d:%02d".format(m, s)
    }
}
