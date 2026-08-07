package com.nin0dev.vendroid.webview

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.webkit.WebSettings
import android.webkit.WebView

/**
 * Helper for building a full-screen, black-themed dialog that hosts a
 * WebView configured with the app's secure defaults. The dialog is
 * cancelable and the WebView is destroyed when it is dismissed.
 *
 * Used by the QuickCSS, log-viewer, and firewall editors to avoid
 * duplicating the WebView/dialog wiring in each open path.
 */object SecureWebViewDialog {

    /** Configures [wv] with the app's standard secure WebView settings. */
    fun configure(wv: WebView) {
        val s = wv.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        wv.setBackgroundColor(Color.parseColor("#0f0f10"))
    }

    /**
     * Creates a full-screen black dialog hosting [wv]. The dialog is
     * cancelable and destroys [wv] on dismissal. [onDismiss] is invoked after
     * the WebView is destroyed (e.g. to reset an "active" flag). Callers must
     * call [Dialog.show] and load the asset HTML themselves.
     */    fun create(activity: Activity, wv: WebView, onDismiss: (() -> Unit)? = null): Dialog =
        Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen).apply {
            setContentView(wv)
            setCancelable(true)
            setOnDismissListener {
                wv.destroy()
                onDismiss?.invoke()
            }
        }
}
