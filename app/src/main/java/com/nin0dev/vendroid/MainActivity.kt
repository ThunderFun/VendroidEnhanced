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
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import android.webkit.ValueCallback
import android.webkit.WebView
import android.webkit.WebChromeClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.gson.Gson
import com.nin0dev.vendroid.utils.Constants
import com.nin0dev.vendroid.utils.Logger.e
import com.nin0dev.vendroid.utils.UpdateData
import com.nin0dev.vendroid.webview.HttpClient
import com.nin0dev.vendroid.webview.HttpClient.fetchVencord
import com.nin0dev.vendroid.webview.VChromeClient
import com.nin0dev.vendroid.webview.VWebviewClient
import com.nin0dev.vendroid.webview.VencordNative
import java.io.IOException
import java.lang.ref.WeakReference
import java.util.concurrent.Executors
import java.time.LocalDate
import androidx.core.content.edit
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts


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

    private var loadingScreenDismissed = false
    private var loadingAnimationRunnable: Runnable? = null
    private var loadingTimeoutRunnable: Runnable? = null
    private var loadingDismissRunnable: Runnable? = null
    private val fetchExecutor = Executors.newSingleThreadExecutor()
    private var loadingAnimStartTime: Long = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var quickCssLayout: LinearLayout
    private lateinit var loadingScreenLayout: LinearLayout

    private fun setupQuickCss() {
        val saveButton = findViewById<Button>(R.id.save_css)
        val cssEditText = findViewById<TextInputEditText>(R.id.css)

        saveButton.setOnClickListener {
            wv!!.evaluateJavascript(
                "VencordNative.quickCss.set(${gson.toJson(cssEditText.text.toString())})", null
            )
            showDiscordToast("Saved QuickCSS", "SUCCESS")
            quickCssLayout.visibility = GONE
            loadingScreenLayout.visibility = GONE
            wv!!.visibility = VISIBLE
            currentFocus?.clearFocus();
        }
    }

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
        ed.putString(
            "clientMod",
            if (sPrefs.getBoolean("equicord", false)) "equicord" else "vencord"
        )
        ed.putString("splashScreen", sPrefs.getString("splash", "viggy"));

        ed.apply()
    }

    fun checkUpdates(ignoreSetting: Boolean = false) {
        return // Server ping disabled

        val sPrefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val today = LocalDate.now()
        val i = "${today.dayOfYear}${today.year}"
        val url = "https://vendroid.nin0.dev/api/updates?version=${BuildConfig.VERSION_CODE}${if (sPrefs.getString("lastDailyCheck", "") == i) "" else "&daily=true"}"
        sPrefs.edit { putString("lastDailyCheck", i) }

        fetchExecutor.execute {
            try {
                val conn = HttpClient.fetch(url)
                val response: String
                try {
                    response = HttpClient.readAsText(conn.inputStream, conn.contentLength.coerceAtLeast(8192))
                } finally {
                    conn.disconnect()
                }
                val updateData = gson.fromJson<UpdateData>(response, UpdateData::class.java) ?: return@execute

                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (updateData.update != null && sPrefs.getBoolean("checkVDEUpdates", true)) {
                        MaterialAlertDialogBuilder(this@MainActivity)
                            .setIcon(R.drawable.baseline_system_update_24)
                            .setTitle("An update is available (${updateData.update.title})")
                            .setMessage(updateData.update.text)
                            .setPositiveButton(getString(R.string.update)) { _, _ ->
                                val browserIntent = Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("https://github.com/nin0-dev/VendroidEnhanced/releases/latest/download/app-release.apk")
                                )
                                startActivity(browserIntent)
                            }
                            .setNegativeButton(getString(R.string.later)) { _, _ -> }
                            .show()
                    }
                    if (!sPrefs.getBoolean("checkAnnouncements", true) || ignoreSetting) return@runOnUiThread
                    val announcementsPrefs = getSharedPreferences("announcements", Context.MODE_PRIVATE)
                    updateData.announcements?.forEach { announcement ->
                        if (!announcementsPrefs.getBoolean(announcement.id.toString(), false)) {
                            MaterialAlertDialogBuilder(this@MainActivity)
                                .setIcon(R.drawable.campaign_24dp_000000)
                                .setTitle(announcement.title)
                                .setMessage(announcement.text)
                                .setPositiveButton("OK") { _, _ ->
                                    announcementsPrefs.edit().putBoolean(announcement.id.toString(), true).apply()
                                }
                                .show()
                        }
                    }
                }
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) {
                    e("Network error during update check", e)
                }
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Failed to check for updates", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun dismissLoadingScreen() {
        if (loadingScreenDismissed) return
        loadingScreenDismissed = true
        loadingAnimationRunnable?.let { mainHandler.removeCallbacks(it) }
        loadingAnimationRunnable = null
        loadingScreenLayout.animate()?.alpha(0f)?.setDuration(500)?.withEndAction {
            loadingScreenLayout.visibility = GONE
            loadingScreenLayout.alpha = 1f
        }?.start()
    }

    fun scheduleLoadingScreenDismiss(delayMs: Long) {
        if (loadingScreenDismissed) return
        val runnable = Runnable { dismissLoadingScreen() }
        loadingDismissRunnable = runnable
        mainHandler.postDelayed(runnable, delayMs)
    }

    private fun startLoadingAnimation() {
        val dots = intArrayOf(R.id.dot1, R.id.dot2, R.id.dot3).map { findViewById<View>(it) }
        loadingAnimStartTime = System.currentTimeMillis()

        dots.forEach { dot ->
            // Hardware layer caches each dot as a GPU texture — the scale/alpha
            // animation then becomes a pure GPU transform with zero draw calls.
            dot.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            dot.scaleX = 0.4f
            dot.scaleY = 0.4f
            dot.alpha = 0.3f
        }

        // 30fps animation via postDelayed — half the callback rate of the original 60fps (16ms),
        // reducing CPU overhead while remaining smooth enough for a loading indicator.
        // ValueAnimator was attempted but ofInt(0,1) with extreme duration fails to produce
        // timely frame callbacks.
        val runnable = object : Runnable {
            override fun run() {
                if (loadingScreenDismissed) return
                val elapsed = System.currentTimeMillis() - loadingAnimStartTime
                val cycleMs = 1200L
                dots.forEachIndexed { i, dot ->
                    val phase = (elapsed - i * 200L).toDouble()
                    val t = (phase % cycleMs) / cycleMs
                    val wave = (Math.sin(t * 2.0 * Math.PI - Math.PI / 2.0) + 1.0) / 2.0
                    val scale = 0.4f + wave.toFloat() * 0.6f
                    val alpha = 0.3f + wave.toFloat() * 0.7f
                    dot.scaleX = scale
                    dot.scaleY = scale
                    dot.alpha = alpha
                }
                loadingAnimationRunnable = this
                mainHandler.postDelayed(this, 33)
            }
        }
        runnable.run()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        migrateSettings()
        DynamicColors.applyToActivitiesIfAvailable(application)

        // Tell SurfaceFlinger the window content is fully opaque — skips
        // per-frame alpha compositing on the entire surface, freeing GPU
        // bandwidth for actual rendering work.
        window.setFormat(android.graphics.PixelFormat.OPAQUE)

        val sPrefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val editor = sPrefs.edit()

        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
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

        quickCssLayout = findViewById(R.id.quickcss)
        loadingScreenLayout = findViewById(R.id.loading_screen)

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

        // Intercept Service Worker fetch events (API 24+) — they bypass
        // WebViewClient.shouldInterceptRequest entirely.
        if (androidx.webkit.WebViewFeature.isFeatureSupported(
                androidx.webkit.WebViewFeature.SERVICE_WORKER_BASIC_USAGE)) {
            androidx.webkit.ServiceWorkerControllerCompat.getInstance()
                .setServiceWorkerClient(
                    object : androidx.webkit.ServiceWorkerClientCompat() {
                        @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.LOLLIPOP)
                        override fun shouldInterceptRequest(request: android.webkit.WebResourceRequest): android.webkit.WebResourceResponse? {
                            val host = request.url.host
                            return if (host != null && !Constants.isAllowedDomain(host)) {
                                android.webkit.WebResourceResponse(
                                    "text/plain", "utf-8",
                                    java.io.ByteArrayInputStream(ByteArray(0))
                                )
                            } else null
                        }
                    }
                )
        }

        setupQuickCss()
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
        @Suppress("DEPRECATION")
        s.databaseEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        s.setBuiltInZoomControls(false)
        // Use the wide viewport and overview mode so the page scales correctly
        // without extra re-layouts from viewport mismatch.
        s.setUseWideViewPort(true)
        s.setLoadWithOverviewMode(true)
        // Pin text zoom to 100% — device-level font scaling can cause reflows
        // and layout thrash on every touch that triggers a relayout.
        s.textZoom = 100

        // Remove over-scroll glow: it adds a GPU shader compositing pass on
        // every scroll-to-edge event. In a full-screen app this is pure overhead.
        wv!!.overScrollMode = View.OVER_SCROLL_NEVER

        // Native Android scrollbars are invisible in a full-screen SPA, but
        // View still allocates and composites them. Disabling removes that
        // per-frame draw cost.
        wv!!.isVerticalScrollBarEnabled = false
        wv!!.isHorizontalScrollBarEnabled = false

        // Prevent long-press from engaging Android text-selection machinery.
        // That adds native input pipeline state and delays touch-up events.
        wv!!.isLongClickable = false

        // Suppress haptic feedback: every long-press fires a sync IPC to the
        // vibrator service, stalling the UI thread for ~1-2 ms.
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
            // These reads must be synchronous — onPageStarted fires as soon as
            // the WebView begins navigating, and Vencord must be injected then.
            // Internal storage reads are <50ms; APK resource reads are even faster
            // (memory-mapped). The heavy work (network fetch) stays async.
            if (HttpClient.VencordMobileRuntime == null) {
                resources.openRawResource(R.raw.vencord_mobile).use { `is` ->
                    HttpClient.setVencordMobileRuntime(HttpClient.readAsText(`is`))
                }
            }
            synchronized(vencordRuntimeLock) {
                if (HttpClient.VencordRuntime == null) {
                    val needsRedownload = sPrefs.getInt("lastMajorUpdateThatUserHasUpdatedVencord", 0) < BuildConfig.VERSION_CODE
                    val vendroidFile = File(filesDir, "vencord.js")
                    if (needsRedownload) {
                        vendroidFile.delete()
                    } else if (vendroidFile.exists()) {
                        try {
                            HttpClient.setVencordRuntime(HttpClient.applyPatches(vendroidFile.readText()))
                        } catch (_: Exception) {}
                    }
                }
            }
            fetchExecutor.execute {
                try {
                    fetchVencord(this@MainActivity)
                } catch (_: IOException) {
                }
            }
        } else {
            Toast.makeText(this, "Safe mode enabled, Vencord won't be loaded", Toast.LENGTH_SHORT)
                .show()
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
                if (host != null && Constants.isDiscordDomain(host)) {
                    wv!!.loadUrl(lastUrl)
                    currentUrlForBridge = lastUrl
                    currentHostForBridge = host
                    lastUrl
                } else {
                    wv!!.loadUrl("https://discord.com/app")
                    "https://discord.com/app"
                }
            } else {
                wv!!.loadUrl("https://discord.com/app")
                "https://discord.com/app"
            }
        }
        currentUrlForBridge = initialUrl

        mainHandler.postDelayed({ checkUpdates() }, 3000)
        startLoadingAnimation()

        loadingTimeoutRunnable = Runnable {
            if (!loadingScreenDismissed) {
                dismissLoadingScreen()
            }
        }
        mainHandler.postDelayed(loadingTimeoutRunnable!!, 30000)

        wvInitialized = true
    }

    private fun handleUrl(url: Uri?) {
        if (url != null) {
            val host = url.host
            if (host == null || !Constants.isDiscordDomain(host)) return
            val path = url.path ?: ""
            currentUrlForBridge = url.toString()
            currentHostForBridge = host
            if (!wvInitialized || wv == null) {
                wv?.loadUrl(url.toString())
            } else {
                wv!!.evaluateJavascript(
                    "Vencord.Webpack.Common.NavigationRouter.transitionTo(${gson.toJson(path)})",
                    null
                )
            }
        }
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
            if (host != null && Constants.isDiscordDomain(host)) {
                getSharedPreferences("settings", Context.MODE_PRIVATE)
                    .edit() { putString("lastUrl", url) }
            }
        }
        wv?.onPause()
        wv?.pauseTimers()
        // When backgrounded, spoof document.hidden and pause all CSS animations
        // so Discord’s React app throttles itself and the compositor stops
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
        loadingTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        loadingDismissRunnable?.let { mainHandler.removeCallbacks(it) }
        loadingDismissRunnable = null
        loadingAnimationRunnable?.let { mainHandler.removeCallbacks(it) }
        loadingAnimationRunnable = null
        wv?.onPause()
        wv?.pauseTimers()
        wv?.stopLoading()
        wvInitialized = false
        (wv?.parent as? android.view.ViewGroup)?.removeView(wv)
        wv?.destroy()
        wv = null
        if (::vencordNative.isInitialized) vencordNative.shutdown()
        fetchExecutor.shutdownNow()
        super.onDestroy()
    }

    fun injectVencordIfReady() {
        val runtime: String?
        val mobileRuntime: String?
        synchronized(vencordRuntimeLock) {
            runtime = HttpClient.VencordRuntime
            mobileRuntime = HttpClient.VencordMobileRuntime
        }
        if (wv != null && runtime != null && mobileRuntime != null) {
            if (missedInjection) {
                missedInjection = false
                val url = currentUrlForBridge
                if (url != null && Constants.isDiscordDomain(Uri.parse(url).host ?: "")) {
                    wv?.reload()
                    return
                }
            }
            val script = buildString {
                runtime?.let { append(it).append(';') }
                mobileRuntime?.let { append(it).append(';') }
            }
            wv?.evaluateJavascript(script, null)
        }
    }

    fun showDiscordToast(message: String, type: String) {
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
