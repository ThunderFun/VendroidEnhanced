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
import com.nin0dev.vendroid.utils.Logger.e
import com.nin0dev.vendroid.utils.Logger.w
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

    private var originalStatusBarColor: Int? = null
    private var originalNavBarColor: Int? = null

    // Eagerly initialize SharedPreferences in the constructor (which runs on
    // the main thread during WebView setup) instead of lazily on the JS bridge
    // thread. The first getSharedPreferences() call reads+parses the XML file
    // from disk, which can take 50-100ms on eMMC. Doing this on the bridge
    // thread would stall ALL @JavascriptInterface methods.
    private val settingsPrefs: SharedPreferences? = activity.get()
        ?.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val executor = Executors.newSingleThreadExecutor()

    private var cssCacheEvictionCounter = 0

    private fun evictStaleCssCache() {
        val sPrefs = settingsPrefs ?: return
        val now = System.currentTimeMillis()
        val editor = sPrefs.edit()
        var evicted = false
        for (key in sPrefs.all.keys) {
            if (!key.startsWith("css_cache_") || key.endsWith("_ts")) continue
            val ts = sPrefs.getLong("${key}_ts", 0)
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

    private fun rateLimitWrite(id: String): Boolean {
        val now = System.nanoTime()
        val last = lastWriteTime[id] ?: 0L
        if (now - last < 500_000_000L) return false // 500ms in nanos
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
        if (!rateLimitWrite("updateVencord")) return
        executor.execute {
            var conn: HttpURLConnection? = null
            var vendroidTmpFile: File? = null
            try {
                val act = activity.get() ?: return@execute
                val sPrefs = settingsPrefs ?: return@execute
                val vendroidFile = File(act.filesDir, "vencord.js")
                vendroidTmpFile = File(act.filesDir, "vencord.js.tmp")
                val defaultUrl = if (
                    sPrefs.getString("clientMod", "vencord") == "equicord"
                ) Constants.EQUICORD_BUNDLE_URL else Constants.JS_BUNDLE_URL
                val vencordLocation = sPrefs.getString("vencordLocation", defaultUrl) ?: defaultUrl
                val vencordHost = Uri.parse(vencordLocation).host
                if (vencordHost != null && !Constants.isAllowedVencordHost(vencordHost)) {
                    activity.get()?.let { e("Vencord location host '$vencordHost' is not in allowed list") }
                    return@execute
                }
                conn = HttpClient.fetch(vencordLocation)
                val content = HttpClient.readAsText(conn.inputStream)
                val patched = HttpClient.applyPatches(content)
                vendroidTmpFile.writeText(patched)
                if (!vendroidTmpFile.renameTo(vendroidFile)) {
                    vendroidTmpFile.delete()
                    throw IOException("Failed to rename ${vendroidTmpFile.name} to ${vendroidFile.name}")
                }
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
            val sPrefs = settingsPrefs ?: return safeDefault
            try {
                sPrefs.getString(safeId, safeDefault) ?: safeDefault
            } catch (_: ClassCastException) {
                sPrefs.edit().remove(safeId).apply()
                safeDefault
            }
        } catch (_: Throwable) {
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
            } catch (_: ClassCastException) {
                sPrefs.edit().remove(safeId).apply()
                defaultValue
            }
        } catch (_: Throwable) {
            defaultValue
        }
    }

    @JavascriptInterface
    fun setString(id: String?, value: String?) {
        val safeId = id ?: return
        val safeValue = value ?: return
        try {
            if (!isOnDiscordDomain()) return
            if (!rateLimitWrite(safeId)) return
            if (!isKeyAllowedForWrite(safeId)) return
            val sPrefs = settingsPrefs ?: return
            sPrefs.edit {
                if (safeId.startsWith("css_cache_")) putLong("${safeId}_ts", System.currentTimeMillis())
                if (safeId == "clientMod") putInt("lastMajorUpdateThatUserHasUpdatedVencord", 0)
                putString(safeId, safeValue)
            }
        } catch (_: Throwable) {}
    }

    @JavascriptInterface
    fun setBool(id: String?, value: Boolean) {
        val safeId = id ?: return
        try {
            if (!isOnDiscordDomain()) return
            if (!rateLimitWrite(safeId)) return
            if (!isKeyAllowedForWrite(safeId)) return
            val sPrefs = settingsPrefs ?: return
            sPrefs.edit {
                putBoolean(safeId, value)
            }
        } catch (_: Throwable) {}
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
            if (!isOnDiscordDomain()) {
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
            if (!isOnDiscordDomain()) return
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
                            if (safeQuickCss.isNotEmpty()) {
                                view?.evaluateJavascript(
                                    "window.qcssSet?.(${gson.toJson(safeQuickCss)})", null
                                )
                            } else {
                                val mainWv = wvRef.get() ?: return
                                mainWv.evaluateJavascript("VencordNative.quickCss.get()") { result ->
                                    val raw = result.trim().removePrefix("\"").removeSuffix("\"")
                                    view?.evaluateJavascript(
                                        "window.qcssSet?.(${gson.toJson(raw.replace("\\n", "\n"))})", null
                                    )
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
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }

    private class QuickCssBridge(
        private val activity: MainActivity,
        private val editorWebView: WebView,
        private val dialog: android.app.Dialog
    ) {
        @android.webkit.JavascriptInterface
        fun quickCssSet(css: String?) {
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
            val sPrefs = settingsPrefs ?: return null
            return try {
                sPrefs.getString(safeKey, null)
            } catch (_: ClassCastException) {
                sPrefs.edit().remove(safeKey).apply()
                null
            } catch (_: Throwable) {
                null
            }
        } catch (_: Throwable) {
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
}
