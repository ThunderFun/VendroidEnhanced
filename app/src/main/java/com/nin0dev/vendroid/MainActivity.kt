package com.nin0dev.vendroid

import android.annotation.SuppressLint
import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import java.io.File
import android.view.View
import android.webkit.ValueCallback
import android.webkit.WebView
import android.webkit.WebChromeClient
import android.widget.Toast
import com.google.gson.Gson
import com.nin0dev.vendroid.utils.Constants
import com.nin0dev.vendroid.utils.VDELog
import com.nin0dev.vendroid.ui.LoadingScreenManager
import com.nin0dev.vendroid.webview.HttpClient
import com.nin0dev.vendroid.webview.HttpClient.fetchVencord
import com.nin0dev.vendroid.webview.VChromeClient
import com.nin0dev.vendroid.webview.VWebviewClient
import com.nin0dev.vendroid.webview.VencordNative
import java.io.IOException
import java.lang.ref.WeakReference
import java.util.concurrent.Executors
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.edit

class MainActivity : AppCompatActivity() {
    private var wvInitialized = false
    private var prewarmUsed = false
    private var wv: WebView? = null

    /** Cached URL for bridge-thread safety: updated on the UI thread and read
     *  off-thread instead of calling wv.url directly. */
    @Volatile
    var currentUrlForBridge: String? = null
    @Volatile
    var currentHostForBridge: String? = null
    /** True between a main-frame commit and onPageFinished. Lets the bridge's
     *  strict domain check skip the UI-thread round trip in steady state. */
    @Volatile
    var navigationInProgress = false
    @Volatile
    var missedInjection = false
    /** Deep link received via onNewIntent during onCreate; applied once the
     *  WebView is initialized so the initial load does not overwrite it. */
    @Volatile
    private var pendingDeepLink: String? = null
    private lateinit var chromeClient: VChromeClient
    private lateinit var vencordNative: VencordNative

    @JvmField
    var filePathCallback: ValueCallback<Array<Uri>>? = null

    val fileChooserLauncher: ActivityResultLauncher<Intent> = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = filePathCallback
        filePathCallback = null
        if (callback == null) return@registerForActivityResult
        if (result.data == null) {
            callback.onReceiveValue(null)
            return@registerForActivityResult
        }
        val resultArray = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data!!)
        callback.onReceiveValue(resultArray)
    }

    private lateinit var loadingScreenManager: LoadingScreenManager

    /** Public so the WebView/JS layers can schedule/dismiss the loading screen
     *  directly without a pass-through facade on the activity. */
    val loadingScreen: LoadingScreenManager get() = loadingScreenManager

    private val fetchExecutor = Executors.newSingleThreadExecutor()

    private fun migrateSettings() {
        val sPrefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (sPrefs.getBoolean("migratedSettings", false)) return;
        val ed = sPrefs.edit()
        ed.putBoolean("migratedSettings", true);

        ed.putBoolean("checkVDEUpdates", sPrefs.getBoolean("checkVendroidUpdates", true))
        // Both toggles were historically controlled by the single legacy
        // checkVendroidUpdates flag; keep them in sync during migration so an
        // existing user does not silently lose one.
        ed.putBoolean(
            "checkAnnouncements",
            sPrefs.getBoolean("checkVendroidUpdates", true)
        )
        // Derive clientMod from the legacy boolean only if unset; re-runs
        // (reinstall/flag wipe) must not clobber an existing choice.
        if (!sPrefs.contains("clientMod")) {
            ed.putString(
                "clientMod",
                if (sPrefs.getBoolean("equicord", false)) "equicord" else "vencord"
            )
        }

        ed.remove("checkVendroidUpdates")
        ed.remove("equicord")
        ed.remove("splash")

        ed.apply()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        VDELog.i("Main", "onCreate()")
        // Load settings once and reuse throughout onCreate.
        val sPrefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (!sPrefs.getBoolean("migratedSettings", false)) {
            migrateSettings()
        }

        // First-run security disclosure. Do not load Discord, the WebView, or
        // any injected code until the user accepts the risks of a modified
        // Discord client running third-party code.
        if (!sPrefs.getBoolean("riskWarningAccepted", false)) {
            showFirstRunWarning(sPrefs)
            return
        }
        proceedWithStartup(sPrefs)
    }

    /**
     * Shows the first-run security warning. Blocks app startup until the user
     * explicitly accepts the risks; declining (back/dismiss) closes the app.
     * Persists acceptance so this only shows once.
     */
    private fun showFirstRunWarning(sPrefs: SharedPreferences) {
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle(R.string.risk_warning_title)
            .setMessage(R.string.risk_warning_body)
            .setCancelable(false) // block bypass via outside tap / back
            .setPositiveButton(R.string.risk_warning_accept) { _, _ ->
                sPrefs.edit().putBoolean("riskWarningAccepted", true).apply()
                proceedWithStartup(sPrefs)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .create()
        dialog.setOnDismissListener { if (!sPrefs.getBoolean("riskWarningAccepted", false)) finish() }
        dialog.show()
    }

    /** The body of the original onCreate, run only after the risk warning is
     *  accepted. */
    private fun proceedWithStartup(sPrefs: SharedPreferences) {
        window.setFormat(android.graphics.PixelFormat.OPAQUE)

        val editor = sPrefs.edit()

        // WebView debugging exposes the page (cookies, token, JS context) to
        // any attached debugger. Gate it behind an explicit build flag.
        WebView.setWebContentsDebuggingEnabled(BuildConfig.ALLOW_WEBVIEW_DEBUGGING)
        setContentView(R.layout.activity_main)
        WindowCompat.setDecorFitsSystemWindows(window, true)

        setupBackPress()

        loadingScreenManager = LoadingScreenManager(this, findViewById(R.id.loading_screen))
        // Start the animation now so the splash does not freeze during WebView setup.
        loadingScreenManager.start()
        loadingScreenManager.scheduleTimeout(30000)

        installWebView(sPrefs)
        syncFeatureToggles(sPrefs)
        configureServiceWorker()

        loadVencordRuntimes(sPrefs, editor)

        val initialUrl = resolveInitialUrl(sPrefs, intent)
        currentUrlForBridge = initialUrl

        wvInitialized = true

        // Apply a deep link stashed by onNewIntent during onCreate.
        pendingDeepLink?.let { link ->
            pendingDeepLink = null
            handleUrl(Uri.parse(link))
        }
    }

    /** Registers the back-press handler that proxies to the Discord JS app. */
    private fun setupBackPress() {
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (wv != null) {
                    val isFullscreen = chromeClient.isFullscreen
                    if (isFullscreen) {
                        chromeClient.hideCustomView()
                        return
                    }
                    wv!!.evaluateJavascript("VencordMobile.onBackPress()") { r ->
                        // "true" = JS handled it (closed a modal etc.). Anything
                        // else (null on a JS error in safe mode or before the
                        // runtime loads) falls through to the default back
                        // action so the user is never stuck.
                        if ("true" != r) {
                            isEnabled = false
                            onBackPressedDispatcher.onBackPressed()
                            isEnabled = true
                        }
                    }
                    return
                }
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        })
    }

    /** Installs the real WebView and wires the WebView/Chrome clients and
     *  settings. */
    private fun installWebView(sPrefs: SharedPreferences) {
        // The layout's @id/webview is a plain View placeholder so setContentView
        // does not inflate a WebView (Chromium init) before clients are wired up.
        val placeholder = findViewById<View>(R.id.webview)
        val parent = placeholder?.parent as? android.view.ViewGroup
        val params = placeholder?.layoutParams
        val index = if (parent != null) parent.indexOfChild(placeholder) else -1
        val prewarmed = VendroidApp.prewarmedWebView
        wv = if (prewarmed != null) {
            prewarmUsed = true
            prewarmed
        } else {
            WebView(this)
        }
        wv!!.setBackgroundColor(android.graphics.Color.parseColor("#121214"))
        // Keep the id so VChromeClient / VencordNative still find the WebView.
        wv!!.id = R.id.webview
        if (parent != null && params != null && index >= 0) {
            parent.removeView(placeholder)
            parent.addView(wv, index, params)
        }
        VendroidApp.prewarmedWebView = null

        chromeClient = VChromeClient(this)
        val webViewClient = VWebviewClient(this)
        wv!!.setWebViewClient(webViewClient)
        wv!!.setWebChromeClient(chromeClient)

        if (sPrefs.getBoolean("desktopMode", false)) {
            wv!!.settings.userAgentString =
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
        }
        val s = wv!!.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.allowFileAccess = false
        s.allowContentAccess = false

        s.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
        s.mediaPlaybackRequiresUserGesture = false
        s.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        s.setBuiltInZoomControls(false)
        s.setUseWideViewPort(true)
        s.setLoadWithOverviewMode(true)
        s.textZoom = 100
        // Pre-rasterize offscreen tiles during scroll/fling so new content
        // appears painted when scrolled into view. Modest GPU memory cost,
        // visibly smoother scrolling on long message lists.
        s.offscreenPreRaster = true

        wv!!.overScrollMode = View.OVER_SCROLL_NEVER
        wv!!.isVerticalScrollBarEnabled = false
        wv!!.isHorizontalScrollBarEnabled = false
        wv!!.isLongClickable = false
        wv!!.isHapticFeedbackEnabled = false
        wv!!.isScrollContainer = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            wv!!.defaultFocusHighlightEnabled = false
        }

        // Keep the Chromium renderer at IMPORTANT priority and never waive it
        // when hidden, so the OS cannot kill or throttle it and touch stays fast.
        wv!!.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)

        // Disable Safe Browsing
        if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.SAFE_BROWSING_ENABLE)) {
            androidx.webkit.WebSettingsCompat.setSafeBrowsingEnabled(s, false)
        }

        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(wv!!, false)
    }

    /** Syncs the feature-toggle flags (read once at startup) to the live
     *  seams. */
    private fun syncFeatureToggles(sPrefs: SharedPreferences) {
        // Read into a @Volatile field so shouldInterceptRequest does not hit
        // SharedPreferences per request.
        val blockTyping = sPrefs.getBoolean("vendroid_blockTypingIndicator", false)
        VWebviewClient.updateTypingBlock(blockTyping)

        // Sync the external-link confirmation toggle to the link popup.
        val confirmLinks = sPrefs.getBoolean("vendroid_confirmExternalLinks", true)
        com.nin0dev.vendroid.webview.LinkHandler.updateConfirmExternalLinks(confirmLinks)
    }

    /** Intercepts Service Worker fetch events (API 24+), which bypass
     *  WebViewClient.shouldInterceptRequest entirely. */
    private fun configureServiceWorker() {
        if (androidx.webkit.WebViewFeature.isFeatureSupported(
                androidx.webkit.WebViewFeature.SERVICE_WORKER_BASIC_USAGE)) {
            androidx.webkit.ServiceWorkerControllerCompat.getInstance()
                .setServiceWorkerClient(
                    object : androidx.webkit.ServiceWorkerClientCompat() {
                        @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.LOLLIPOP)
                        override fun shouldInterceptRequest(request: android.webkit.WebResourceRequest): android.webkit.WebResourceResponse? {
                            // Reuse the shared gate so the SW path cannot drift
                            // from the WebView client.
                            return VWebviewClient.shouldBlockForRequest(request)
                        }
                    }
                )
        }
    }

    /** Loads the Vencord runtimes (bridge + JS bundle) unless safe mode is
     *  on. */
    private fun loadVencordRuntimes(sPrefs: SharedPreferences, editor: SharedPreferences.Editor) {
        if (!sPrefs.getBoolean("safeMode", false)) {
            vencordNative = VencordNative(WeakReference(this), wv!!)
            wv?.addJavascriptInterface(vencordNative, "VencordMobileNative")
            // These reads are usually no-ops because VendroidApp.onCreate()
            // already loaded them on a background thread. They remain a safety
            // net for process-death paths where the Application is recreated.
            if (HttpClient.VencordMobileRuntime == null) {
                resources.openRawResource(R.raw.vencord_mobile).use { inputStream ->
                    HttpClient.setVencordMobileRuntime(HttpClient.readAsText(inputStream))
                }
            }
            val vendroidFile = File(filesDir, "vencord.js")
            val fileContent: String? = if (HttpClient.VencordRuntime == null) {
                val needsRedownload = sPrefs.getInt("lastMajorUpdateThatUserHasUpdatedVencord", 0) < BuildConfig.VERSION_CODE
                if (needsRedownload) {
                    vendroidFile.delete()
                    sPrefs.edit().remove(HttpClient.PREF_BUNDLE_PATCHED).apply()
                    null
                } else if (vendroidFile.exists()) {
                    try { HttpClient.readBundleFromDisk(sPrefs, vendroidFile) }
                    catch (e: Exception) { VDELog.e("Main", "Failed to read vendroidFile", e); null }
                } else null
            } else null
            fileContent?.let {
                synchronized(vencordRuntimeLock) {
                    // readBundleFromDisk already applied patches if needed, so
                    // this is just a synchronized publish of the ready bundle.
                    if (HttpClient.VencordRuntime == null) {
                        try {
                            HttpClient.setVencordRuntime(it)
                        } catch (e: Exception) {
                            VDELog.e("Main", "publishing Vencord runtime failed", e)
                        }
                    }
                }
            }
            val weakSelf = WeakReference(this)
            fetchExecutor.execute {
                val act = weakSelf.get()
                if (act == null || act.isFinishing || act.isDestroyed) return@execute
                try {
                    fetchVencord(act)
                } catch (e: IOException) {
                    VDELog.e("Main", "fetchVencord failed", e)
                }
            }
        } else {
            Toast.makeText(this, "Safe mode enabled, Vencord won't be loaded", Toast.LENGTH_SHORT)
                .show()
            VDELog.w("Main", "Safe mode enabled — Vencord will not load")
            editor.putBoolean("safeMode", false)
            editor.apply()
        }
    }

    /** Resolves the initial URL from a deep link intent or the last resume
     *  URL. */
    private fun resolveInitialUrl(sPrefs: SharedPreferences, intent: Intent): String {
        if (intent.action == Intent.ACTION_VIEW) {
            val data = intent.data
            val host = data?.host
            if (host != null && Constants.isDiscordDomain(host)) {
                val target = data.toString()
                // Route through NavigationPolicy so path rules (e.g. /blog ->
                // popup) apply to deep links like in-WebView navigations,
                // instead of bypassing them via a direct loadUrl.
                if (com.nin0dev.vendroid.webview.NavigationPolicy.decide(data, true)
                    == com.nin0dev.vendroid.webview.NavigationPolicy.Action.LOAD_IN_WEBVIEW) {
                    wv!!.loadUrl(target)
                    currentUrlForBridge = target
                    currentHostForBridge = host
                    return target
                }
                // Path/domain rule triggered (e.g. /blog or a CDN host): route
                // to the link popup and land on the app shell.
                com.nin0dev.vendroid.webview.LinkHandler(this).showLinkPopup(data)
                currentUrlForBridge = "https://discord.com/app"
                currentHostForBridge = "discord.com"
                wv!!.loadUrl("https://discord.com/app")
                return "https://discord.com/app"
            }
            // Non-Discord deep link (or no data): load the default app shell.
            wv!!.loadUrl("https://discord.com/app")
            return "https://discord.com/app"
        }
        val lastUrl = sPrefs.getString("lastUrl", null)
        if (lastUrl != null) {
            val host = Uri.parse(lastUrl).host
            if (host != null && Constants.isDiscordDomain(host) && isAppResumeUrl(lastUrl)) {
                wv!!.loadUrl(lastUrl)
                currentUrlForBridge = lastUrl
                currentHostForBridge = host
                return lastUrl
            }
            // Stale non-app URL (e.g. /blog/...): fall back to /app rather
            // than reloading a page with no back history.
            wv!!.loadUrl("https://discord.com/app")
            return "https://discord.com/app"
        }
        wv!!.loadUrl("https://discord.com/app")
        return "https://discord.com/app"
    }

    private fun handleUrl(url: Uri?) {
        if (url != null) {
            val host = url.host
            if (host == null || !Constants.isDiscordDomain(host)) return
            val path = url.path ?: ""
            currentUrlForBridge = url.toString()
            currentHostForBridge = host
            if (!wvInitialized || wv == null) {
                // Defer until onCreate finishes; loadUrl now would be
                // overwritten by the initial-URL load.
                pendingDeepLink = url.toString()
            } else if (HttpClient.VencordMobileRuntime == null) {
                // Runtime not injected into this page yet; transitionTo would
                // no-op against a page without Vencord. Defer until the
                // runtimes are injected (see injectVencordIfReady).
                pendingDeepLink = url.toString()
            } else {
                // Guarded so a page without Vencord (safe mode / failed load)
                // fails silently instead of throwing a ReferenceError.
                wv!!.evaluateJavascript(
                    "if(window.Vencord&&Vencord.Webpack&&Vencord.Webpack.Common)" +
                        "{Vencord.Webpack.Common.NavigationRouter.transitionTo(${gson.toJson(path)})}",
                    null
                )
            }
        }
    }

    private fun isAppResumeUrl(url: String): Boolean {
        val path = Uri.parse(url).path ?: return false
        return path == "/app" ||
            // "/channels" must match "/channels" and "/channels/..." but not
            // "/channelssomething" (mirrors MainFrameDiskCache.isCacheableRoute).
            path == "/channels" || path.startsWith("/channels/") ||
            path.startsWith("/library") ||
            path.startsWith("/store") ||
            path.startsWith("/friends")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_VIEW) {
            intent.data?.let { handleUrl(it) }
        }
    }

    override fun onPause() {
        val url = wv?.url
        if (url != null) {
            currentUrlForBridge = url
            currentHostForBridge = Uri.parse(url).host
            val host = currentHostForBridge
            // Only persist URLs the app can resume into (channels, DMs, /app).
            // Saving a non-app page (e.g. /blog/...) would reload it on restart
            // with empty history, hardlocking the user there.
            if (host != null && Constants.isDiscordDomain(host) && isAppResumeUrl(url)) {
                getSharedPreferences("settings", Context.MODE_PRIVATE)
                    .edit() { putString("lastUrl", url) }
            }
        }
        wv?.onPause()
        wv?.pauseTimers()
        // When backgrounded, spoof document.hidden and pause CSS animations so
        // Discord's React app throttles and the compositor stops wasted GPU
        // work. With pauseTimers() this removes most background CPU/GPU churn.
        wv?.evaluateJavascript(
            "if(window.__vendroidSetVisibility)window.__vendroidSetVisibility('hidden');" +
            "if(window.__vendroidPauseAnimations)window.__vendroidPauseAnimations()",
            null
        )
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        // Restore visibility spoofing before resuming timers/rendering so
        // Discord sees the page as foregrounded immediately.
        wv?.evaluateJavascript(
            "if(window.__vendroidSetVisibility)window.__vendroidSetVisibility('visible');" +
            "if(window.__vendroidResumeAnimations)window.__vendroidResumeAnimations()",
            null
        )
        wv?.onResume()
        wv?.resumeTimers()
    }

    override fun onDestroy() {
        // loadingScreenManager is set only once startup passes the first-run
        // risk warning. If the user declines, or the activity is destroyed
        // while the warning is shown, it is never set and must not be touched.
        if (::loadingScreenManager.isInitialized) loadingScreenManager.cleanup()
        wv?.onPause()
        wv?.pauseTimers()
        wv?.stopLoading()
        wvInitialized = false
        (wv?.parent as? android.view.ViewGroup)?.removeView(wv)
        wv?.destroy()
        wv = null
        if (::vencordNative.isInitialized) vencordNative.shutdown()
        fetchExecutor.shutdownNow()
        if (!prewarmUsed) {
            VendroidApp.destroyPrewarmedWebViewIfUnused()
        }
        super.onDestroy()
    }

    fun injectVencordIfReady() {
        val runtime: String?
        val mobileRuntime: String?
        synchronized(vencordRuntimeLock) {
            runtime = HttpClient.VencordRuntime
            mobileRuntime = HttpClient.VencordMobileRuntime
        }
        VDELog.i("Main", "Injecting Vencord runtime (${runtime?.length ?: 0} chars, mobile=${mobileRuntime?.length ?: 0} chars)")
        if (wv != null && runtime != null && mobileRuntime != null) {
            if (missedInjection) {
                missedInjection = false
                VDELog.w("Main", "Missed injection, scheduling reload")
                val url = currentUrlForBridge
                if (url != null && Constants.isDiscordAppOrigin(Uri.parse(url).host ?: "")) {
                    wv?.reload()
                    return
                }
            }
            // Only inject on Discord pages; the runtimes are designed for
            // Discord and must not run on whitelisted non-Discord pages.
            val url = currentUrlForBridge
            if (url == null || !Constants.isDiscordAppOrigin(Uri.parse(url).host ?: "")) return
            // Capability-token bootstrap must run first so the token is in
            // scope (closure-captured, not a window global) before the runtimes
            // call the bridge.
            wv?.evaluateJavascript(VencordNative.bridgeBootstrapJs() + ";", null)
            wv?.evaluateJavascript(runtime + ";", null)
            wv?.evaluateJavascript(mobileRuntime + ";", null)
            // Route any deep link that arrived before the runtime was ready.
            pendingDeepLink?.let { link ->
                pendingDeepLink = null
                handleUrl(Uri.parse(link))
            }
        }
    }

    /** True while a video/movie is in fullscreen custom view, so the overlay's
     *  status-bar color management can skip and avoid clobbering fullscreen. */
    fun isVideoFullscreen(): Boolean =
        ::chromeClient.isInitialized && chromeClient.isFullscreen

    fun showDiscordToast(message: String, type: String) {
        // message is JSON-encoded via gson.toJson before interpolation, but type
        // is concatenated raw. Keep the allowList strict; widening it would
        // allow JS injection via the unencoded type.
        val allowedTypes = setOf("SUCCESS", "ERROR", "INFO", "WARN")
        val safeType = if (type in allowedTypes) type else "INFO"
        wv?.post(Runnable {
            wv?.evaluateJavascript(
                "toasts=Vencord.Webpack.Common.Toasts; toasts.show({id: toasts.genId(), message: ${gson.toJson(message)}, type: toasts.Type.$safeType, options: {position: toasts.Position.BOTTOM,}})",
                null
            )
        })
    }

    companion object {
        private val gson = Gson()
        private val vencordRuntimeLock = Any()
    }
}
