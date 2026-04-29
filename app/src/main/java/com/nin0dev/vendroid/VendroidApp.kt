package com.nin0dev.vendroid

import android.app.Application
import android.os.Build
import android.webkit.WebView

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
                prewarmedWebView = WebView(this)
            } catch (_: Exception) {
                // Silently ignore — some ROMs or restricted environments may fail
            }
        }
    }

    companion object {
        @Volatile
        var prewarmedWebView: WebView? = null
            internal set
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
