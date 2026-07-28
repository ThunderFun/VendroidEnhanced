package com.nin0dev.vendroid.webview

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View.GONE
import android.view.View.VISIBLE
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.content.edit
import com.google.android.material.textfield.TextInputEditText
import com.nin0dev.vendroid.MainActivity
import com.nin0dev.vendroid.R
import com.nin0dev.vendroid.utils.Constants
import com.nin0dev.vendroid.utils.FirewallConfig
import com.nin0dev.vendroid.utils.Logger.e
import com.nin0dev.vendroid.utils.Logger.w
import com.nin0dev.vendroid.utils.VDELog
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class VencordNative(private val activity: WeakReference<MainActivity>, wv: WebView) {
    private val wvRef: WeakReference<WebView> = WeakReference(wv)

    companion object {
        private const val CSS_CACHE_TTL_MS = 7 * 24 * 60 * 60 * 1000L
        private const val CSS_CACHE_MAX_KEYS = 32
        private val ICON_NAMES = setOf("Main", "Jolly", "Discord", "Retro", "TS12")
        @Volatile
        private var currentIcon: String = "Main"
        private val iconLock = Any()
        private val gson = com.google.gson.Gson()

        private fun resolveCurrentIcon(activity: MainActivity?): String {
            val act = activity ?: return "Main"
            val pm = act.packageManager
            val pkg = act.applicationContext
            for (name in ICON_NAMES) {
                val state = pm.getComponentEnabledSetting(
                    ComponentName(pkg, "com.nin0dev.vendroid.${name}MainActivity")
                )
                if (state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                    return name
                }
                // Main is enabled by default in the manifest; if it was never
                // explicitly toggled getComponentEnabledSetting returns DEFAULT.
                if (state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && name == "Main") {
                    return "Main"
                }
            }
            return "Main"
        }

        fun initCurrentIcon(activity: MainActivity?) {
            synchronized(iconLock) {
                if (currentIcon == "Main") {
                    currentIcon = resolveCurrentIcon(activity)
                }
            }
        }
    }

    init {
        initCurrentIcon(activity.get())
    }

    @Volatile
    var overlayActive = false
        private set

    @Volatile
    private var logsDialogActive = false

    @Volatile
    private var firewallDialogActive = false

    private var originalStatusBarColor: Int? = null
    private var originalNavBarColor: Int? = null

    // Eagerly initialize SharedPreferences in the constructor (which runs on
    // the main thread during WebView setup) instead of lazily on the JS bridge
    // thread. The first getSharedPreferences() call reads+parses the XML file
    // from disk, which can take 50-100ms on eMMC. Doing this on the bridge
    // thread would stall ALL @JavascriptInterface methods.
    private val settingsPrefs: SharedPreferences? = activity.get()
        ?.getSharedPreferences("settings", Context.MODE_PRIVATE)

    // Dedicated SharedPreferences for CSS cache entries.  Isolating CSS from
    // the main "settings" prefs avoids rewriting the entire settings XML on
    // every CSS write and keeps the settings file small (faster cold-start
    // parse, no contention between CSS churn and actual settings).
    private val cssCachePrefs: SharedPreferences? = activity.get()
        ?.getSharedPreferences("css_cache", Context.MODE_PRIVATE)

    private val executor = Executors.newSingleThreadExecutor()

    private var cssCacheEvictionCounter = 0

    private fun evictStaleCssCache() {
        val cssPrefs = cssCachePrefs ?: return
        val now = System.currentTimeMillis()
        val editor = cssPrefs.edit()
        var evicted = false
        for (key in cssPrefs.all.keys) {
            if (!key.startsWith("css_cache_") || key.endsWith("_ts")) continue
            val ts = cssPrefs.getLong("${key}_ts", 0)
            if (now - ts > CSS_CACHE_TTL_MS) {
                editor.remove(key)
                editor.remove("${key}_ts")
                evicted = true
            }
        }
        if (evicted) editor.apply()
    }

    // Per-key rate limiter — prevents rapid re-writes to the same key while
    // allowing independent keys to be written in parallel. Uses System.nanoTime()
    // (monotonic hardware counter) instead of currentTimeMillis() (wall clock
    // that can jump on clock adjustments, breaking the rate limiter).
    private val lastWriteTime = ConcurrentHashMap<String, Long>()

    private fun rateLimitWrite(id: String): Boolean = rateLimitWrite(id, 500_000_000L)

    private fun rateLimitWrite(id: String, minIntervalNanos: Long): Boolean {
        val now = System.nanoTime()
        val last = lastWriteTime[id] ?: 0L
        if (now - last < minIntervalNanos) return false
        lastWriteTime[id] = now
        return true
    }

    private fun isKeyAllowedForWrite(id: String): Boolean {
        if (id == "vencordLocation") return false
        if (id == "clientMod") return true
        if (id.startsWith("Vencord-") || id.startsWith("vendroid_") || id.startsWith("Vencord_") || id.startsWith("css_cache_")) return true
        if (com.nin0dev.vendroid.BuildConfig.DEBUG) w("Blocked write for disallowed key: $id")
        return false
    }

    private fun isKeyAllowedForRead(id: String): Boolean {
        if (id == "vencordLocation") return false
        if (id == "clientMod") return true
        if (id.startsWith("Vencord-") || id.startsWith("vendroid_") || id.startsWith("Vencord_") || id.startsWith("css_cache_")) return true
        if (com.nin0dev.vendroid.BuildConfig.DEBUG) w("Blocked read for disallowed key: $id")
        return false
    }

    private fun isOnDiscordDomain(): Boolean {
        return Constants.isDiscordDomain(activity.get()?.currentHostForBridge ?: return false)
    }

    /**
     * Defense-in-depth domain check for sensitive bridge methods. Re-reads the
     * WebView's current URL on the UI thread in addition to the cached
     * [currentHostForBridge], closing the TOCTOU window between a navigation
     * and the cached host being refreshed. Returns false if either source
     * disagrees or the live URL is not a Discord domain.
     */
    private fun isOnDiscordDomainStrict(): Boolean {
        if (!isOnDiscordDomain()) return false
        val wv = wvRef.get() ?: return false
        // WebView.getUrl() must be queried on the UI thread. Bridge methods run
        // on a Chromium JS thread, so this does not stall the page's main thread.
        var liveHost: String? = null
        val wvActivity = activity.get()
        if (wvActivity != null) {
            val latch = java.util.concurrent.CountDownLatch(1)
            wvActivity.runOnUiThread {
                try { liveHost = wv.url?.let { Uri.parse(it).host } } catch (_: Exception) {}
                latch.countDown()
            }
            try {
                if (!latch.await(2, java.util.concurrent.TimeUnit.SECONDS)) return false
            } catch (_: InterruptedException) {
                return false
            }
        }
        return liveHost != null && Constants.isDiscordDomain(liveHost!!)
    }

    fun shutdown() {
        executor.shutdown()
    }

    @JavascriptInterface
    fun setOverlayActive(active: Boolean) {
        if (!isOnDiscordDomain()) return
        overlayActive = active
        val act = activity.get() ?: return
        act.runOnUiThread {
            @Suppress("DEPRECATION")
            if (active) {
                if (originalStatusBarColor == null) {
                    originalStatusBarColor = act.window.statusBarColor
                }
                if (originalNavBarColor == null) {
                    originalNavBarColor = act.window.navigationBarColor
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                    act.window.isStatusBarContrastEnforced = false
                    act.window.isNavigationBarContrastEnforced = false
                }
                act.window.statusBarColor = Color.BLACK
                act.window.navigationBarColor = Color.BLACK
            } else {
                act.window.statusBarColor = originalStatusBarColor ?: act.window.statusBarColor
                act.window.navigationBarColor = originalNavBarColor ?: act.window.navigationBarColor
            }
        }
    }

    @JavascriptInterface
    fun goBack() {
        if (!isOnDiscordDomain()) return
        activity.get()?.runOnUiThread {
            val wv = wvRef.get() ?: return@runOnUiThread
            if (wv.canGoBack()) wv.goBack() else
                activity.get()?.finish()
        }
    }

    @JavascriptInterface
    fun updateVencord() {
        // Strict check before any work — this overwrites vencord.js via a
        // network download, so non-Discord whitelisted pages must not invoke it.
        if (!isOnDiscordDomainStrict()) return
        if (!rateLimitWrite("updateVencord", 5 * 60 * 1_000_000_000L)) return
        // Resolve and validate the bundle location before scheduling any
        // network work, so a misconfigured vencordLocation fails fast.
        val sPrefs = settingsPrefs ?: return
        val defaultUrl = if (
            sPrefs.getString("clientMod", "vencord") == "equicord"
        ) Constants.EQUICORD_BUNDLE_URL else Constants.JS_BUNDLE_URL
        val vencordLocation = sPrefs.getString("vencordLocation", defaultUrl) ?: defaultUrl
        val vencordHost = Uri.parse(vencordLocation).host
        if (vencordHost == null || !Constants.isAllowedVencordHost(vencordHost)) {
            e("Vencord location host '$vencordHost' is not in allowed list")
            return
        }
        executor.execute {
            var conn: HttpURLConnection? = null
            var vendroidTmpFile: File? = null
            try {
                val act = activity.get() ?: return@execute
                val vendroidFile = File(act.filesDir, "vencord.js")
                vendroidTmpFile = File(act.filesDir, "vencord.js.tmp")
                conn = HttpClient.fetch(vencordLocation)
                val content = HttpClient.readAsText(conn.inputStream)
                val patched = HttpClient.applyPatches(content)
                vendroidTmpFile.writeText(patched)
                if (!vendroidTmpFile.renameTo(vendroidFile)) {
                    vendroidTmpFile.delete()
                    throw IOException("Failed to rename ${vendroidTmpFile.name} to ${vendroidFile.name}")
                }
                // Sync ETag/patched-flag with the startup path so the next
                // launch's conditional GET returns 304. VencordRuntime is left
                // untouched; the user is prompted to restart.
                val responseEtag = conn.getHeaderField("ETag")
                val editor = sPrefs.edit()
                if (responseEtag != null) {
                    editor.putString("vencordEtag", responseEtag)
                }
                editor.putInt("lastMajorUpdateThatUserHasUpdatedVencord", com.nin0dev.vendroid.BuildConfig.VERSION_CODE)
                editor.apply()
                HttpClient.vencordBundlePatched = true
                act.runOnUiThread {
                    act.showDiscordToast("Updated Vencord, restart to apply changes!", "SUCCESS")
                }
            } catch (ex: Exception) {
                activity.get()?.let { e("Failed to update Vencord", ex) }
            } finally {
                vendroidTmpFile?.delete()
                conn?.disconnect()
            }
        }
    }

    @JavascriptInterface
    fun getString(id: String?, defaultValue: String?): String {
        val safeId = id ?: return defaultValue ?: ""
        val safeDefault = defaultValue ?: ""
        return try {
            if (!isOnDiscordDomain()) return safeDefault
            if (!isKeyAllowedForRead(safeId)) return safeDefault
            // Route CSS cache reads to the dedicated cache prefs file.
            if (safeId.startsWith("css_cache_")) {
                val cssPrefs = cssCachePrefs ?: return safeDefault
                return try {
                    cssPrefs.getString(safeId, safeDefault) ?: safeDefault
                } catch (e: ClassCastException) {
                    if (com.nin0dev.vendroid.BuildConfig.DEBUG) e("getString($safeId) ClassCastException — removing corrupted key", e)
                    cssPrefs.edit().remove(safeId).apply()
                    safeDefault
                }
            }
            val sPrefs = settingsPrefs ?: return safeDefault
            try {
                sPrefs.getString(safeId, safeDefault) ?: safeDefault
            } catch (e: ClassCastException) {
                if (com.nin0dev.vendroid.BuildConfig.DEBUG) e("getString($safeId) ClassCastException — removing corrupted key", e)
                sPrefs.edit().remove(safeId).apply()
                safeDefault
            }
        } catch (t: Throwable) {
            if (com.nin0dev.vendroid.BuildConfig.DEBUG) e("getString($safeId) failed", t)
            safeDefault
        }
    }

    @JavascriptInterface
    fun getBool(id: String?, defaultValue: Boolean): Boolean {
        val safeId = id ?: return defaultValue
        return try {
            if (!isOnDiscordDomain()) return defaultValue
            if (!isKeyAllowedForRead(safeId)) return defaultValue
            val sPrefs = settingsPrefs ?: return defaultValue
            try {
                sPrefs.getBoolean(safeId, defaultValue)
            } catch (e: ClassCastException) {
                if (com.nin0dev.vendroid.BuildConfig.DEBUG) e("getBool($safeId) ClassCastException — removing corrupted key", e)
                sPrefs.edit().remove(safeId).apply()
                defaultValue
            }
        } catch (t: Throwable) {
            if (com.nin0dev.vendroid.BuildConfig.DEBUG) e("getBool($safeId) failed", t)
            defaultValue
        }
    }

    @JavascriptInterface
    fun setString(id: String?, value: String?) {
        val safeId = id ?: return
        val safeValue = value ?: return
        try {
            // Strict check — setString can persist the bundle source (clientMod)
            // and other settings, so non-Discord pages must not write.
            if (!isOnDiscordDomainStrict()) return
            if (!rateLimitWrite(safeId)) return
            if (!isKeyAllowedForWrite(safeId)) return
            // Route CSS cache keys to the dedicated cache prefs file so they
            // don't churn the main settings XML on every write.
            if (safeId.startsWith("css_cache_")) {
                val cssPrefs = cssCachePrefs ?: return
                // Bound the key count to prevent unbounded prefs growth
                // (SharedPreferences loads fully into memory). Updating an
                // existing key is always allowed; new keys are rejected once
                // the cap is reached.
                if (!cssPrefs.contains(safeId)) {
                    val cssKeyCount = cssPrefs.all.keys.count { it.startsWith("css_cache_") && !it.endsWith("_ts") }
                    if (cssKeyCount >= CSS_CACHE_MAX_KEYS) {
                        if (com.nin0dev.vendroid.BuildConfig.DEBUG) w("CSS cache key cap reached ($CSS_CACHE_MAX_KEYS), rejecting new key: $safeId")
                        return
                    }
                }
                cssPrefs.edit {
                    putLong("${safeId}_ts", System.currentTimeMillis())
                    putString(safeId, safeValue)
                }
                return
            }
            val sPrefs = settingsPrefs ?: return
            sPrefs.edit {
                if (safeId == "clientMod") {
                    // Invalidate the stale bundle so the next launch downloads
                    // the new mod cleanly, rather than injecting both the old
                    // (from preload) and new (from fetchVencord) runtimes.
                    putInt("lastMajorUpdateThatUserHasUpdatedVencord", 0)
                    remove("vencordEtag")
                    activity.get()?.filesDir?.let { File(it, "vencord.js").delete() }
                    HttpClient.vencordBundlePatched = false
                    HttpClient.setVencordRuntime(null)
                }
                putString(safeId, safeValue)
            }
        } catch (t: Throwable) {
            if (com.nin0dev.vendroid.BuildConfig.DEBUG) e("setString($safeId) failed", t)
        }
    }

    @JavascriptInterface
    fun setBool(id: String?, value: Boolean) {
        val safeId = id ?: return
        try {
            // Strict check — setBool persists behavior-influencing settings
            // (safeMode, desktopMode, etc.), so non-Discord pages must not write.
            if (!isOnDiscordDomainStrict()) return
            if (!rateLimitWrite(safeId)) return
            if (!isKeyAllowedForWrite(safeId)) return
            val sPrefs = settingsPrefs ?: return
            sPrefs.edit {
                putBoolean(safeId, value)
            }
            // Live-update the typing indicator filter so the toggle takes
            // effect without an app restart.
            if (safeId == "vendroid_blockTypingIndicator") {
                com.nin0dev.vendroid.webview.VWebviewClient.updateTypingBlock(value)
            }
            // Live-update the external-link confirmation toggle.
            if (safeId == "vendroid_confirmExternalLinks") {
                com.nin0dev.vendroid.webview.LinkHandler.updateConfirmExternalLinks(value)
            }
        } catch (t: Throwable) {
            if (com.nin0dev.vendroid.BuildConfig.DEBUG) e("setBool($safeId) failed", t)
        }
    }

    @JavascriptInterface
    fun changeAppIcon(id: String?) {
        val rawId = id ?: run {
            val a = activity.get()
            a?.runOnUiThread { Toast.makeText(a, "Icon change: null id", Toast.LENGTH_SHORT).show() }
            return
        }
        val safeId = ICON_NAMES.find { it.equals(rawId, ignoreCase = true) }
        if (safeId == null) {
            val a = activity.get()
            a?.runOnUiThread { Toast.makeText(a, "Icon change: unknown id '$rawId'", Toast.LENGTH_SHORT).show() }
            return
        }
        try {
            if (!isOnDiscordDomainStrict()) {
                val a = activity.get()
                a?.runOnUiThread { Toast.makeText(a, "Icon change: not on Discord domain", Toast.LENGTH_SHORT).show() }
                return
            }
            synchronized(iconLock) {
                if (safeId == currentIcon) {
                    val a = activity.get()
                    a?.runOnUiThread { Toast.makeText(a, "Icon '$safeId' is already active", Toast.LENGTH_SHORT).show() }
                    return
                }
                val act = activity.get() ?: return
                val oldIcon = currentIcon
                currentIcon = safeId
                val pm = act.packageManager
                val pkg = act.applicationContext
                pm.setComponentEnabledSetting(
                    ComponentName(pkg, "com.nin0dev.vendroid.${safeId}MainActivity"),
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
                )
                pm.setComponentEnabledSetting(
                    ComponentName(pkg, "com.nin0dev.vendroid.${oldIcon}MainActivity"),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
                act.runOnUiThread {
                    Toast.makeText(act, "Icon changed to $safeId. Restart launcher if it doesn't update.", Toast.LENGTH_LONG).show()
                }
            }
        } catch (t: Throwable) {
            e("changeAppIcon failed for id=$safeId", t)
            val a = activity.get()
            a?.runOnUiThread { Toast.makeText(a, "Icon change failed: ${t.message}", Toast.LENGTH_LONG).show() }
        }
    }

    @JavascriptInterface
    fun openQuickCss(quickCss: String?) {
        try {
            // Strict check — this opens a WebView with a JS interface, so a
            // non-Discord whitelisted page must not invoke it.
            if (!isOnDiscordDomainStrict()) return
            val act = activity.get() ?: return
            val safeQuickCss = quickCss ?: ""
            act.runOnUiThread {
                try {
                    val wv = WebView(act)
                    wv.settings.javaScriptEnabled = true
                    wv.settings.domStorageEnabled = true
                    wv.settings.allowFileAccess = false
                    wv.settings.allowContentAccess = false
                    wv.settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    wv.setBackgroundColor(android.graphics.Color.parseColor("#0f0f10"))

                    val dialog = android.app.Dialog(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
                    dialog.setContentView(wv)
                    dialog.setCancelable(true)
                    dialog.setOnDismissListener {
                        wv.destroy()
                    }

                    val bridge = QuickCssBridge(act, wv, dialog)
                    wv.addJavascriptInterface(bridge, "VencordMobileNative")

                    wv.webViewClient = object : android.webkit.WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            bridge.originCommitted = true
                            if (safeQuickCss.isNotEmpty()) {
                                view?.evaluateJavascript(
                                    "window.qcssSet?.(${gson.toJson(safeQuickCss)})", null
                                )
                            } else {
                                val mainWv = wvRef.get() ?: return
                                mainWv.evaluateJavascript("VencordNative.quickCss.get()") { result ->
                                    // result is a JSON-encoded string; pass it to JS via
                                    // JSON.parse. Manual unescaping breaks on literal backslashes.
                                    if (result != null && result != "null") {
                                        view?.evaluateJavascript(
                                            "window.qcssSet?.(JSON.parse($result))", null
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (act.isFinishing || act.isDestroyed) {
                        wv.destroy()
                        return@runOnUiThread
                    }
                    dialog.show()
                    wv.loadUrl("file:///android_asset/quickcss_editor.html")
                } catch (e: Throwable) {
                    if (com.nin0dev.vendroid.BuildConfig.DEBUG) e("openQuickCss inner failed", e)
                }
            }
        } catch (e: Throwable) {
            if (com.nin0dev.vendroid.BuildConfig.DEBUG) e("openQuickCss outer failed", e)
        }
    }

    private class QuickCssBridge(
        private val activity: MainActivity,
        private val editorWebView: WebView,
        private val dialog: android.app.Dialog
    ) {
        // Set from onPageFinished (UI thread). WebView.getUrl() must be called
        // on the UI thread, but @JavascriptInterface methods run on a Chromium
        // internal thread, so checking getUrl() directly is unreliable.
        @Volatile var originCommitted = false

        private fun isExpectedOrigin(): Boolean = originCommitted

        @android.webkit.JavascriptInterface
        fun quickCssSet(css: String?) {
            if (!isExpectedOrigin()) return
            val safe = css ?: ""
            activity.runOnUiThread {
                val mainWv = activity.findViewById<WebView>(R.id.webview)
                mainWv?.evaluateJavascript(
                    "VencordNative.quickCss.set(${gson.toJson(safe)})", null
                )
                Toast.makeText(activity, "Saved QuickCSS", Toast.LENGTH_SHORT).show()
                if (dialog.isShowing) dialog.dismiss()
            }
        }

        @android.webkit.JavascriptInterface
        fun quickCssClose() {
            if (!isExpectedOrigin()) return
            activity.runOnUiThread {
                if (dialog.isShowing) dialog.dismiss()
            }
        }
    }

    @JavascriptInterface
    fun getCssCache(cacheKey: String?): String? {
        val safeKey = cacheKey ?: return null
        try {
            if (!isOnDiscordDomain()) return null
            if (!safeKey.startsWith("css_cache_")) return null
            if (++cssCacheEvictionCounter % 50 == 0) {
                executor.execute { evictStaleCssCache() }
            }
            val cssPrefs = cssCachePrefs ?: return null
            return try {
                cssPrefs.getString(safeKey, null)
            } catch (e: ClassCastException) {
                if (com.nin0dev.vendroid.BuildConfig.DEBUG) e("getCssCache($safeKey) ClassCastException", e)
                cssPrefs.edit().remove(safeKey).apply()
                null
            } catch (e: Throwable) {
                if (com.nin0dev.vendroid.BuildConfig.DEBUG) e("getCssCache($safeKey) failed", e)
                null
            }
        } catch (e: Throwable) {
            if (com.nin0dev.vendroid.BuildConfig.DEBUG) e("getCssCache($safeKey) outer failed", e)
            return null
        }
    }

    @JavascriptInterface
    fun dismissLoadingScreen() {
        if (!isOnDiscordDomain()) return
        val act = activity.get() ?: return
        act.runOnUiThread {
            act.dismissLoadingScreen()
        }
    }

    @JavascriptInterface
    fun isDebugBuild(): Boolean {
        return com.nin0dev.vendroid.BuildConfig.DEBUG
    }

    @JavascriptInterface
    fun openLogs() {
        try {
            // Gate on Discord domain. A failed Discord load still leaves
            // currentHostForBridge on a Discord host, so troubleshooting
            // remains possible; non-Discord whitelisted pages cannot open
            // the viewer or read app diagnostics.
            if (!isOnDiscordDomain()) return
            val act = activity.get() ?: return
            if (logsDialogActive) return
            act.runOnUiThread {
                try {
                    if (act.isFinishing || act.isDestroyed) return@runOnUiThread

                    val wv = WebView(act)
                    wv.settings.javaScriptEnabled = true
                    wv.settings.domStorageEnabled = true
                    wv.settings.allowFileAccess = false
                    wv.settings.allowContentAccess = false
                    wv.settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    wv.setBackgroundColor(android.graphics.Color.parseColor("#0f0f10"))

                    val dialog = android.app.Dialog(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
                    dialog.setContentView(wv)
                    dialog.setCancelable(true)
                    dialog.setOnDismissListener {
                        logsDialogActive = false
                        wv.destroy()
                    }

                    val bridge = LogViewerBridge(act, wv, dialog)
                    wv.addJavascriptInterface(bridge, "VencordMobileNative")

                    wv.webViewClient = object : android.webkit.WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            bridge.originCommitted = true
                            val logs = VDELog.getRecentLogs(500)
                            view?.evaluateJavascript(
                                "window.vdeSetLogs?.(${gson.toJson(logs)})", null
                            )
                        }
                    }

                    logsDialogActive = true
                    dialog.show()
                    wv.loadUrl("file:///android_asset/log_viewer.html")
                } catch (e: Throwable) {
                    logsDialogActive = false
                    if (com.nin0dev.vendroid.BuildConfig.DEBUG) {
                        android.util.Log.e("Vendroid", "openLogs inner failed", e)
                    }
                }
            }
        } catch (e: Throwable) {
            if (com.nin0dev.vendroid.BuildConfig.DEBUG) {
                android.util.Log.e("Vendroid", "openLogs outer failed", e)
            }
        }
    }

    private class LogViewerBridge(
        private val activity: MainActivity,
        private val viewerWebView: WebView,
        private val dialog: android.app.Dialog
    ) {
        @Volatile var originCommitted = false

        private fun isExpectedOrigin(): Boolean = originCommitted

        @android.webkit.JavascriptInterface
        fun close() {
            if (!isExpectedOrigin()) return
            if (dialog.isShowing) dialog.dismiss()
        }

        @android.webkit.JavascriptInterface
        fun clearLogs() {
            if (!isExpectedOrigin()) return
            VDELog.clearLogs()
        }

        @android.webkit.JavascriptInterface
        fun refreshLogs(): String {
            if (!isExpectedOrigin()) return ""
            return VDELog.getRecentLogs(500)
        }

        @android.webkit.JavascriptInterface
        fun shareLogs() {
            if (!isExpectedOrigin()) return
            val text = VDELog.getLogFileContents()
            activity.runOnUiThread {
                try {
                    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_TEXT, text)
                        putExtra(android.content.Intent.EXTRA_SUBJECT, "VendroidEnhanced Logs")
                    }
                    activity.startActivity(android.content.Intent.createChooser(intent, "Share logs"))
                } catch (_: android.content.ActivityNotFoundException) {
                    android.widget.Toast.makeText(activity, "No app available to share logs", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    @JavascriptInterface
    fun openFirewallEditor() {
        try {
            // Strict check — this mutates the firewall config, so a non-Discord
            // page must not invoke it even if currentHostForBridge is stale.
            if (!isOnDiscordDomainStrict()) return
            val act = activity.get() ?: return
            if (firewallDialogActive) return
            act.runOnUiThread {
                try {
                    if (act.isFinishing || act.isDestroyed) return@runOnUiThread

                    val wv = WebView(act)
                    wv.settings.javaScriptEnabled = true
                    wv.settings.domStorageEnabled = true
                    wv.settings.allowFileAccess = false
                    wv.settings.allowContentAccess = false
                    wv.settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    wv.setBackgroundColor(android.graphics.Color.parseColor("#0f0f10"))

                    val dialog = android.app.Dialog(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
                    dialog.setContentView(wv)
                    dialog.setCancelable(true)
                    dialog.setOnDismissListener {
                        firewallDialogActive = false
                        wv.destroy()
                    }

                    val bridge = FirewallEditorBridge(act, wv, dialog)
                    wv.addJavascriptInterface(bridge, "VencordMobileNative")

                    // Defer the initial config push until onPageFinished, when
                    // the page URL has committed. The bridge's isExpectedOrigin()
                    // check reads a @Volatile flag set here (on the UI thread),
                    // not WebView.getUrl() — getUrl() must be called on the UI
                    // thread but bridge methods run on a Chromium internal thread.
                    wv.webViewClient = object : android.webkit.WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            bridge.originCommitted = true
                            val json = gson.toJson(FirewallConfig.toJson())
                            view?.evaluateJavascript(
                                "window.vdeFirewallInit?.($json)", null
                            )
                        }
                    }

                    firewallDialogActive = true
                    dialog.show()
                    wv.loadUrl("file:///android_asset/firewall_editor.html")
                } catch (e: Throwable) {
                    firewallDialogActive = false
                    if (com.nin0dev.vendroid.BuildConfig.DEBUG) {
                        android.util.Log.e("Vendroid", "openFirewallEditor inner failed", e)
                    }
                }
            }
        } catch (e: Throwable) {
            if (com.nin0dev.vendroid.BuildConfig.DEBUG) {
                android.util.Log.e("Vendroid", "openFirewallEditor outer failed", e)
            }
        }
    }

    private class FirewallEditorBridge(
        private val activity: MainActivity,
        private val editorWebView: WebView,
        private val dialog: android.app.Dialog
    ) {
        @Volatile var originCommitted = false
        @Volatile private var lastError: String? = null

        private fun isExpectedOrigin(): Boolean = originCommitted

        @android.webkit.JavascriptInterface
        fun close() {
            try {
                if (!isExpectedOrigin()) return
                if (dialog.isShowing) dialog.dismiss()
            } catch (t: Throwable) {
                lastError = "close: ${t.message}"
            }
        }

        @android.webkit.JavascriptInterface
        fun getFirewallConfig(): String {
            try {
                if (!isExpectedOrigin()) return "{\"error\":\"origin\"}"
                return FirewallConfig.toJson()
            } catch (t: Throwable) {
                lastError = "getFirewallConfig: ${t.javaClass.name}: ${t.message}"
                return "{\"categories\":[],\"customDomains\":[],\"error\":\"$lastError\"}"
            }
        }

        @android.webkit.JavascriptInterface
        fun saveFirewallConfig(json: String): Boolean {
            try {
                if (!isExpectedOrigin()) return false
                val ok = FirewallConfig.fromJsonAndSave(json)
                if (ok) {
                    activity.runOnUiThread {
                        android.widget.Toast.makeText(
                            activity, "Firewall saved", android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                return ok
            } catch (t: Throwable) {
                lastError = "saveFirewallConfig: ${t.message}"
                return false
            }
        }

        @android.webkit.JavascriptInterface
        fun resetFirewallConfig() {
            try {
                if (!isExpectedOrigin()) return
                FirewallConfig.resetToDefaults()
                activity.runOnUiThread {
                    android.widget.Toast.makeText(
                        activity, "Firewall reset to defaults", android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (t: Throwable) {
                lastError = "resetFirewallConfig: ${t.message}"
            }
        }
    }
}
