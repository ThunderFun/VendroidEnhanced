package com.nin0dev.vendroid.webview

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
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

    fun shutdown() {
        executor.shutdown()
    }

    @JavascriptInterface
    fun setOverlayActive(active: Boolean) {
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
        if (!rateLimitWrite(id)) return
        if (!isKeyAllowedForWrite(id)) return
        val sPrefs = settingsPrefs ?: return
        sPrefs.edit {
            if (id == "clientMod") putInt("lastMajorUpdateThatUserHasUpdatedVencord", 0)
            putString(id, value)
        }
    }

    @JavascriptInterface
    fun setBool(id: String, value: Boolean) {
        if (!rateLimitWrite(id)) return
        if (!isKeyAllowedForWrite(id)) return
        val sPrefs = settingsPrefs ?: return
        sPrefs.edit {
            putBoolean(id, value)
        }
    }

    @JavascriptInterface
    fun changeAppIcon(id: String) {
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
        if (!cacheKey.startsWith("css_cache_")) return null
        val sPrefs = settingsPrefs ?: return null
        return try {
            sPrefs.getString(cacheKey, null)
        } catch (e: Exception) {
            null
        }
    }

    @JavascriptInterface
    fun dismissLoadingScreen() {
        val act = activity.get() ?: return
        act.runOnUiThread {
            act.dismissLoadingScreen()
        }
    }
}
