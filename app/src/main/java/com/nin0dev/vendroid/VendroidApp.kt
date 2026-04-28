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

            // Pre-warm the Chromium process by creating a throwaway WebView.
            // This front-loads the ~200-500ms cost of spawning the WebView
            // process so that MainActivity's WebView creation is faster.
            try {
                val webView = WebView(this)
                webView.destroy()
            } catch (_: Exception) {
                // Silently ignore — some ROMs or restricted environments may fail
            }
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
