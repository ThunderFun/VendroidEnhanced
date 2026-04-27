package com.nin0dev.vendroid

import android.app.Application
import android.webkit.WebView

class VendroidApp : Application() {
    override fun onCreate() {
        super.onCreate()
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
