package com.nin0dev.vendroid

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.View.GONE
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity

/**
 * Manages the loading screen — animation dots, timeout, and dismiss scheduling.
 * Extracted from MainActivity to keep lifecycle orchestration separate from
 * UI chrome.
 */
class LoadingScreenManager(
    private val activity: AppCompatActivity,
    private val loadingScreenLayout: LinearLayout
) {
    private var dismissed = false
    private var animRunnable: Runnable? = null
    private var timeoutRunnable: Runnable? = null
    private var dismissRunnable: Runnable? = null
    private var animStartTime: Long = 0
    private val handler = Handler(Looper.getMainLooper())

    fun start() {
        val dots = intArrayOf(R.id.dot1, R.id.dot2, R.id.dot3)
            .map { activity.findViewById<View>(it) }
        animStartTime = System.currentTimeMillis()

        dots.forEach { dot ->
            // Hardware layer caches each dot as a GPU texture — the scale/alpha
            // animation then becomes a pure GPU transform with zero draw calls.
            dot.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            dot.scaleX = 0.4f
            dot.scaleY = 0.4f
            dot.alpha = 0.3f
        }

        // postDelayed at ~30fps because ValueAnimator fails to produce timely
        // callbacks for extremely long-duration animations in this use case.
        val runnable = object : Runnable {
            override fun run() {
                if (dismissed) return
                val elapsed = System.currentTimeMillis() - animStartTime
                val cycleMs = 1200L
                dots.forEachIndexed { i, dot ->
                    val phase = (elapsed - i * 200L).toDouble()
                    val t = (phase % cycleMs) / cycleMs
                    val wave = (Math.sin(t * 2.0 * Math.PI - Math.PI / 2.0) + 1.0) / 2.0
                    dot.scaleX = 0.4f + wave.toFloat() * 0.6f
                    dot.scaleY = 0.4f + wave.toFloat() * 0.6f
                    dot.alpha = 0.3f + wave.toFloat() * 0.7f
                }
                animRunnable = this
                handler.postDelayed(this, 33)
            }
        }
        runnable.run()
    }

    fun scheduleTimeout(delayMs: Long) {
        timeoutRunnable = Runnable {
            if (!dismissed) dismiss()
        }
        handler.postDelayed(timeoutRunnable!!, delayMs)
    }

    fun scheduleDismiss(delayMs: Long) {
        if (dismissed) return
        val runnable = Runnable { dismiss() }
        dismissRunnable = runnable
        handler.postDelayed(runnable, delayMs)
    }

    fun dismiss() {
        if (dismissed) return
        dismissed = true
        animRunnable?.let { handler.removeCallbacks(it) }
        animRunnable = null
        loadingScreenLayout.animate()?.alpha(0f)?.setDuration(500)?.withEndAction {
            loadingScreenLayout.visibility = GONE
            loadingScreenLayout.alpha = 1f
        }?.start()
    }

    fun cleanup() {
        timeoutRunnable?.let { handler.removeCallbacks(it) }
        dismissRunnable?.let { handler.removeCallbacks(it) }
        animRunnable?.let { handler.removeCallbacks(it) }
        dismissRunnable = null
        animRunnable = null
    }
}
