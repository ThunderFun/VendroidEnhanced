package com.nin0dev.vendroid.webview

import android.graphics.Color
import android.os.Build
import android.view.Window
import com.nin0dev.vendroid.utils.VDELog

/**
 * Read/write seam over the window system-bar state [BarColorManager] manages.
 * Isolates every android.view.Window access from the manager's decision logic.
 */
interface SystemBarTarget {
    /**
     * Runs [block] on the thread that owns the target (the app's UI thread).
     * Like Activity.runOnUiThread, this must execute [block] inline when
     * already on that thread, so publishes made from the UI thread take
     * effect before the caller proceeds.
     */
    fun post(block: () -> Unit)

    var statusBarColor: Int
    var navigationBarColor: Int

    /**
     * Whether isStatusBarContrastEnforced / isNavigationBarContrastEnforced
     * are available (API 35+). When false, the manager never reads or writes
     * them.
     */
    val contrastEnforcementSupported: Boolean

    var isStatusBarContrastEnforced: Boolean
    var isNavigationBarContrastEnforced: Boolean
}

/**
 * [SystemBarTarget] backed by an Activity [Window]. [postToUiThread] should be
 * Activity.runOnUiThread (inline on the UI thread, posted otherwise).
 */
@Suppress("DEPRECATION") // bar color/contrast setters are deprecated on recent
// APIs but remain live here. The app theme opts out of edge-to-edge
// enforcement (windowOptOutEdgeToEdgeEnforcement=true), so the deprecated
// setters are the only way to color the bars on all supported devices.
class WindowSystemBarTarget(
    private val window: Window,
    private val postToUiThread: (() -> Unit) -> Unit,
) : SystemBarTarget {
    override fun post(block: () -> Unit) = postToUiThread(block)

    override var statusBarColor: Int
        get() = window.statusBarColor
        set(value) { window.statusBarColor = value }

    override var navigationBarColor: Int
        get() = window.navigationBarColor
        set(value) { window.navigationBarColor = value }

    override val contrastEnforcementSupported: Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM

    override var isStatusBarContrastEnforced: Boolean
        get() = if (contrastEnforcementSupported) window.isStatusBarContrastEnforced else true
        set(value) { if (contrastEnforcementSupported) window.isStatusBarContrastEnforced = value }

    override var isNavigationBarContrastEnforced: Boolean
        get() = if (contrastEnforcementSupported) window.isNavigationBarContrastEnforced else true
        set(value) { if (contrastEnforcementSupported) window.isNavigationBarContrastEnforced = value }
}

/**
 * Sole writer of an activity window's status and navigation bar colors.
 *
 * Two features recolor the bars: the Vencord overlay (status+nav black while
 * active) and fullscreen video (status black while a custom view shows; the
 * nav bar is hidden via insets, not recolored). Each publishes its state via
 * [publishOverlayActive] / [publishVideoFullscreen] and neither reads the
 * other's state. Every publish re-derives the bar state from the two flags
 * ([reapply]), so any interleaving converges. Bars are black iff at least
 * one feature wants them black; otherwise the captured original applies.
 *
 * This replaces two independent per-feature "original color" caches that
 * clobbered each other (a video started under the overlay snapshotted the
 * overlay's black as "original" and restored it on exit; an overlay activated
 * during fullscreen was dropped entirely, leaving the wrong color latched).
 *
 * Contract:
 *  - This class must be the only code writing this window's bar colors.
 *    Route any future bar-color change through a publish + reapply here.
 *  - Do not reintroduce per-feature color snapshots. Originals are captured
 *    exactly once, before the first write ([captureOriginals]).
 *  - Publishers write their flag first and only then queue the reapply, so
 *    every queued reapply observes it. reapply reads each flag once; a
 *    concurrent publish queues a second reapply that converges.
 */
class BarColorManager(private val target: SystemBarTarget) {
    /** Overlay (Vencord title bar) active. Published from the JS bridge thread. */
    @Volatile
    private var overlayActive = false

    /** Fullscreen video custom view showing. Published from the UI thread. */
    @Volatile
    private var videoFullscreen = false

    // Original (theme) bar state. Captured once before the first write (see
    // the class contract), then valid for the window's lifetime.
    private var captured = false
    private var originalStatusBarColor = Color.BLACK
    private var originalNavigationBarColor = Color.BLACK
    private var originalStatusBarContrastEnforced = true
    private var originalNavigationBarContrastEnforced = true

    /**
     * Overlay state change. Safe from any thread.
     */
    fun publishOverlayActive(active: Boolean) {
        overlayActive = active
        target.post { reapply() }
    }

    /**
     * Fullscreen-video state change. Called from WebChromeClient callbacks,
     * so always on the UI thread.
     */
    fun publishVideoFullscreen(active: Boolean) {
        videoFullscreen = active
        target.post { reapply() }
    }

    private fun reapply() {
        try {
            // One read per flag; see the class contract.
            val overlay = overlayActive
            val video = videoFullscreen

            if (!captured) captureOriginals()

            val statusBlack = overlay || video
            val navBlack = overlay
            val status = if (statusBlack) Color.BLACK else originalStatusBarColor
            val nav = if (navBlack) Color.BLACK else originalNavigationBarColor
            val statusContrast = if (statusBlack) false else originalStatusBarContrastEnforced
            val navContrast = if (navBlack) false else originalNavigationBarContrastEnforced

            var changed = false
            if (target.statusBarColor != status) {
                target.statusBarColor = status
                changed = true
            }
            if (target.navigationBarColor != nav) {
                target.navigationBarColor = nav
                changed = true
            }
            if (target.contrastEnforcementSupported) {
                if (target.isStatusBarContrastEnforced != statusContrast) {
                    target.isStatusBarContrastEnforced = statusContrast
                    changed = true
                }
                if (target.isNavigationBarContrastEnforced != navContrast) {
                    target.isNavigationBarContrastEnforced = navContrast
                    changed = true
                }
            }
            if (changed) {
                VDELog.d(
                    TAG,
                    "applied status=${hex(status)} nav=${hex(nav)} " +
                        "contrast=$statusContrast/$navContrast (overlay=$overlay video=$video)"
                )
            }
        } catch (t: Throwable) {
            // A publish can land during activity teardown, where window
            // attribute writes throw (detached decor view). Never propagate.
            VDELog.e(TAG, "reapply failed", t)
        }
    }

    private fun captureOriginals() {
        // Read into locals first. If a read throws (dying window), `captured`
        // stays false and the next reapply retries with fresh values.
        val status = target.statusBarColor
        val nav = target.navigationBarColor
        val statusContrast =
            if (target.contrastEnforcementSupported) target.isStatusBarContrastEnforced else true
        val navContrast =
            if (target.contrastEnforcementSupported) target.isNavigationBarContrastEnforced else true

        originalStatusBarColor = status
        originalNavigationBarColor = nav
        originalStatusBarContrastEnforced = statusContrast
        originalNavigationBarContrastEnforced = navContrast
        captured = true
        VDELog.d(
            TAG,
            "captured original status=${hex(status)} nav=${hex(nav)} " +
                "contrast=$statusContrast/$navContrast"
        )
    }

    private fun hex(color: Int): String = "0x" + Integer.toHexString(color).uppercase()

    companion object {
        private const val TAG = "BarColors"
    }
}
