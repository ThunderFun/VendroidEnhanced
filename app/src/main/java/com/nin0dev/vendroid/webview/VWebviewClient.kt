package com.nin0dev.vendroid.webview

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.RequiresApi
import com.nin0dev.vendroid.utils.Constants
import com.nin0dev.vendroid.utils.FirewallConfig
import com.nin0dev.vendroid.utils.JsPatches
import com.nin0dev.vendroid.utils.VDELog
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL

class VWebviewClient(
    context: Context
) : WebViewClient() {
    private val appContext: Context = context.applicationContext
    private val activityRef: WeakReference<Activity> = if (context is Activity) WeakReference(context) else WeakReference(null)
    private val linkHandler: LinkHandler = LinkHandler(context)

    private class CachedResponse(
        val statusCode: Int,
        val reasonPhrase: String,
        val headers: Map<String, String>,
        val body: ByteArray,
        val fetchedAt: Long = System.currentTimeMillis()
    )

    private enum class CacheTarget { THEME_CSS, MAIN_FRAME }

    // Static caches survive across MainActivity recreations (rotation, memory
    // pressure, etc.) so previously-fetched CSS / HTML is still warm.

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url
        val scheme = url.scheme
        if (scheme == "about") {
            return false
        }
        val host = url.host
        // Whitelisted hosts (Discord, GitHub, etc.) load inside the WebView.
        if (host != null && Constants.isAllowedDomain(host)) {
            return false
        }
        // Non-allowlisted links go to the link popup (Copy / Open / Share /
        // Cancel). Returning true cancels in-WebView navigation and prevents the
        // click from bubbling up to Discord, which would treat it as a message tap.
        VDELog.d("WV", "External link: $url")
        linkHandler.showLinkPopup(url)
        return true
    }

    private val disableHighlightCss = "html{-webkit-tap-highlight-color:transparent}a,button,[role=\"button\"],input,textarea,select,[tabindex]:not([tabindex=\"-1\"]){outline:none}"

    private fun maybeInjectStyleIntoHtml(text: String): String? {
        val headIdx = text.indexOf("</head>", ignoreCase = true)
        if (headIdx <= 0) return null
        val tag = "<style id=\"vendroid-disable-highlight\">$disableHighlightCss</style>"
        return text.substring(0, headIdx) + tag + text.substring(headIdx)
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        val activity = activityRef.get()
        (activity as? com.nin0dev.vendroid.MainActivity)?.let {
            it.currentUrlForBridge = url
            it.currentHostForBridge = Uri.parse(url).host
        }
        VDELog.i("WV", "Page started: $url")

        // If shouldInterceptRequest already embedded the firewall JS into the
        // HTML for this URL, we can skip the evaluateJavascript entirely —
        // the embedded script runs at parse time (before any page JS) and the
        // animation patches are only needed for background/foreground toggling
        // which happens after page load.  Otherwise, inject the combined
        // firewall + animation patches in a single IPC call.
        if (!companionFirewallEmbedded(url)) {
            view.evaluateJavascript(JsPatches.STARTUP_PATCHES_JS, null)
        }

        view.evaluateJavascript("typeof Vencord!=='undefined'&&typeof VencordMobile!=='undefined'") { result ->
            if (result?.trim() == "true") return@evaluateJavascript
            // Only inject the Vencord runtimes on Discord pages. The runtimes
            // hook webpack and patch globals designed for Discord; running them
            // on whitelisted non-Discord pages (github.com, *.github.io, etc.)
            // is unnecessary and exposes the bridge object to third-party pages.
            val host = Uri.parse(url).host ?: ""
            if (!Constants.isDiscordDomain(host)) return@evaluateJavascript
            val runtime = HttpClient.VencordRuntime
            val mobileRuntime = HttpClient.VencordMobileRuntime
            if (runtime != null && mobileRuntime != null) {
                // Evaluate runtimes separately to avoid building a ~1 MB
                // intermediate string on the UI thread.
                view.evaluateJavascript(runtime + ";", null)
                view.evaluateJavascript(mobileRuntime + ";", null)
            } else {
                (activityRef.get() as? com.nin0dev.vendroid.MainActivity)?.missedInjection = true
            }
        }
    }

    override fun onPageFinished(view: WebView, url: String) {
        super.onPageFinished(view, url)
        val activity = activityRef.get()
        (activity as? com.nin0dev.vendroid.MainActivity)?.let {
            it.currentUrlForBridge = url
            it.currentHostForBridge = Uri.parse(url).host
        }
        VDELog.d("WV", "Page finished: $url")

        if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
            (activity as? com.nin0dev.vendroid.MainActivity)?.scheduleLoadingScreenDismiss(500)
        }
    }

    /**
     * Check if the firewall JS was already embedded in the HTML for [url]
     * by a prior shouldInterceptRequest pass.  Consumes the flag (removes it)
     * so it's only used once per URL.
     */
    private fun companionFirewallEmbedded(url: String): Boolean {
        return firewallEmbeddedUrls.remove(url)
    }

    @RequiresApi(Build.VERSION_CODES.N)
    override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
        // Restrict to browser/inline schemes. data:, file:, intent:, and
        // custom schemes have a null host and would otherwise bypass the
        // domain allowlist below. blob: is needed for Discord media; data:
        // for embedded images/CSS.
        val scheme = req.url.scheme
        if (scheme != "https" && scheme != "http" && scheme != "blob" && scheme != "data") {
            return WebResourceResponse("text/plain", "utf-8", 204, "No Content", emptyMap(), java.io.ByteArrayInputStream(ByteArray(0)))
        }
        val host = req.url.host
        if (host != null && !Constants.isAllowedDomain(host)) {
            // Block non-whitelisted subresources (scripts, images, XHR, media, etc.)
            return WebResourceResponse("text/plain", "utf-8", 204, "No Content", emptyMap(), java.io.ByteArrayInputStream(ByteArray(0)))
        }
        if (scheme == "http") {
            return WebResourceResponse("text/plain", "utf-8", 204, "No Content", emptyMap(), java.io.ByteArrayInputStream(ByteArray(0)))
        }
        // Privacy: block Discord telemetry, Sentry, and fingerprinting before
        // the CSP-stripping/fetch path so they never reach the network.
        shouldBlockForPrivacy(host, req.url.path)?.let { return it }
        if (!shouldInterceptForCspStripping(req)) return null
        val urlString = req.url.toString()
        val isCss = req.url.path?.endsWith(".css") == true
        val isThemeCss = isCss && isVencordCssUrl(req.url)
        val isMainFrame = req.isForMainFrame

        if (isThemeCss) {
            themeCssCache.get(urlString)?.let { cached ->
                if (System.currentTimeMillis() - cached.fetchedAt < THEME_CSS_TTL_MS) {
                    return WebResourceResponse("text/css", "utf-8", cached.statusCode, cached.reasonPhrase, cached.headers, ByteArrayInputStream(cached.body))
                }
                themeCssCache.remove(urlString)
            }
        }

        if (isMainFrame) {
            mainFrameCache.get(urlString)?.let { cached ->
                if (System.currentTimeMillis() - cached.fetchedAt < MAIN_FRAME_TTL_MS) {
                    val ct = cached.headers.getOrDefault("Content-Type", "text/html")
                    return WebResourceResponse(ct, "utf-8", cached.statusCode, cached.reasonPhrase, cached.headers, ByteArrayInputStream(cached.body))
                }
                mainFrameCache.remove(urlString)
            }
        }

        var conn: HttpURLConnection? = null
        try {
            conn = URL(urlString).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.requestMethod = req.method
            conn.instanceFollowRedirects = false
            if (isThemeCss) {
                conn.useCaches = false
                conn.setRequestProperty("Cache-Control", "no-cache")
                conn.setRequestProperty("Pragma", "no-cache")
            }
            for ((key, value) in req.requestHeaders) {
                val lowerKey = key.lowercase()
                if (isThemeCss && lowerKey in STRIPPED_CONDITIONAL_HEADERS) continue
                if (lowerKey == "accept-encoding") continue
                conn.setRequestProperty(key, value)
            }
            val cacheTarget = if (isThemeCss) CacheTarget.THEME_CSS else if (isMainFrame) CacheTarget.MAIN_FRAME else null
            val response = doFetch(req, conn, isCss, cacheTarget, urlString)
            conn.disconnect()
            return response
        } catch (_: Exception) {
            conn?.disconnect()
            return null
        }
    }

    private fun isVencordCssUrl(uri: Uri): Boolean {
        val host = uri.host ?: return false
        if (!isForgeHost(host)) return false
        val urlLower = uri.toString().lowercase()
        return urlLower.contains("vencord") || urlLower.contains("equicord") || urlLower.contains("vendroid")
    }

    private val FORGE_HOSTS_EXACT = hashSetOf(
        "github.com", "raw.githubusercontent.com", "codeberg.org",
        "githack.com", "raw.githack.com", "cdn.githack.com", "cbcdn.githack.com"
    )

    private val forgeHostCache = ConcurrentHashMap<String, Boolean>()

    private fun isForgeHost(host: String): Boolean =
        forgeHostCache.computeIfAbsent(host) { h ->
            h in FORGE_HOSTS_EXACT || h.endsWith(".github.io") || h.endsWith(".codeberg.page") || h.endsWith(".githack.com")
        } ?: false

    private fun shouldInterceptForCspStripping(req: WebResourceRequest): Boolean {
        val scheme = req.url.scheme ?: return false
        if (scheme != "http" && scheme != "https") return false

        if (req.url.path?.endsWith(".css") == true) {
            val host = req.url.host ?: return false
            if (isForgeHost(host)) return true
        }

        if (req.isForMainFrame) {
            val host = req.url.host ?: return false
            if (Constants.isDiscordDomain(host)) return true
        }

        return false
    }

    private fun stripVencordIncompatibleCsp(cspValue: String): String {
        return cspValue.split(";")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filterNot { directive ->
                val directiveName = directive.substringBefore(" ").lowercase()
                directiveName in VENCORD_INCOMPATIBLE_CSP_DIRECTIVES
            }
            .joinToString("; ")
            .ifEmpty { "frame-ancestors 'none'; base-uri 'none'; object-src 'none'" }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun doFetch(
        req: WebResourceRequest,
        conn: HttpURLConnection,
        isCss: Boolean,
        cacheTarget: CacheTarget? = null,
        urlString: String = ""
    ): WebResourceResponse {
        val host = req.url.host ?: ""
        val isDiscordDomain = Constants.isDiscordDomain(host)
        val isMainFrame = req.isForMainFrame

        val statusCode = conn.responseCode

        val modifiedHeaders = HashMap<String, String>(conn.headerFields?.size ?: 16)
        var i = 0
        while (true) {
            val key = conn.getHeaderFieldKey(i)
            val value = conn.getHeaderField(i)
            i++
            if (key == null && value == null) break
            if (key == null) continue  // skip status line at index 0
            val lowerKey = key.lowercase()
            if (isDiscordDomain && lowerKey == "content-security-policy") {
                val stripped = stripVencordIncompatibleCsp(value)
                if (stripped.isNotEmpty()) modifiedHeaders[key] = stripped
                continue
            }
            if (isDiscordDomain && lowerKey == "content-security-policy-report-only") continue
            modifiedHeaders[key] = value
        }
        if (isCss) modifiedHeaders["Content-Type"] = "text/css"
        val contentType = modifiedHeaders.getOrDefault("Content-Type", "application/octet-stream")
        val reasonPhrase = conn.responseMessage.takeIf { it.isNotEmpty() } ?: "OK"

        var bodyBytes = if (statusCode >= 400) {
            try { conn.errorStream?.use { it.readBytes() } } catch (_: Exception) { null } ?: ByteArray(0)
        } else {
            conn.inputStream.use { it.readBytes() }
        }

        // Inject the JS network firewall into every Discord HTML response.
        // This runs before any page scripts and prevents SW registration +
        // wraps fetch/XHR/WebSocket for hosts that slip past shouldInterceptRequest.
        if (isDiscordDomain && isMainFrame && statusCode in 200..299 && bodyBytes.isNotEmpty()) {
            val ctLower = (modifiedHeaders.getOrDefault("Content-Type", "")).lowercase()
            if (ctLower.contains("text/html")) {
                try {
                    val text = bodyBytes.toString(Charsets.UTF_8)
                    val headIdx = text.indexOf("</head>", ignoreCase = true)
                    if (headIdx > 0) {
                        var patched = text.substring(0, headIdx) + "<script>${JsPatches.NETWORK_FIREWALL_JS}</script>" + text.substring(headIdx)
                        // Inject the disable-highlight CSS at parse time instead of via
                        // evaluateJavascript later. This avoids a full style recalculation
                        // after the DOM is already built.
                        maybeInjectStyleIntoHtml(patched)?.let { cssPatched ->
                            patched = cssPatched
                        }
                        bodyBytes = patched.toByteArray(Charsets.UTF_8)
                        modifiedHeaders["Content-Length"] = bodyBytes.size.toString()
                        modifiedHeaders.remove("Content-Encoding")
                        modifiedHeaders.remove("content-encoding")
                        // Record that this URL's HTML already contains the
                        // firewall so onPageStarted can skip re-injecting it.
                        if (firewallEmbeddedUrls.size < MAX_FIREWALL_TRACKED) {
                            firewallEmbeddedUrls.add(urlString)
                            VDELog.i("WV", "Embedded firewall JS: $host (${FirewallConfig.jsAllowedHosts().size} hosts)")
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        if (statusCode in 200..299 && cacheTarget != null) {
            val entry = CachedResponse(statusCode, reasonPhrase, modifiedHeaders, bodyBytes)
            when (cacheTarget) {
                CacheTarget.THEME_CSS -> themeCssCache.put(urlString, entry)
                CacheTarget.MAIN_FRAME -> mainFrameCache.put(urlString, entry)
            }
        }

        return WebResourceResponse(contentType, "utf-8", statusCode, reasonPhrase, modifiedHeaders, ByteArrayInputStream(bodyBytes))
    }

    companion object {
        private const val THEME_CSS_TTL_MS = 60_000L
        private const val MAIN_FRAME_TTL_MS = 30_000L
        private val STRIPPED_CONDITIONAL_HEADERS = setOf(
            "if-none-match", "if-modified-since", "if-unmodified-since", "if-match"
        )
        private val VENCORD_INCOMPATIBLE_CSP_DIRECTIVES = hashSetOf(
            "default-src", "script-src", "script-src-elem", "script-src-attr",
            "style-src", "style-src-elem", "style-src-attr",
            "connect-src", "img-src", "font-src", "media-src",
            "worker-src", "manifest-src", "child-src"
        )

        // Thread-safe LRU cache wrapping LruCache because
        // shouldInterceptRequest runs on Chromium network threads and can be
        // invoked concurrently.
        private class ThreadSafeLruCache(maxSize: Int) {
            private val cache = object : LruCache<String, CachedResponse>(maxSize) {
                override fun sizeOf(key: String, value: CachedResponse): Int = value.body.size
            }
            @Synchronized fun get(key: String): CachedResponse? = cache.get(key)
            @Synchronized fun put(key: String, value: CachedResponse): CachedResponse? = cache.put(key, value)
            @Synchronized fun remove(key: String): CachedResponse? = cache.remove(key)
        }

        private val themeCssCache = ThreadSafeLruCache(2 * 1024 * 1024)
        private val mainFrameCache = ThreadSafeLruCache(2 * 1024 * 1024)

        // Track main-frame URLs that already have the network firewall JS
        // embedded in the HTML (from shouldInterceptRequest / doFetch).
        // When onPageStarted fires for one of these, the combined
        // firewall+animation evaluateJavascript can be skipped entirely,
        // saving one IPC round-trip.  Capped at 16 entries to bound memory.
        private val firewallEmbeddedUrls = ConcurrentHashMap.newKeySet<String>()
        private const val MAX_FIREWALL_TRACKED = 16

        // Privacy filter — blocks Discord telemetry, Sentry, fingerprinting,
        // and (optionally) typing indicators at the path level. Shared by
        // VWebviewClient.shouldInterceptRequest and the Service Worker client
        // in MainActivity so SW-fetched requests can't bypass it.

        @Volatile
        private var blockTypingIndicator = false

        private val SENTRY_PATTERN = Regex("^/assets/sentry\\.[^/]+\\.js$")

        /** Update the typing indicator block. Called at startup and on toggle. */
        fun updateTypingBlock(block: Boolean) {
            blockTypingIndicator = block
        }

        /**
         * Returns a blocking [WebResourceResponse] if the request matches a
         * privacy filter, or null to allow. Always blocks telemetry (/science,
         * /track), Sentry SDK, and fingerprinting (/api.js, /cdn-cgi/). Blocks
         * typing indicators only if [blockTypingIndicator] is true.
         *
         * A fresh [WebResourceResponse] is allocated per call to match the
         * existing host-block pattern and avoid relying on Chromium
         * stream-reuse semantics.
         */
        fun shouldBlockForPrivacy(host: String?, path: String?): WebResourceResponse? {
            if (host == null || path.isNullOrEmpty()) return null
            if (!Constants.isDiscordDomain(host)) return null
            return when {
                path.endsWith("/science") || path.endsWith("/track") -> {
                    VDELog.d("WV", "Blocked telemetry: $path")
                    WebResourceResponse("text/plain", "utf-8", 204, "No Content", emptyMap(), java.io.ByteArrayInputStream(ByteArray(0)))
                }
                path.endsWith("/api.js") || path.startsWith("/cdn-cgi/") -> {
                    VDELog.d("WV", "Blocked fingerprinting: $path")
                    WebResourceResponse("text/plain", "utf-8", 204, "No Content", emptyMap(), java.io.ByteArrayInputStream(ByteArray(0)))
                }
                SENTRY_PATTERN.matches(path) -> {
                    VDELog.d("WV", "Blocked Sentry SDK: $path")
                    WebResourceResponse("text/plain", "utf-8", 204, "No Content", emptyMap(), java.io.ByteArrayInputStream(ByteArray(0)))
                }
                blockTypingIndicator && path.endsWith("/typing") -> {
                    VDELog.d("WV", "Blocked typing indicator: $path")
                    WebResourceResponse("text/plain", "utf-8", 204, "No Content", emptyMap(), java.io.ByteArrayInputStream(ByteArray(0)))
                }
                else -> null
            }
        }
    }
}
