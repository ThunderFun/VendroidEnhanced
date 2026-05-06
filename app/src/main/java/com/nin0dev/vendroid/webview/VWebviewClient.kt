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

    private class CachedResponse(
        val statusCode: Int,
        val reasonPhrase: String,
        val headers: Map<String, String>,
        val body: ByteArray,
        val fetchedAt: Long = System.currentTimeMillis()
    )

    private enum class CacheTarget { THEME_CSS, MAIN_FRAME }

    private val themeCssCache = object : LruCache<String, CachedResponse>(512 * 1024) {
        override fun sizeOf(key: String, value: CachedResponse): Int = value.body.size
    }

    private val mainFrameCache = object : LruCache<String, CachedResponse>(512 * 1024) {
        override fun sizeOf(key: String, value: CachedResponse): Int = value.body.size
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url
        val scheme = url.scheme
        if (scheme == "about") {
            return false
        }
        val host = url.host
        if (host != null && Constants.isAllowedDomain(host)) {
            return false
        }
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

        // Firewall backup: JS injection against cached HTML loads where
        // shouldInterceptRequest isn't invoked. Also forces SW unregister
        // in case the page had one registered already.
        view.evaluateJavascript(Constants.NETWORK_FIREWALL_JS, null)

        // Inject animation-control helpers early so they are available before
        // onStop fires (which may pause CSS animations and spoof visibility).
        view.evaluateJavascript(Constants.ANIMATION_PATCH_JS, null)

        view.evaluateJavascript("typeof Vencord!=='undefined'&&typeof VencordMobile!=='undefined'") { result ->
            if (result?.trim() == "true") return@evaluateJavascript
            val runtime = HttpClient.VencordRuntime
            val mobileRuntime = HttpClient.VencordMobileRuntime
            if (runtime != null && mobileRuntime != null) {
                val script = buildString {
                    append(runtime)
                    append(';')
                    append(mobileRuntime)
                    append(';')
                }
                view.evaluateJavascript(script, null)
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

        if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
            (activity as? com.nin0dev.vendroid.MainActivity)?.scheduleLoadingScreenDismiss(500)
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
        val host = req.url.host
        if (host != null && !Constants.isAllowedDomain(host)) {
            // Block non-whitelisted subresources (scripts, images, XHR, media, etc.)
            return WebResourceResponse("text/plain", "utf-8", 204, "No Content", emptyMap(), java.io.ByteArrayInputStream(ByteArray(0)))
        }
        if (!shouldInterceptForCspStripping(req)) return null
        val urlString = req.url.toString()
        val isCss = req.url.path?.endsWith(".css") == true
        val isThemeCss = isCss && isVencordCssUrl(req.url)
        val isMainFrame = req.isForMainFrame

        if (isThemeCss) {
            themeCssCache[urlString]?.let { cached ->
                if (System.currentTimeMillis() - cached.fetchedAt < THEME_CSS_TTL_MS) {
                    return WebResourceResponse("text/css", "utf-8", cached.statusCode, cached.reasonPhrase, cached.headers, ByteArrayInputStream(cached.body))
                }
                themeCssCache.remove(urlString)
            }
        }

        if (isMainFrame) {
            mainFrameCache[urlString]?.let { cached ->
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
            if (isThemeCss) {
                conn.useCaches = false
                conn.setRequestProperty("Cache-Control", "no-cache")
                conn.setRequestProperty("Pragma", "no-cache")
            }
            for ((key, value) in req.requestHeaders) {
                val lowerKey = key.lowercase()
                if (isThemeCss && lowerKey in STRIPPED_CONDITIONAL_HEADERS) continue
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
        "github.com", "raw.githubusercontent.com", "codeberg.org"
    )

    private val forgeHostCache = ConcurrentHashMap<String, Boolean>()

    private fun isForgeHost(host: String): Boolean =
        forgeHostCache.computeIfAbsent(host) { h ->
            h in FORGE_HOSTS_EXACT || h.endsWith(".github.io") || h.endsWith(".codeberg.page")
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
        var i = 1
        while (true) {
            val key = conn.getHeaderFieldKey(i) ?: break
            val value = conn.getHeaderField(i) ?: break
            i++
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
                        var patched = text.substring(0, headIdx) + "<script>${Constants.NETWORK_FIREWALL_JS}</script>" + text.substring(headIdx)
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
    }
}
