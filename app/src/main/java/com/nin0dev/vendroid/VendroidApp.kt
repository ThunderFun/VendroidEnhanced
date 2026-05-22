package com.nin0dev.vendroid

import android.app.Application
import android.content.Context
import android.os.Build
import android.view.View
import android.webkit.WebView
import com.nin0dev.vendroid.webview.HttpClient
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class VendroidApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val isWebProcess = getCurrentProcessName().endsWith(":web")

        if (isWebProcess) {
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
                    setLayerType(View.LAYER_TYPE_HARDWARE, null)
                }
            } catch (_: Exception) {
                // Silently ignore — some ROMs or restricted environments may fail
            }

            // Install HTTP response cache for HttpURLConnection-based fetches
            // (Vencord bundle download, shouldInterceptRequest CSS fetches).
            // Enables 304 Not Modified responses and avoids re-downloading
            // unchanged resources.
            try {
                val httpCacheDir = File(cacheDir, "http_cache")
                httpCacheDir.mkdirs()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    android.net.http.HttpResponseCache.install(httpCacheDir, 10L * 1024 * 1024)
                } else {
                    val cls = Class.forName("android.net.http.HttpResponseCache")
                    cls.getMethod("install", File::class.java, Long::class.javaPrimitiveType)
                        .invoke(null, httpCacheDir, 10L * 1024 * 1024)
                }
            } catch (_: Exception) {
                // Hidden API unavailable — continue without HTTP caching
            }

            // Pre-load Vencord runtimes on a background thread so that by the
            // time MainActivity.onCreate() runs the strings are already in
            // memory and injection can fire immediately.
            Thread {
                try {
                    // 1. VencordMobile runtime (65 KB raw resource, memory-mapped)
                    if (HttpClient.VencordMobileRuntime == null) {
                        resources.openRawResource(R.raw.vencord_mobile).use { `is` ->
                            HttpClient.setVencordMobileRuntime(HttpClient.readAsText(`is`))
                        }
                    }
                    // 2. Vencord runtime (potentially ~1 MB from disk)
                    val vendroidFile = File(filesDir, "vencord.js")
                    if (vendroidFile.exists() && HttpClient.VencordRuntime == null) {
                        try {
                            HttpClient.setVencordRuntime(HttpClient.applyPatches(vendroidFile.readText()))
                        } catch (_: Exception) {}
                    }
                } catch (_: Exception) {}
            }.start()

            // 3. Pre-fetch and cache the Vencord CSS files.  These are injected
            // by vencord_mobile.js on every page load.  Stashing them in
            // SharedPreferences means JS can skip the network fetch entirely.
            Thread {
                try {
                    val sPrefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
                    val isEquicord = sPrefs.getString("clientMod", "vencord") == "equicord"
                    val cssUrls = listOf(
                        if (isEquicord) "https://vde-builds.nin0.dev/equicord/browser.css"
                        else "https://vde-builds.nin0.dev/vencord/browser.css",
                        "https://raw.githubusercontent.com/VendroidEnhanced/random-files/refs/heads/main/moreFixes.css"
                    )
                    val editor = sPrefs.edit()
                    val now = System.currentTimeMillis()
                    for (url in cssUrls) {
                        val key = "css_cache_vde_" + url.hashCode()
                        // Only re-fetch if missing or older than 12 hours
                        val ts = sPrefs.getLong("${key}_ts", 0)
                        if (sPrefs.getString(key, null) == null || now - ts > 12 * 60 * 60 * 1000L) {
                            var conn: HttpURLConnection? = null
                            try {
                                conn = URL(url).openConnection() as HttpURLConnection
                                conn.connectTimeout = 15000
                                conn.readTimeout = 15000
                                if (conn.responseCode in 200..299) {
                                    val css = HttpClient.readAsText(conn.inputStream)
                                    editor.putString(key, css)
                                    editor.putLong("${key}_ts", now)
                                }
                            } catch (_: Exception) {} finally {
                                conn?.disconnect()
                            }
                        }
                    }
                    editor.apply()
                } catch (_: Exception) {}
            }.start()

            // 4. Warm up the Chromium cookie DB so MainActivity doesn't pay
            // the cost on its first CookieManager.getInstance() call.
            Thread {
                try {
                    android.webkit.CookieManager.getInstance()
                } catch (_: Exception) {}
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
        } catch (_: Exception) {
            ""
        }
    }
}
