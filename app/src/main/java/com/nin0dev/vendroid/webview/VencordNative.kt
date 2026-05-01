package com.nin0dev.vendroid.webview

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.view.View.GONE
import android.view.View.VISIBLE
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.LinearLayout
import androidx.core.content.edit
import com.google.android.material.textfield.TextInputEditText
import com.nin0dev.vendroid.MainActivity
import com.nin0dev.vendroid.R
import com.nin0dev.vendroid.utils.Constants
import com.nin0dev.vendroid.utils.Logger.e
import com.nin0dev.vendroid.utils.Logger.w
import java.io.File
import java.io.FileOutputStream
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

    private val iconLock = Any()

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
        if (id.startsWith("Vencord-") || id.startsWith("vendroid_") || id.startsWith("Vencord_") || id.startsWith("css_cache_")) return true
        if (com.nin0dev.vendroid.BuildConfig.DEBUG) w("Blocked write for disallowed key: $id")
        return false
    }

    private fun isKeyAllowedForRead(id: String): Boolean {
        if (id == "vencordLocation") return false
        if (id.startsWith("Vencord-") || id.startsWith("vendroid_") || id.startsWith("Vencord_") || id.startsWith("css_cache_")) return true
        if (com.nin0dev.vendroid.BuildConfig.DEBUG) w("Blocked read for disallowed key: $id")
        return false
    }

    private fun isOnDiscordDomain(): Boolean {
        val wv = wvRef.get() ?: return false
        val url = wv.url ?: return false
        val host = Uri.parse(url).host ?: return false
        return Constants.isDiscordDomain(host)
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
                }
                act.runOnUiThread {
                    act.showDiscordToast("Updated Vencord, restart to apply changes!", "SUCCESS")
                }
            } catch (e: Exception) {
                activity.get()?.let { e("Failed to update Vencord", e) }
            } finally {
                vendroidTmpFile?.delete()
                conn?.disconnect()
            }
        }
    }

    @JavascriptInterface
    fun getString(id: String, defaultValue: String): String {
        if (!isOnDiscordDomain()) return defaultValue
        if (!isKeyAllowedForRead(id)) return defaultValue
        val sPrefs = settingsPrefs ?: return defaultValue
        return try {
            sPrefs.getString(id, defaultValue) ?: defaultValue
        } catch (e: Exception) {
            defaultValue
        }
    }

    @JavascriptInterface
    fun getBool(id: String, defaultValue: Boolean): Boolean {
        if (!isOnDiscordDomain()) return defaultValue
        if (!isKeyAllowedForRead(id)) return defaultValue
        val sPrefs = settingsPrefs ?: return defaultValue
        return try {
            sPrefs.getBoolean(id, defaultValue)
        } catch (e: Exception) {
            defaultValue
        }
    }

    @JavascriptInterface
    fun setString(id: String, value: String) {
        if (!isOnDiscordDomain()) return
        if (!rateLimitWrite(id)) return
        if (!isKeyAllowedForWrite(id)) return
        val sPrefs = settingsPrefs ?: return
        sPrefs.edit {
            if (id.startsWith("css_cache_")) putLong("${id}_ts", System.currentTimeMillis())
            if (id == "clientMod") putInt("lastMajorUpdateThatUserHasUpdatedVencord", 0)
            putString(id, value)
        }
    }

    @JavascriptInterface
    fun setBool(id: String, value: Boolean) {
        if (!isOnDiscordDomain()) return
        if (!rateLimitWrite(id)) return
        if (!isKeyAllowedForWrite(id)) return
        val sPrefs = settingsPrefs ?: return
        sPrefs.edit {
            putBoolean(id, value)
        }
    }

    @JavascriptInterface
    fun changeAppIcon(id: String) {
        if (!isOnDiscordDomain()) return
        synchronized(iconLock) {
            if (id == currentIcon) return
            if (id !in ICON_NAMES) return
            val act = activity.get() ?: return
            val oldIcon = currentIcon
            currentIcon = id
            val pm = act.packageManager
            val pkg = act.applicationContext
            pm.setComponentEnabledSetting(
                ComponentName(pkg, "com.nin0dev.vendroid.${id}MainActivity"),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            pm.setComponentEnabledSetting(
                ComponentName(pkg, "com.nin0dev.vendroid.${oldIcon}MainActivity"),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        }
    }

    @JavascriptInterface
    fun openQuickCss(quickCss: String) {
        if (!isOnDiscordDomain()) return
        val act = activity.get() ?: return
        if (quickCss.isNotEmpty()) {
            act.runOnUiThread {
                AlertDialog.Builder(act)
                    .setTitle("External CSS")
                    .setMessage("A plugin wants to open the QuickCSS editor with custom content. Apply?")
                    .setPositiveButton("Apply") { _, _ ->
                        val quickCssView = act.findViewById<LinearLayout>(R.id.quickcss)
                        val loadingView = act.findViewById<LinearLayout>(R.id.loading_screen)
                        val wvView = act.findViewById<WebView>(R.id.webview)
                        val cssEdit = act.findViewById<TextInputEditText>(R.id.css)
                        quickCssView.visibility = VISIBLE
                        loadingView.visibility = GONE
                        wvView.visibility = GONE
                        cssEdit.setText(quickCss)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        } else {
            act.runOnUiThread {
                val quickCssView = act.findViewById<LinearLayout>(R.id.quickcss)
                val loadingView = act.findViewById<LinearLayout>(R.id.loading_screen)
                val wvView = act.findViewById<WebView>(R.id.webview)
                quickCssView.visibility = VISIBLE
                loadingView.visibility = GONE
                wvView.visibility = GONE
            }
        }
    }

    @JavascriptInterface
    fun getCssCache(cacheKey: String): String? {
        if (!isOnDiscordDomain()) return null
        if (!cacheKey.startsWith("css_cache_")) return null
        if (++cssCacheEvictionCounter % 50 == 0) {
            executor.execute { evictStaleCssCache() }
        }
        val sPrefs = settingsPrefs ?: return null
        return try {
            sPrefs.getString(cacheKey, null)
        } catch (e: Exception) {
            null
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
