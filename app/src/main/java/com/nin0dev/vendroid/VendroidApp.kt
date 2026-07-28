package com.nin0dev.vendroid

import android.app.Application
import android.content.Context
import android.os.Build
import android.view.View
import android.webkit.WebView
import com.nin0dev.vendroid.webview.HttpClient
import com.nin0dev.vendroid.utils.FirewallConfig
import com.nin0dev.vendroid.utils.Logger.e
import com.nin0dev.vendroid.utils.VDELog
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class VendroidApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val isWebProcess = getCurrentProcessName().endsWith(":web")

        if (isWebProcess) {
            // Initialize in-app logging first — must happen before anything else
            // so that subsequent init steps are captured even if they crash.
            VDELog.init(applicationContext)
            VDELog.i("VDE", "App started (PID=${android.os.Process.myPid()})")

            // Initialize the firewall config before any WebView request can
            // fire. shouldInterceptRequest() reads the snapshot via
            // Constants.isAllowedDomain(), so it must be built up-front.
            FirewallConfig.init(applicationContext)
            VDELog.i("VDE", "Firewall config initialized")

            // On Android P+, WebView requires a unique data directory suffix
            // for each non-default process. Without this, creating a WebView in
            // the :web process crashes with an IllegalStateException.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                WebView.setDataDirectorySuffix("web")
            }

            // Pre-warm the Chromium renderer process by creating a WebView.
            // Keeping the reference alive (instead of destroy()-ing it) ensures
            // the renderer process stays warm, so MainActivity's WebView
            // creation is significantly faster (~50-100ms vs ~200-500ms cold).
            // The pre-warmed WebView can also be reused directly in MainActivity,
            // eliminating the second WebView creation entirely.
            try {
                prewarmedWebView = WebView(this).apply {
                    setBackgroundColor(android.graphics.Color.parseColor("#121214"))
                }
            } catch (e: Exception) {
                e("Failed to create prewarmed WebView", e)
            }

            // Install HTTP response cache for HttpURLConnection-based fetches
            // (Vencord bundle download, shouldInterceptRequest CSS fetches).
            // Enables 304 Not Modified responses and avoids re-downloading
            // unchanged resources.
            try {
                val httpCacheDir = File(cacheDir, "http_cache")
                httpCacheDir.mkdirs()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    android.net.http.HttpResponseCache.install(httpCacheDir, 50L * 1024 * 1024)
                } else {
                    val cls = Class.forName("android.net.http.HttpResponseCache")
                    cls.getMethod("install", File::class.java, Long::class.javaPrimitiveType)
                        .invoke(null, httpCacheDir, 50L * 1024 * 1024)
                }
            } catch (e: Exception) {
                e("Failed to install HTTP response cache", e)
            }
            VDELog.i("VDE", "HTTP response cache installed")

            // One-time migration: move CSS cache entries from the shared
            // "settings" prefs file into a dedicated "css_cache" file.
            // This keeps the settings file small (faster cold-start parse)
            // and isolates CSS write churn.
            Thread {
                try {
                    val settingsPrefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
                    if (settingsPrefs.getBoolean("css_cache_migrated", false)) return@Thread
                    val cssPrefs = getSharedPreferences("css_cache", Context.MODE_PRIVATE)
                    val editor = cssPrefs.edit()
                    val settingsEditor = settingsPrefs.edit()
                    var migrated = false
                    for ((key, value) in settingsPrefs.all) {
                        if (!key.startsWith("css_cache_")) continue
                        when (value) {
                            is String -> { editor.putString(key, value); migrated = true }
                            is Long -> { editor.putLong(key, value); migrated = true }
                        }
                        settingsEditor.remove(key)
                    }
                    settingsEditor.putBoolean("css_cache_migrated", true)
                    settingsEditor.apply()
                    if (migrated) editor.apply()
                } catch (ex: Exception) {
                    e("CSS cache migration failed", ex)
                }
            }.start()

            // Pre-load Vencord runtimes on a background thread so that by the
            // time MainActivity.onCreate() runs the strings are already in
            // memory and injection can fire immediately.
            Thread {
                try {
                    // 1. VencordMobile runtime (65 KB raw resource, memory-mapped)
                    if (HttpClient.VencordMobileRuntime == null) {
                        resources.openRawResource(R.raw.vencord_mobile).use { inputStream ->
                            HttpClient.setVencordMobileRuntime(HttpClient.readAsText(inputStream))
                        }
                    }
                    VDELog.i("VDE", "VencordMobile runtime preloaded")
                    // 2. Vencord runtime (potentially ~1 MB from disk)
                    val vendroidFile = File(filesDir, "vencord.js")
                    // Don't serve a stale bundle: if a redownload is pending
                    // (clientMod switch or app version bump), drop the file
                    // before the preload read so fetchVencord can replace it.
                    val sPrefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
                    val needsRedownload = sPrefs.getInt(
                        "lastMajorUpdateThatUserHasUpdatedVencord", 0
                    ) < com.nin0dev.vendroid.BuildConfig.VERSION_CODE
                    if (needsRedownload) {
                        vendroidFile.delete()
                        HttpClient.vencordBundlePatched = false
                        sPrefs.edit().remove("vencordEtag").apply()
                    }
                    if (vendroidFile.exists() && HttpClient.VencordRuntime == null) {
                        try {
                            // The file was written with applyPatches already
                            // applied during a previous download.  Skip the
                            // redundant ~1MB regex scan.
                            HttpClient.setVencordRuntime(
                                if (HttpClient.vencordBundlePatched) vendroidFile.readText()
                                else HttpClient.applyPatches(vendroidFile.readText())
                            )
                            VDELog.i("VDE", "Vencord runtime preloaded (${vendroidFile.length()} bytes)")
                        } catch (ex: Exception) {
                            e("Failed to apply Vencord patches", ex)
                            VDELog.e("VDE", "Failed to apply Vencord patches: ${ex.message}", ex)
                        }
                    }
                } catch (ex: Exception) {
                    e("Vencord preload failed", ex)
                    VDELog.e("VDE", "Vencord preload failed: ${ex.message}", ex)
                }
            }.start()

            // 3. Pre-fetch and cache the Vencord CSS files.  These are injected
            // by vencord_mobile.js on every page load.  Stashing them in
            // the dedicated css_cache prefs means JS can skip the network fetch
            // entirely without churning the main settings XML.
            Thread {
                try {
                    val sPrefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
                    val cssPrefs = getSharedPreferences("css_cache", Context.MODE_PRIVATE)
                    val isEquicord = sPrefs.getString("clientMod", "vencord") == "equicord"
                    val cssUrls = listOf(
                        if (isEquicord) "https://vde-builds.nin0.dev/equicord/browser.css"
                        else "https://vde-builds.nin0.dev/vencord/browser.css",
                        "https://raw.githubusercontent.com/VendroidEnhanced/random-files/refs/heads/main/moreFixes.css"
                    )
                    val editor = cssPrefs.edit()
                    val now = System.currentTimeMillis()
                    for (url in cssUrls) {
                        val key = "css_cache_vde_" + url.hashCode()
                        VDELog.d("VDE", "Prefetching CSS: $url")
                        // Only re-fetch if missing or older than 12 hours
                        val ts = cssPrefs.getLong("${key}_ts", 0)
                        if (cssPrefs.getString(key, null) == null || now - ts > 12 * 60 * 60 * 1000L) {
                            var conn: HttpURLConnection? = null
                            try {
                                if (!url.startsWith("https://")) {
                                    VDELog.w("VDE", "CSS prefetch rejected non-HTTPS URL: $url")
                                    continue
                                }
                                conn = URL(url).openConnection() as HttpURLConnection
                                conn.connectTimeout = 15000
                                conn.readTimeout = 15000
                                conn.instanceFollowRedirects = false
                                val code = conn.responseCode
                                if (code in 200..299) {
                                    val css = HttpClient.readAsText(conn.inputStream)
                                    editor.putString(key, css)
                                    editor.putLong("${key}_ts", now)
                                } else if (code in 300..399) {
                                    VDELog.w("VDE", "CSS prefetch rejected redirect ($code) from $url")
                                }
                            } catch (ex: Exception) {
                                e("CSS fetch failed for $url", ex)
                                VDELog.w("VDE", "CSS fetch failed for $url: ${ex.message}")
                            } finally {
                                conn?.disconnect()
                            }
                        }
                    }
                    editor.apply()
                } catch (ex: Exception) {
                    e("CSS prefetch thread failed", ex)
                }
            }.start()

            // 4. Warm up the Chromium cookie DB so MainActivity doesn't pay
            // the cost on its first CookieManager.getInstance() call.
            Thread {
                try {
                    android.webkit.CookieManager.getInstance()
                } catch (ex: Exception) {
                    e("CookieManager warmup failed", ex)
                }
            }.start()
        }
    }

    companion object {
        @Volatile
        var prewarmedWebView: WebView? = null
            internal set

        /**
         * Destroys the pre-warmed WebView if it was never consumed by
         * MainActivity. Call from MainActivity.onDestroy() when
         * prewarmUsed == false to avoid leaking the renderer process.
         */
        fun destroyPrewarmedWebViewIfUnused() {
            prewarmedWebView?.destroy()
            prewarmedWebView = null
        }
    }

    private fun getCurrentProcessName(): String {
        // Application.getProcessName() is a static method available from API 28.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return Application.getProcessName()
        }
        // Fallback for API 26-27: read the process name from /proc/self/cmdline.
        return try {
            val bytes = java.io.File("/proc/self/cmdline").readBytes()
            val end = bytes.indexOf(0.toByte())
            String(bytes, 0, if (end > 0) end else bytes.size)
        } catch (ex: Exception) {
            e("getCurrentProcessName fallback failed", ex)
            ""
        }
    }
}
