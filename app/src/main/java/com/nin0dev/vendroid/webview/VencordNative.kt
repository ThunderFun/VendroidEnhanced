package com.nin0dev.vendroid.webview

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.view.View.GONE
import android.view.View.VISIBLE
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.LinearLayout
import androidx.core.content.edit
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.textfield.TextInputEditText
import com.nin0dev.vendroid.MainActivity
import com.nin0dev.vendroid.R
import com.nin0dev.vendroid.utils.Constants
import com.nin0dev.vendroid.utils.Logger.e
import java.io.File
import java.io.FileOutputStream
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.util.concurrent.Executors

class VencordNative(private val activity: WeakReference<MainActivity>, wv: WebView) {
    private val wvRef: WeakReference<WebView> = WeakReference(wv)

    companion object {
        private val ICON_NAMES = arrayOf("Main", "Jolly", "Discord", "Retro", "TS12")
        @Volatile
        private var currentIcon: String = "Main"
    }

    @Volatile
    var overlayActive = false
        private set

    private var originalStatusBarColor: Int? = null

    private val settingsPrefs: SharedPreferences? by lazy { activity.get()?.getSharedPreferences("settings", Context.MODE_PRIVATE) }

    private val executor = Executors.newSingleThreadExecutor()

    @Volatile
    private var quickCssLayout: LinearLayout? = null
    @Volatile
    private var loadingScreenLayout: LinearLayout? = null
    @Volatile
    private var webview: WebView? = null
    @Volatile
    private var cssEditText: TextInputEditText? = null

    fun shutdown() {
        executor.shutdown()
    }

    @JavascriptInterface
    fun setOverlayActive(active: Boolean) {
        overlayActive = active
        val act = activity.get() ?: return
        act.runOnUiThread {
            val controller = WindowInsetsControllerCompat(act.window, act.window.decorView)
            if (active) {
                if (originalStatusBarColor == null) {
                    originalStatusBarColor = act.window.statusBarColor
                }
                act.window.statusBarColor = Color.BLACK
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.navigationBars())
            } else {
                act.window.statusBarColor = originalStatusBarColor ?: act.window.statusBarColor
                controller.show(WindowInsetsCompat.Type.navigationBars())
            }
        }
    }

    @JavascriptInterface
    fun goBack() {
        activity.get()?.runOnUiThread {
            val wv = wvRef.get() ?: return@runOnUiThread
            if (wv.canGoBack()) wv.goBack() else
                activity.get()?.getActionBar()
        }
    }

    @JavascriptInterface
    fun updateVencord() {
        executor.execute {
            var conn: HttpURLConnection? = null
            try {
                val act = activity.get() ?: return@execute
                val sPrefs = settingsPrefs ?: return@execute
                val vendroidFile = File(act.filesDir, "vencord.js")
                val vendroidTmpFile = File(act.filesDir, "vencord.js.tmp")
                val defaultUrl = if (
                    sPrefs.getString("clientMod", "vencord") == "equicord"
                ) Constants.EQUICORD_BUNDLE_URL else Constants.JS_BUNDLE_URL
                val vencordLocation = sPrefs.getString("vencordLocation", defaultUrl) ?: defaultUrl
                conn = HttpClient.fetch(vencordLocation)
                conn.inputStream.use { input ->
                    FileOutputStream(vendroidTmpFile).use { output ->
                        input.copyTo(output)
                    }
                }
                vendroidTmpFile.renameTo(vendroidFile)
                act.runOnUiThread {
                    act.showDiscordToast("Updated Vencord, restart to apply changes!", "SUCCESS")
                }
            } catch (e: Exception) {
                activity.get()?.let { e("Failed to update Vencord", e) }
            } finally {
                conn?.disconnect()
            }
        }
    }

    @JavascriptInterface
    fun updateVendroid() {
        activity.get()?.checkUpdates(ignoreSetting = true)
    }

    @JavascriptInterface
    fun getString(id: String, defaultValue: String): String {
        val sPrefs = settingsPrefs ?: return defaultValue
        return try {
            sPrefs.getString(id, defaultValue) ?: defaultValue
        } catch (e: Exception) {
            defaultValue
        }
    }

    @JavascriptInterface
    fun getBool(id: String, defaultValue: Boolean): Boolean {
        val sPrefs = settingsPrefs ?: return false
        return try {
            sPrefs.getBoolean(id, defaultValue)
        } catch (e: Exception) {
            defaultValue
        }
    }

    @JavascriptInterface
    fun setString(id: String, value: String) {
        val sPrefs = settingsPrefs ?: return
        sPrefs.edit {
            if (id == "clientMod") putInt("lastMajorUpdateThatUserHasUpdatedVencord", 0)
            putString(id, value)
        }
    }

    @JavascriptInterface
    fun setBool(id: String, value: Boolean) {
        val sPrefs = settingsPrefs ?: return
        sPrefs.edit {
            putBoolean(id, value)
        }
    }

    @JavascriptInterface
    fun changeAppIcon(id: String) {
        val act = activity.get() ?: return
        if (id == currentIcon) return
        if (id !in ICON_NAMES) return
        val pm = act.packageManager
        val pkg = act.applicationContext
        pm.setComponentEnabledSetting(
            ComponentName(pkg, "com.nin0dev.vendroid.${id}MainActivity"),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP
        )
        pm.setComponentEnabledSetting(
            ComponentName(pkg, "com.nin0dev.vendroid.${currentIcon}MainActivity"),
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )
        currentIcon = id
    }

    @JavascriptInterface
    fun openQuickCss(quickCss: String) {
        val act = activity.get() ?: return
        act.runOnUiThread {
            val quickCssView = quickCssLayout ?: act.findViewById<LinearLayout>(R.id.quickcss).also { quickCssLayout = it }
            val loadingView = loadingScreenLayout ?: act.findViewById<LinearLayout>(R.id.loading_screen).also { loadingScreenLayout = it }
            val wvView = webview ?: act.findViewById<WebView>(R.id.webview).also { webview = it }
            val cssEdit = cssEditText ?: act.findViewById<TextInputEditText>(R.id.css).also { cssEditText = it }
            quickCssView.visibility = VISIBLE
            loadingView.visibility = GONE
            wvView.visibility = GONE
            cssEdit.setText(quickCss)
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
