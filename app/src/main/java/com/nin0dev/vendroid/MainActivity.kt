package com.nin0dev.vendroid

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import java.io.File
import android.os.Handler
import android.os.Looper
import android.os.StrictMode
import android.os.StrictMode.ThreadPolicy
import android.view.KeyEvent
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


class MainActivity : Activity() {
    private var wvInitialized = false
    private var wv: WebView? = null
    private lateinit var chromeClient: VChromeClient
    private lateinit var vencordNative: VencordNative

    @JvmField
    var filePathCallback: ValueCallback<Array<Uri>>? = null

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
                "VencordNative.quickCss.set(\"${
                    cssEditText.text
                        .toString()
                        .replace("\\", "\\\\")
                        .replace("\"", "\\\"")
                        .replace("\n", "\\n")
                        .replace("\r", "\\r")
                }\")", null
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
        val sPrefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (!sPrefs.getBoolean("checkVDEUpdates", true) && !sPrefs.getBoolean("checkAnnouncements", true) && !ignoreSetting) return

        val today = LocalDate.now()
        val i = "${today.dayOfYear}${today.year}"
        val url = "https://vendroid.nin0.dev/api/updates?version=${BuildConfig.VERSION_CODE}${if (sPrefs.getString("lastDailyCheck", "") == i) "" else "&daily=true"}"
        sPrefs.edit { putString("lastDailyCheck", i) }

        fetchExecutor.execute {
            try {
                val conn = HttpClient.fetch(url)
                val response = HttpClient.readAsText(conn.inputStream, conn.contentLength.coerceAtLeast(8192))
                conn.disconnect()
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
        val loadingScreen = findViewById<LinearLayout>(R.id.loading_screen)
        loadingScreen.animate()?.alpha(0f)?.setDuration(500)?.withEndAction {
            loadingScreen.visibility = GONE
            loadingScreen.alpha = 1f
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

        val sPrefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val editor = sPrefs.edit()

        WebView.setWebContentsDebuggingEnabled(
            BuildConfig.DEBUG || sPrefs.getBoolean(
                "allowRemoteDebugging",
                false
            )
        )
        setContentView(R.layout.activity_main)

        quickCssLayout = findViewById(R.id.quickcss)
        loadingScreenLayout = findViewById(R.id.loading_screen)

        wv = findViewById(R.id.webview)!!

        chromeClient = VChromeClient(this)
        val webViewClient = VWebviewClient(this)
        wv!!.setWebViewClient(webViewClient)
        wv!!.setWebChromeClient(chromeClient)
        setupQuickCss()
        explodeAndroid()
        if (sPrefs.getBoolean("desktopMode", false)) {
            wv!!.settings.userAgentString =
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
        }
        val s = wv?.getSettings()!!
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.allowFileAccess = true

        s.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
        s.databaseEnabled = true
        s.offscreenPreRaster = true
        s.setRenderPriority(android.webkit.WebSettings.RenderPriority.HIGH)
        s.mediaPlaybackRequiresUserGesture = false
        s.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        s.setBuiltInZoomControls(false)

        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(wv!!, true)

        if (!sPrefs.getBoolean("safeMode", false)) {
            vencordNative = VencordNative(WeakReference(this), wv!!)
            wv?.addJavascriptInterface(vencordNative, "VencordMobileNative")
            if (HttpClient.VencordMobileRuntime == null) {
                resources.openRawResource(R.raw.vencord_mobile).use { `is` ->
                    HttpClient.VencordMobileRuntime = HttpClient.readAsText(`is`)
                }
            }
            // Synchronously pre-load cached vencord.js so it's ready when
            // onPageStarted fires. File read from internal storage is <50ms.
            // The background fetchVencord() will still check version/debug
            // and re-download if needed.
            if (HttpClient.VencordRuntime == null) {
                val vendroidFile = File(filesDir, "vencord.js")
                if (vendroidFile.exists()) {
                    try {
                        HttpClient.VencordRuntime = HttpClient.applyPatches(vendroidFile.readText())
                    } catch (_: Exception) {}
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
        if (intent.action == Intent.ACTION_VIEW) {
            val data = intent.data
            if (data != null) handleUrl(intent.data)
        } else {
            // All branches currently resolve to the same URL; map kept for future branch support
            wv!!.loadUrl("https://discord.com/app")
        }

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

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (wv != null) {
            if (chromeClient.isFullscreen) {
                chromeClient.hideCustomView()
                wv!!.evaluateJavascript("VencordMobile.onBackPress()", null)
                return
            }
            if (::vencordNative.isInitialized && vencordNative.overlayActive) {
                wv!!.evaluateJavascript("VencordMobile.onBackPress()", null)
                return
            }
            wv!!.evaluateJavascript("VencordMobile.onBackPress()") { r: String -> if ("false" == r) @Suppress("DEPRECATION") super.onBackPressed() }
            return
        }
        super.onBackPressed()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            onBackPressed()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, intent: Intent?) {
        super.onActivityResult(requestCode, resultCode, intent)

        if (requestCode == FILECHOOSER_RESULTCODE) {
            val callback = filePathCallback
            filePathCallback = null

            if (callback == null) return

            if (intent == null) {
                callback.onReceiveValue(null)
                return
            }
            val result = WebChromeClient.FileChooserParams.parseResult(resultCode, intent)
            callback.onReceiveValue(result)
        }
    }

    private fun handleUrl(url: Uri?) {
        if (url != null) {
            if (url.authority != "discord.com" && url.authority != "ptb.discord.com" && url.authority != "canary.discord.com") return
            if (!wvInitialized) {
                wv!!.loadUrl(url.toString())
            } else {
                wv!!.evaluateJavascript(
                    "Vencord.Webpack.Common.NavigationRouter.transitionTo(\"${url.path}\")",
                    null
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val data = intent.data
        data?.let { handleUrl(it) }
    }

    override fun onPause() {
        wv?.onPause()
        wv?.pauseTimers()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
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
        (wv?.parent as? android.view.ViewGroup)?.removeView(wv)
        wv?.destroy()
        wv = null
        if (::vencordNative.isInitialized) vencordNative.shutdown()
        fetchExecutor.shutdown()
        super.onDestroy()
    }

    fun injectVencordIfReady() {
        val runtime = HttpClient.VencordRuntime
        val mobileRuntime = HttpClient.VencordMobileRuntime
        if (wv != null && (runtime != null || mobileRuntime != null)) {
            val script = buildString {
                runtime?.let { append(it).append(';') }
                mobileRuntime?.let { append(it).append(';') }
            }
            wv?.evaluateJavascript(script, null)
        }
    }

    fun showDiscordToast(message: String, type: String) {
        wv?.post(Runnable {
            wv?.evaluateJavascript(
                "toasts=Vencord.Webpack.Common.Toasts; toasts.show({id: toasts.genId(), message: \"$message\", type: toasts.Type.$type, options: {position: toasts.Position.BOTTOM,}})",
                null
            )
        })
    }

    companion object {
        const val FILECHOOSER_RESULTCODE = 8485
        private val gson = Gson()
    }

    private fun explodeAndroid() {
        StrictMode.setThreadPolicy(
            ThreadPolicy.Builder()
                .build()
        )
    }
}
