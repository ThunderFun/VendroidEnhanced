package com.nin0dev.vendroid.webview

import android.net.Uri
import com.nin0dev.vendroid.utils.Constants

/**
 * Pure navigation policy for WebView URL loads. Callers must already have
 * normalized the URL via [UrlNormalizer].
 *
 * Subframe navigations always load in-WebView; their subresources are
 * governed by shouldInterceptRequest. Main-frame navigations to Discord-owned
 * domains load in-WebView, with two exceptions that route to the link popup:
 *  - /blog paths: standalone marketing pages the app can't navigate back out
 *    of (hardlock), better opened in the system browser.
 *  - all other hosts.
 */
object NavigationPolicy {

    enum class Action { LOAD_IN_WEBVIEW, SHOW_POPUP }

    fun decide(scheme: String?, asciiHost: String?, isForMainFrame: Boolean): Action {
        if (scheme == "about") return Action.LOAD_IN_WEBVIEW
        if (!isForMainFrame) return Action.LOAD_IN_WEBVIEW
        if (asciiHost != null && Constants.isNavigationAllowedDomain(asciiHost)) {
            return Action.LOAD_IN_WEBVIEW
        }
        return Action.SHOW_POPUP
    }

    /**
     * Main-frame variant that takes the full URL so path-based rules can apply.
     * Currently routes Discord-owned /blog pages to the popup (see class doc).
     */
    fun decide(url: Uri, isForMainFrame: Boolean): Action {
        if (!isForMainFrame) return Action.LOAD_IN_WEBVIEW
        if (url.scheme == "about") return Action.LOAD_IN_WEBVIEW
        val host = url.host
        if (host != null && Constants.isNavigationAllowedDomain(host)) {
            val path = url.path ?: ""
            if (path.startsWith("/blog")) return Action.SHOW_POPUP
            return Action.LOAD_IN_WEBVIEW
        }
        return Action.SHOW_POPUP
    }
}