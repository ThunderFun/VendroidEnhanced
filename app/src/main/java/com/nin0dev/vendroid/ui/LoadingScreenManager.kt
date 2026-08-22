package com.nin0dev.vendroid.ui

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.View.GONE
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import com.nin0dev.vendroid.R

/**
 * Manages the loading screen — animation dots, timeout, and dismiss scheduling.
 * Extracted from MainActivity to keep lifecycle orchestration separate from
 * UI chrome. Lives in the `ui` package as a pure UI/loading controller.
 */
class LoadingScreenManager(
    private val activity: AppCompatActivity,
    private val loadingScreenLayout: LinearLayout
) {
    private var dismissed = false
    private var animRunnable: Runnable? = null
    private var timeoutRunnable: Runnable? = null
    private var dismissRunnable: Runnable? = null
    private var dismissAnimator: android.view.ViewPropertyAnimator? = null
    private var animStartTime: Long = 0
    private val handler = Handler(Looper.getMainLooper())

    // Dots are resolved once at construction; they never change for the activity.
    private val dots = intArrayOf(R.id.dot1, R.id.dot2, R.id.dot3)
        .map { activity.findViewById<View>(it) }

    fun start() {
        animStartTime = System.currentTimeMillis()

        dots.forEach { dot ->
            // Hardware layer caches each dot as a GPU texture — the scale/alpha
            // animation then becomes a pure GPU transform with zero draw calls.
            dot.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            dot.scaleX = 0.4f
            dot.scaleY = 0.4f
            dot.alpha = 0.3f
        }

        // Drive the animation with postDelayed at ~30fps because ValueAnimator
        // misses callbacks for very long-duration animations in this use case.
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

    /** Stops the animation loop to avoid background CPU churn. */
    fun pause() {
        if (dismissed) return
        animRunnable?.let { handler.removeCallbacks(it) }
    }

    /** Resumes the animation loop after [pause]. */
    fun resume() {
        if (dismissed) return
        // Remove before posting so a resume without a prior pause (e.g. the
        // initial onResume) doesn't start a second concurrent loop.
        animRunnable?.let { handler.removeCallbacks(it); handler.post(it) }
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
        timeoutRunnable?.let { handler.removeCallbacks(it) }
        timeoutRunnable = null
        animRunnable?.let { handler.removeCallbacks(it) }
        animRunnable = null
        dismissAnimator = loadingScreenLayout.animate()?.alpha(0f)?.setDuration(500)?.withEndAction {
            loadingScreenLayout.visibility = GONE
            loadingScreenLayout.alpha = 1f
        }
        dismissAnimator?.start()
    }

    fun cleanup() {
        timeoutRunnable?.let { handler.removeCallbacks(it) }
        dismissRunnable?.let { handler.removeCallbacks(it) }
        animRunnable?.let { handler.removeCallbacks(it) }
        // Cancel any in-flight dismiss animation so its withEndAction doesn't
        // touch a detached/destroyed view hierarchy after onDestroy.
        dismissAnimator?.cancel()
        timeoutRunnable = null
        dismissRunnable = null
        animRunnable = null
        dismissAnimator = null
    }
}
