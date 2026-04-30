package com.nin0dev.vendroid.webview

import android.content.ActivityNotFoundException
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.ConsoleMessage.MessageLevel
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.nin0dev.vendroid.MainActivity
import com.nin0dev.vendroid.R
import com.nin0dev.vendroid.utils.Logger.e
import com.nin0dev.vendroid.utils.Logger.i
import com.nin0dev.vendroid.utils.Logger.w
import java.lang.ref.WeakReference

class VChromeClient(activity: MainActivity) : WebChromeClient() {
    private val activityRef: WeakReference<MainActivity> = WeakReference(activity)
    private var customView: View? = null
    private var customViewCallback: CustomViewCallback? = null
    private var originalStatusBarColor: Int = 0
    private lateinit var fullscreenContainer: FrameLayout
    private lateinit var webview: WebView
    private var cachedActivityRef: MainActivity? = null
    private var insetsController: WindowInsetsControllerCompat? = null
    val isFullscreen: Boolean get() = customView != null

    private fun ensureViewsInitialized(activity: MainActivity) {
        if (cachedActivityRef !== activity) {
            fullscreenContainer = activity.findViewById(R.id.fullscreen_container)
            webview = activity.findViewById(R.id.webview)
            cachedActivityRef = activity
        }
    }

    private fun getInsetsController(activity: MainActivity): WindowInsetsControllerCompat {
        return insetsController ?: WindowInsetsControllerCompat(activity.window, activity.window.decorView).also {
            insetsController = it
        }
    }

    private val transparentPoster: Bitmap by lazy {
        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).also { it.eraseColor(0) }
    }

    override fun getDefaultVideoPoster(): Bitmap? = transparentPoster

    override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
        if (!com.nin0dev.vendroid.BuildConfig.DEBUG) return true
        // Defer string construction until AFTER level dispatch — avoids
        // allocating the full message string for DEBUG/LOG levels that
        // Discord emits hundreds of times per second (React dev tools,
        // webpack HMR, etc.). This eliminates the dominant per-message
        // GC pressure in debug mode.
        when (msg.messageLevel()) {
            MessageLevel.ERROR -> e("[Javascript] ${msg.message()} @ ${msg.lineNumber()}: ${msg.sourceId()}")
            MessageLevel.WARNING -> w("[Javascript] ${msg.message()} @ ${msg.lineNumber()}: ${msg.sourceId()}")
            // Skip DEBUG/LOG — extremely voluminous, near-zero diagnostic value
            MessageLevel.DEBUG, MessageLevel.LOG -> {}
            else -> i("[Javascript] ${msg.message()} @ ${msg.lineNumber()}: ${msg.sourceId()}")
        }
        return true
    }

    override fun onShowFileChooser(
        webView: WebView,
        filePathCallback: ValueCallback<Array<Uri>>,
        fileChooserParams: FileChooserParams
    ): Boolean {
        val activity = activityRef.get() ?: return false

        activity.filePathCallback?.onReceiveValue(null)
        activity.filePathCallback = null

        activity.filePathCallback = filePathCallback

        return try {
            val intent = fileChooserParams.createIntent()
            activity.fileChooserLauncher.launch(intent)
            true
        } catch (e: ActivityNotFoundException) {
            activity.filePathCallback = null
            false
        }
    }

    override fun onShowCustomView(view: View, callback: CustomViewCallback) {
        if (customView != null) {
            callback.onCustomViewHidden()
            customViewCallback?.onCustomViewHidden()
            return
        }
        val activity = activityRef.get() ?: return
        ensureViewsInitialized(activity)
        customView = view
        customViewCallback = callback
        // Hardware layer on the fullscreen view lets the compositor overlay
        // the video surface directly without extra composition passes.
        view.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        fullscreenContainer.addView(view)
        fullscreenContainer.visibility = View.VISIBLE
        webview.visibility = View.GONE
        val controller = getInsetsController(activity)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.navigationBars())
        @Suppress("DEPRECATION")
        originalStatusBarColor = activity.window.statusBarColor
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            activity.window.isStatusBarContrastEnforced = false
        }
        activity.window.statusBarColor = Color.BLACK
    }

    override fun onHideCustomView() {
        if (customView == null) return
        val activity = activityRef.get()
        val localCustomView = customView
        val localCallback = customViewCallback

        customView = null
        customViewCallback = null

        if (activity == null || !::fullscreenContainer.isInitialized) {
            localCallback?.onCustomViewHidden()
            return
        }
        fullscreenContainer.visibility = View.GONE
        // Restore to default layer type — the view is being removed, so
        // the GPU texture it held can be released.
        localCustomView?.setLayerType(View.LAYER_TYPE_NONE, null)
        fullscreenContainer.removeView(localCustomView)
        webview.visibility = View.VISIBLE
        val controller = getInsetsController(activity)
        controller.show(WindowInsetsCompat.Type.navigationBars())
        @Suppress("DEPRECATION")
        activity.window.statusBarColor = originalStatusBarColor
        localCallback?.onCustomViewHidden()
    }

    fun hideCustomView() {
        if (customView != null) {
            onHideCustomView()
        }
    }
}
