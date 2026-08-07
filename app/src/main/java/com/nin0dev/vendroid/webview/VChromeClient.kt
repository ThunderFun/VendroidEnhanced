package com.nin0dev.vendroid.webview

import android.content.ActivityNotFoundException
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.ConsoleMessage.MessageLevel
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.nin0dev.vendroid.BuildConfig
import com.nin0dev.vendroid.MainActivity
import com.nin0dev.vendroid.R
import com.nin0dev.vendroid.utils.VDELog
import java.lang.ref.WeakReference

class VChromeClient(activity: MainActivity) : WebChromeClient() {
    private val activityRef: WeakReference<MainActivity> = WeakReference(activity)
    private var customView: View? = null
    private var customViewCallback: CustomViewCallback? = null
    private var originalStatusBarColor: Int = 0
    private var originalStatusBarContrastEnforced: Boolean = true
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
        val message = msg.message()

        // Route [Vendroid] messages to VDELog in all builds (not just DEBUG)
        // to capture vencord_mobile.js diagnostics (webpack capture, plugin
        // init, Slate fixes, GIF picker) for the in-app log viewer. Only accept
        // them when the committed main frame is a Discord app-origin host, so a
        // third-party Activity/iframe page cannot forge trusted diagnostics
        // into the shareable log.
        if (message.startsWith("[Vendroid]") || message.startsWith("[VDE]")) {
            val host = activityRef.get()?.currentHostForBridge
            if (host == null || !com.nin0dev.vendroid.utils.Constants.isDiscordAppOrigin(host)) {
                // Drop without persisting — emitted by a non-app-origin page.
                return true
            }
            val level = when (msg.messageLevel()) {
                MessageLevel.ERROR -> VDELog.Level.ERROR
                MessageLevel.WARNING -> VDELog.Level.WARN
                else -> VDELog.Level.INFO
            }
            VDELog.log(level, "JS", message)
            // Do not also call Logger — that would double-log to VDELog.
            return true
        }

        // Non-Vendroid messages: logcat only in debug builds. Use Log.e/w
        // directly, not Logger, to avoid flooding VDELog with Discord's
        // internal JS noise (React dev tools, webpack HMR, etc.).
        if (BuildConfig.DEBUG) {
            when (msg.messageLevel()) {
                MessageLevel.ERROR -> Log.e("Vendroid", "[JS] $message @ ${msg.lineNumber()}")
                MessageLevel.WARNING -> Log.w("Vendroid", "[JS] $message @ ${msg.lineNumber()}")
                else -> {}
            }
        }
        return true
    }

    override fun onShowFileChooser(
        webView: WebView,
        filePathCallback: ValueCallback<Array<Uri>>,
        fileChooserParams: FileChooserParams
    ): Boolean {
        val activity = activityRef.get() ?: return false

        val oldCallback = activity.filePathCallback
        activity.filePathCallback = filePathCallback
        oldCallback?.onReceiveValue(null)

        return try {
            val intent = fileChooserParams.createIntent()
            activity.fileChooserLauncher.launch(intent)
            true
        } catch (e: Exception) {
            activity.filePathCallback = null
            filePathCallback.onReceiveValue(null)
            false
        }
    }

    override fun onShowCustomView(view: View, callback: CustomViewCallback) {
        if (customView != null) {
            // Already fullscreen: reject the NEW request only. Telling the old
            // callback it was hidden while its view is still on screen would
            // desync the WebView's fullscreen state.
            callback.onCustomViewHidden()
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
        // statusBarColor and isStatusBarContrastEnforced are deprecated on
        // API 35 but remain the only way to set bar color on pre-edge-to-edge
        // devices.
        @Suppress("DEPRECATION")
        run {
            originalStatusBarColor = activity.window.statusBarColor
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                originalStatusBarContrastEnforced = activity.window.isStatusBarContrastEnforced
                activity.window.isStatusBarContrastEnforced = false
            }
            activity.window.statusBarColor = Color.BLACK
        }
    }

    override fun onHideCustomView() {
        if (customView == null) return
        val activity = activityRef.get()
        val localCustomView = customView
        val localCallback = customViewCallback

        customView = null
        customViewCallback = null

        if (activity == null) {
            localCallback?.onCustomViewHidden()
            return
        }

        if (!::fullscreenContainer.isInitialized || !::webview.isInitialized) {
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
        run {
            activity.window.statusBarColor = originalStatusBarColor
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                activity.window.isStatusBarContrastEnforced = originalStatusBarContrastEnforced
            }
        }
        localCallback?.onCustomViewHidden()
    }

    fun hideCustomView() {
        if (customView != null) {
            onHideCustomView()
        }
    }
}
