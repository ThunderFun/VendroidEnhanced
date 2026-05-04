package com.nin0dev.vendroid.webview

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.annotation.RequiresApi
import com.nin0dev.vendroid.utils.Constants
import java.io.ByteArrayInputStream
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
        val host = url.host
        val isDiscordDomain = host != null && Constants.isDiscordDomain(host)
        if (isDiscordDomain || url.scheme == "about") {
            return false
        }
        val scheme = url.scheme
        if (scheme == "http" || scheme == "https") {
            val intent = Intent(Intent.ACTION_VIEW, url)
            try {
                view.context.startActivity(intent)
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(appContext, "No app found to open this link", Toast.LENGTH_SHORT).show()
            }
        }
        return true
    }

    private val disableHighlightCss = "(function(){if(document.getElementById('vendroid-disable-highlight'))return;var s=document.createElement('style');s.id='vendroid-disable-highlight';s.textContent='*,*::before,*::after{-webkit-tap-highlight-color:transparent!important;outline:none!important}';var t=document.head||document.documentElement;if(t)t.appendChild(s)})()"

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        val activity = activityRef.get()
        (activity as? com.nin0dev.vendroid.MainActivity)?.currentUrlForBridge = url
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
        (activity as? com.nin0dev.vendroid.MainActivity)?.currentUrlForBridge = url
        view.evaluateJavascript(disableHighlightCss, null)

        if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
            (activity as? com.nin0dev.vendroid.MainActivity)?.scheduleLoadingScreenDismiss(500)
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
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

    private fun isForgeHost(host: String): Boolean =
        host in FORGE_HOSTS_EXACT || host.endsWith(".github.io") || host.endsWith(".codeberg.page")

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

        val bodyBytes = if (statusCode >= 400) {
            try { conn.errorStream?.use { it.readBytes() } } catch (_: Exception) { null } ?: ByteArray(0)
        } else {
            conn.inputStream.use { it.readBytes() }
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
