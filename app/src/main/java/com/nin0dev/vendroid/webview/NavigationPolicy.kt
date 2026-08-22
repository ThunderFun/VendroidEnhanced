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

    /**
     * Single source of truth for navigation routing. Takes the full URL so
     * path-based rules can apply (Discord-owned /blog pages route to the popup).
     */
    fun decide(url: Uri, isForMainFrame: Boolean): Action {
        if (!isForMainFrame) return Action.LOAD_IN_WEBVIEW
        if (url.scheme == "about") return Action.LOAD_IN_WEBVIEW
        // Main frames must load over HTTPS. A cleartext http:// frame would
        // expose the session/token to a network attacker; do not rely solely
        // on shouldInterceptRequest to stop it (redirects/service workers
        // could bypass that callback). Route to the popup instead.
        if (url.scheme != "https") return Action.SHOW_POPUP
        val host = url.host
        if (host != null && Constants.isNavigationAllowedDomain(host)) {
            val path = url.path ?: ""
            if (path == "/blog" || path.startsWith("/blog/")) return Action.SHOW_POPUP
            return Action.LOAD_IN_WEBVIEW
        }
        return Action.SHOW_POPUP
    }
}