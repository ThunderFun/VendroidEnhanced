package com.nin0dev.vendroid.webview

import com.nin0dev.vendroid.utils.Constants

/**
 * Pure navigation policy for WebView URL loads. Callers must already have
 * normalized the URL via [UrlNormalizer].
 *
 * Subframe navigations always load in-WebView; their subresources are
 * governed by shouldInterceptRequest. Main-frame navigations to Discord-owned
 * domains load in-WebView; all others go to the link popup.
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
}