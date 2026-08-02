package com.nin0dev.vendroid

import android.annotation.SuppressLint
import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import java.io.File
import android.view.View
import android.webkit.ValueCallback
import android.webkit.WebView
import android.webkit.WebChromeClient
import android.widget.Toast
import com.google.android.material.color.DynamicColors
import com.google.gson.Gson
import com.nin0dev.vendroid.utils.Constants
import com.nin0dev.vendroid.utils.JsPatches
import com.nin0dev.vendroid.utils.Logger.e
import com.nin0dev.vendroid.utils.VDELog
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

    /** Cached URL for bridge-thread safety.  Updated on the UI thread in
     *  WebViewClient callbacks and onPause.  Bridge methods read this
     *  instead of calling wv.url directly. */
    @Volatile
    var currentUrlForBridge: String? = null
    @Volatile
    var currentHostForBridge: String? = null
    @Volatile
    var missedInjection = false
    /** Deep link received via onNewIntent during onCreate; applied once
     *  the WebView is initialized so it isn't overwritten by the initial
     *  URL load. */
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
    private val fetchExecutor = Executors.newSingleThreadExecutor()

    private fun migrateSettings() {
        val sPrefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (sPrefs.getBoolean("migratedSettings", false)) return;
        val ed = sPrefs.edit()
        ed.putBoolean("migratedSettings", true);

        ed.putBoolean("checkVDEUpdates", sPrefs.getBoolean("checkVendroidUpdates", true))
        ed.putBoolean(
            "checkAnnouncements",
            sPrefs.getBoolean("checkVendroidUpdates", true)
        )
        // Derive clientMod from the legacy boolean only if not already set;
        // re-runs (reinstall/flag wipe) must not clobber an existing choice.
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

    fun dismissLoadingScreen() = loadingScreenManager.dismiss()
    fun scheduleLoadingScreenDismiss(delayMs: Long) = loadingScreenManager.scheduleDismiss(delayMs)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        VDELog.i("Main", "onCreate()")
        if (!getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("migratedSettings", false)) {
            migrateSettings()
        }
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            DynamicColors.applyToActivitiesIfAvailable(application)
        }, 2000)

        window.setFormat(android.graphics.PixelFormat.OPAQUE)

        val sPrefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val editor = sPrefs.edit()

        // WebView debugging exposes the page (cookies, token, JS context) to any
        // attached debugger. Gate it behind an explicit build flag; never in dev/release.
        WebView.setWebContentsDebuggingEnabled(BuildConfig.ALLOW_WEBVIEW_DEBUGGING)
        setContentView(R.layout.activity_main)
        WindowCompat.setDecorFitsSystemWindows(window, true)

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (wv != null) {
                    val isFullscreen = chromeClient.isFullscreen
                    if (isFullscreen) {
                        chromeClient.hideCustomView()
                        return
                    }
                    wv!!.evaluateJavascript("VencordMobile.onBackPress()") { r: String ->
                        if ("false" == r) {
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

        loadingScreenManager = LoadingScreenManager(this, findViewById(R.id.loading_screen))

        // Use the pre-warmed WebView from VendroidApp if available — it already
        // has the Chromium renderer process initialized, eliminating ~200-500ms
        // of cold-start latency. Otherwise fall back to the layout-inflated one.
        val prewarmed = VendroidApp.prewarmedWebView
        if (prewarmed != null) {
            val xmlWv = findViewById<WebView>(R.id.webview)
            val parent = xmlWv?.parent as? android.view.ViewGroup
            val params = xmlWv?.layoutParams
            if (parent != null && params != null) {
                val index = parent.indexOfChild(xmlWv)
                parent.removeView(xmlWv)
                prewarmed.id = R.id.webview
                prewarmUsed = true
                // The pre-warmed WebView has no layout params yet — carry over
                // the same width/height/background from the XML definition.
                prewarmed.setBackgroundColor(android.graphics.Color.parseColor("#121214"))
                parent.addView(prewarmed, index, params)
            }
            VendroidApp.prewarmedWebView = null
            wv = prewarmed
        } else {
            wv = findViewById(R.id.webview)!!
        }

        chromeClient = VChromeClient(this)
        val webViewClient = VWebviewClient(this)
        wv!!.setWebViewClient(webViewClient)
        wv!!.setWebChromeClient(chromeClient)

        // Sync the typing indicator toggle to the WebView client. Read once
        // at startup into a @Volatile field; shouldInterceptRequest reads
        // that field instead of SharedPreferences per request.
        val blockTyping = sPrefs.getBoolean("vendroid_blockTypingIndicator", false)
        VWebviewClient.updateTypingBlock(blockTyping)

        // Sync the external-link confirmation toggle to the link popup.
        val confirmLinks = sPrefs.getBoolean("vendroid_confirmExternalLinks", true)
        com.nin0dev.vendroid.webview.LinkHandler.updateConfirmExternalLinks(confirmLinks)

        // Intercept Service Worker fetch events (API 24+) — they bypass
        // WebViewClient.shouldInterceptRequest entirely.
        if (androidx.webkit.WebViewFeature.isFeatureSupported(
                androidx.webkit.WebViewFeature.SERVICE_WORKER_BASIC_USAGE)) {
            androidx.webkit.ServiceWorkerControllerCompat.getInstance()
                .setServiceWorkerClient(
                    object : androidx.webkit.ServiceWorkerClientCompat() {
                        @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.LOLLIPOP)
                        override fun shouldInterceptRequest(request: android.webkit.WebResourceRequest): android.webkit.WebResourceResponse? {
                            // Restrict to browser/inline schemes; data:, file:,
                            // and custom schemes have a null host and would
                            // otherwise bypass the domain allowlist.
                            val scheme = request.url.scheme
                            if (scheme != "https" && scheme != "http" && scheme != "blob" && scheme != "data") {
                                return android.webkit.WebResourceResponse(
                                    "text/plain", "utf-8",
                                    java.io.ByteArrayInputStream(ByteArray(0))
                                )
                            }
                            if (scheme == "http") {
                                return android.webkit.WebResourceResponse(
                                    "text/plain", "utf-8",
                                    java.io.ByteArrayInputStream(ByteArray(0))
                                )
                            }
                            val host = request.url.host
                            if (host != null && !Constants.isAllowedDomain(host)) {
                                return android.webkit.WebResourceResponse(
                                    "text/plain", "utf-8",
                                    java.io.ByteArrayInputStream(ByteArray(0))
                                )
                            }
                            // Apply the same privacy path filter as
                            // VWebviewClient so SW-fetched telemetry/Sentry
                            // requests can't bypass it.
                            VWebviewClient.shouldBlockForPrivacy(host, request.url.path)?.let { return it }
                            return null
                        }
                    }
                )
        }

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
        // when the WebView is not visible. This prevents the OS from killing
        // or throttling the renderer process, keeping touch response fast.
        wv!!.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)

        // Disable Safe Browsing
        if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.SAFE_BROWSING_ENABLE)) {
            androidx.webkit.WebSettingsCompat.setSafeBrowsingEnabled(s, false)
        }

        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(wv!!, false)

        if (!sPrefs.getBoolean("safeMode", false)) {
            vencordNative = VencordNative(WeakReference(this), wv!!)
            wv?.addJavascriptInterface(vencordNative, "VencordMobileNative")
            // These reads are now NO-OPs in the happy path because
            // VendroidApp.onCreate() already loaded them on a background
            // thread.  They remain here as a safety net for process-death
            // paths where the Application object is recreated.
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
                    null
                } else if (vendroidFile.exists()) {
                    try { vendroidFile.readText() } catch (e: Exception) { e("Failed to read vendroidFile", e); null }
                } else null
            } else null
            fileContent?.let {
                synchronized(vencordRuntimeLock) {
                    if (HttpClient.VencordRuntime == null) {
                        try {
                            // The file was written with applyPatches already
                            // applied during a previous download.  Skip the
                            // redundant ~1MB regex scan.
                            HttpClient.setVencordRuntime(
                                if (HttpClient.vencordBundlePatched) it
                                else HttpClient.applyPatches(it)
                            )
                        } catch (e: Exception) {
                            e("applyPatches failed", e)
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
                    e("fetchVencord failed", e)
                }
            }
        } else {
            Toast.makeText(this, "Safe mode enabled, Vencord won't be loaded", Toast.LENGTH_SHORT)
                .show()
            VDELog.w("Main", "Safe mode enabled — Vencord will not load")
            editor.putBoolean("safeMode", false)
            editor.apply()
        }

        val intent = intent
        val initialUrl: String = if (intent.action == Intent.ACTION_VIEW) {
            val data = intent.data
            if (data != null) {
                handleUrl(intent.data)
                data.toString()
            } else {
                "https://discord.com/app"
            }
        } else {
            val lastUrl = sPrefs.getString("lastUrl", null)
            if (lastUrl != null) {
                val host = Uri.parse(lastUrl).host
                if (host != null && Constants.isDiscordDomain(host) && isAppResumeUrl(lastUrl)) {
                    wv!!.loadUrl(lastUrl)
                    currentUrlForBridge = lastUrl
                    currentHostForBridge = host
                    lastUrl
                } else {
                    // Stale non-app URL (e.g. /blog/...) — fall back to /app
                    // rather than reloading a page with no history to go back to.
                    wv!!.loadUrl("https://discord.com/app")
                    "https://discord.com/app"
                }
            } else {
                wv!!.loadUrl("https://discord.com/app")
                "https://discord.com/app"
            }
        }
        currentUrlForBridge = initialUrl


        loadingScreenManager.start()
        loadingScreenManager.scheduleTimeout(30000)

        wvInitialized = true

        // Apply a deep link stashed by onNewIntent during onCreate.
        pendingDeepLink?.let { link ->
            pendingDeepLink = null
            handleUrl(Uri.parse(link))
        }
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
            } else {
                wv!!.evaluateJavascript(
                    "Vencord.Webpack.Common.NavigationRouter.transitionTo(${gson.toJson(path)})",
                    null
                )
            }
        }
    }

    private fun isAppResumeUrl(url: String): Boolean {
        val path = Uri.parse(url).path ?: return false
        return path == "/app" ||
            path.startsWith("/channels") ||
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
            // with an empty history, hardlocking the user there.
            if (host != null && Constants.isDiscordDomain(host) && isAppResumeUrl(url)) {
                getSharedPreferences("settings", Context.MODE_PRIVATE)
                    .edit() { putString("lastUrl", url) }
            }
        }
        wv?.onPause()
        wv?.pauseTimers()
        // When backgrounded, spoof document.hidden and pause all CSS animations
        // so Discord's React app throttles itself and the compositor stops
        // doing useless GPU work.  Combined with pauseTimers() this eliminates
        // the vast majority of background CPU/GPU churn.
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
        loadingScreenManager.cleanup()
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
                if (url != null && Constants.isDiscordDomain(Uri.parse(url).host ?: "")) {
                    wv?.reload()
                    return
                }
            }
            // Only inject on Discord pages; the runtimes are designed for
            // Discord and should not run on whitelisted non-Discord pages.
            val url = currentUrlForBridge
            if (url == null || !Constants.isDiscordDomain(Uri.parse(url).host ?: "")) return
            wv?.evaluateJavascript(runtime + ";", null)
            wv?.evaluateJavascript(mobileRuntime + ";", null)
        }
    }

    fun showDiscordToast(message: String, type: String) {
        // message is JSON-encoded via gson.toJson before interpolation, but type
        // is concatenated raw. Keep the allowList strict; widening it would allow
        // JS injection via the unencoded type value.
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
